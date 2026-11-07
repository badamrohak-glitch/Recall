
package com.recall.app

import android.Manifest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.recall.app.ui.theme.RecallTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Background = Color(0xFF100F17)
private val CardColor = Color(0xFF201E2B)
private val Purple = Color(0xFFB69CFF)
private val SecondaryText = Color(0xFFAAA6B8)

private val captureCategories = listOf(
    "Products",
    "Articles",
    "Places",
    "Study",
    "Food",
    "Clothing & Accessories",
    "Design & Inspiration",
    "Other"
)

class MainActivity : ComponentActivity() {

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    private var captureImagePath by mutableStateOf<String?>(null)
    private var croppedPreviewPath by mutableStateOf<String?>(null)
    private var requestedTab by mutableStateOf("Home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            FirebaseApp.initializeApp(this)

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        }

        window.navigationBarColor = android.graphics.Color.rgb(16, 15, 23)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK && result.data != null) {

                val captureIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE

                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

                ContextCompat.startForegroundService(
                    this,
                    captureIntent
                )

                moveTaskToBack(true)

            } else {
                Toast.makeText(
                    this,
                    "Screen capture cancelled.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        handleNavigationIntent(intent)
        handleCaptureReady(intent)
        handleCaptureFailure(intent)

        setContent {
            RecallTheme {

                val isCropping = captureImagePath != null && croppedPreviewPath == null

                DisposableEffect(isCropping) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    val navigationBars = WindowInsetsCompat.Type.navigationBars()
                    val wasVisible = ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true

                    if (isCropping) {
                        controller.hide(navigationBars)
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(navigationBars)
                    }

                    onDispose {
                        if (wasVisible) {
                            controller.show(navigationBars)
                        } else {
                            controller.hide(navigationBars)
                        }
                    }
                }

                val imagePath = captureImagePath
                val previewPath = croppedPreviewPath

                when {
                    previewPath != null -> {
                        CaptureDetailsScreen(
                            imagePath = previewPath,
                            onCancel = {
                                File(previewPath).delete()
                                croppedPreviewPath = null
                            },
                            onSave = { title, category, note, tags, alsoSave ->
                                saveMemory(
                                    previewPath = previewPath,
                                    title = title,
                                    category = category,
                                    note = note,
                                    tags = tags,
                                    alsoSave = alsoSave
                                )
                            }
                        )
                    }

                    imagePath != null -> {
                        CaptureCropScreen(
                            imagePath = imagePath,
                            onCancel = {
                                File(imagePath).delete()
                                captureImagePath = null
                                moveTaskToBack(true)
                            },
                            onSave = { cropRect ->
                                createCroppedPreview(imagePath, cropRect)
                            }
                        )
                    }

                    else -> {
                        RecallHomeScreen(
                            initialTab = requestedTab
                        )
                    }
                }
            }
        }

        if (intent?.action == ACTION_REQUEST_SCREEN_CAPTURE) {
            window.decorView.post {
                requestScreenCapture()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureGestureServiceRunning()
    }

    private fun ensureGestureServiceRunning() {

        if (!GestureSettings.isTripleTapEnabled(this)) {
            return
        }

        val hasOverlayPermission = Settings.canDrawOverlays(this)

        val hasNotificationPermission =
            Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

        if (!hasOverlayPermission || !hasNotificationPermission) {
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, GestureDetectionService::class.java)
            )

        } catch (e: Exception) {

            android.util.Log.e(
                "RecallGesture",
                "Could not restore gesture detection.",
                e
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        handleNavigationIntent(intent)
        handleCaptureReady(intent)
        handleCaptureFailure(intent)

        if (intent.action == ACTION_REQUEST_SCREEN_CAPTURE) {
            requestScreenCapture()
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_LIBRARY -> requestedTab = "Library"
            ACTION_OPEN_SEARCH -> requestedTab = "Saved"
        }
    }

    private fun handleCaptureReady(intent: Intent?) {

        if (intent?.action != ScreenCaptureService.ACTION_CAPTURE_READY) {
            return
        }

        val path = intent.getStringExtra(
            ScreenCaptureService.EXTRA_IMAGE_PATH
        )

        if (!path.isNullOrBlank() && File(path).exists()) {

            croppedPreviewPath?.let {
                File(it).delete()
            }

            croppedPreviewPath = null
            captureImagePath = path

        } else {
            Toast.makeText(
                this,
                "Screenshot could not be loaded.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleCaptureFailure(intent: Intent?) {

        if (intent?.action != ScreenCaptureService.ACTION_CAPTURE_FAILED) {
            return
        }

        val message = intent.getStringExtra(
            ScreenCaptureService.EXTRA_ERROR_MESSAGE
        ) ?: "Screen capture failed."

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun requestScreenCapture() {

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        screenCaptureLauncher.launch(
            projectionManager.createScreenCaptureIntent()
        )
    }

    private fun createCroppedPreview(
        imagePath: String,
        cropRect: Rect
    ) {

        var originalBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null

        try {
            originalBitmap = BitmapFactory.decodeFile(imagePath)

            if (originalBitmap == null) {
                throw Exception("Could not open screenshot.")
            }

            val left = (cropRect.left * originalBitmap.width)
                .roundToInt()
                .coerceIn(0, originalBitmap.width - 1)

            val top = (cropRect.top * originalBitmap.height)
                .roundToInt()
                .coerceIn(0, originalBitmap.height - 1)

            val right = (cropRect.right * originalBitmap.width)
                .roundToInt()
                .coerceIn(left + 1, originalBitmap.width)

            val bottom = (cropRect.bottom * originalBitmap.height)
                .roundToInt()
                .coerceIn(top + 1, originalBitmap.height)

            croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            val previewFile = File.createTempFile(
                "recall_preview_",
                ".png",
                cacheDir
            )

            val saved = FileOutputStream(previewFile).use { output ->

                val compressed = croppedBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    output
                )

                output.flush()
                compressed
            }

            if (!saved ||
                !previewFile.exists() ||
                previewFile.length() == 0L
            ) {
                previewFile.delete()
                throw Exception("Could not create cropped preview.")
            }

            croppedPreviewPath = previewFile.absolutePath

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to crop image: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

        } finally {

            originalBitmap?.recycle()

            if (croppedBitmap !== originalBitmap) {
                croppedBitmap?.recycle()
            }
        }
    }

    private fun saveMemory(
        previewPath: String,
        title: String,
        category: String,
        note: String,
        tags: String,
        alsoSave: Boolean
    ) {
        lifecycleScope.launch {
            try {
                val previewFile = File(previewPath)
                if (!previewFile.exists() || previewFile.length() == 0L) {
                    throw Exception("Preview image is missing or empty.")
                }

                val captureFolder = File(
                    filesDir,
                    if (alsoSave) "recall_captures" else "recall_library_only"
                )

                if (!captureFolder.exists() && !captureFolder.mkdirs()) {
                    throw Exception(
                        "Could not create Library folder:\n" + captureFolder.absolutePath
                    )
                }

                val savedFile = File.createTempFile("recall_", ".png", captureFolder)
                previewFile.copyTo(target = savedFile, overwrite = true)

                if (!savedFile.exists() || savedFile.length() == 0L) {
                    savedFile.delete()
                    throw Exception("Image could not be copied successfully.")
                }

                val preferences = getSharedPreferences("recall_library", MODE_PRIVATE)
                val finalTitle = title.ifBlank { savedFile.nameWithoutExtension }

                val metadataSaved = preferences.edit()
                    .putString("${savedFile.name}_title", finalTitle)
                    .putString("${savedFile.name}_category", category)
                    .putString("${savedFile.name}_note", note)
                    .putString("${savedFile.name}_tags", tags)
                    .commit()

                if (!metadataSaved) {
                    throw Exception("Memory details could not be saved.")
                }

                try {
                    val cloudMetadata = mutableMapOf<String, Any?>(
                        "title" to finalTitle,
                        "category" to category,
                        "note" to note,
                        "tags" to tags,
                        "alsoSave" to alsoSave
                    )

                    val syncManager = RecallSyncManager(applicationContext)
                    val queued = syncManager.queueSave(
                        memoryId = savedFile.name,
                        metadata = cloudMetadata
                    )

                    if (queued) {
                        RecallSyncWorker.enqueue(applicationContext)
                    }
                } catch (syncError: Exception) {
                    android.util.Log.e(
                        "RecallSync",
                        "Metadata sync could not be queued. Local image is safe.",
                        syncError
                    )
                }

                previewFile.delete()
                captureImagePath?.let { File(it).delete() }
                croppedPreviewPath = null
                captureImagePath = null
                requestedTab = "Library"

                val finalMessage = if (alsoSave) {
                    "Saved and sent to Library on this device!"
                } else {
                    "Sent to Library on this device!"
                }

                Toast.makeText(
                    this@MainActivity,
                    finalMessage,
                    Toast.LENGTH_LONG
                ).show()

                moveTaskToBack(true)

            } catch (e: Exception) {
                android.util.Log.e("RecallSave", "Unable to save memory", e)

                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Unable to save memory")
                    .setMessage(
                        "${e.message ?: "Unknown error"}\n\nPreview: $previewPath"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {

        const val ACTION_REQUEST_SCREEN_CAPTURE =
            "com.recall.app.REQUEST_SCREEN_CAPTURE"

        const val ACTION_OPEN_LIBRARY =
            "com.recall.app.ACTION_OPEN_LIBRARY"

        const val ACTION_OPEN_SEARCH =
            "com.recall.app.ACTION_OPEN_SEARCH"
    }
}

private enum class CropDragMode {
    NEW,
    MOVE,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

@Composable
fun CaptureCropScreen(
    imagePath: String,
    onCancel: () -> Unit,
    onSave: (Rect) -> Unit
) {

    val context = LocalContext.current

    val bitmap = remember(imagePath) {
        BitmapFactory.decodeFile(imagePath)
    }

    DisposableEffect(bitmap) {
        onDispose {
            bitmap?.recycle()
        }
    }

    var cropRect by remember(imagePath) {
        mutableStateOf(Rect(0.05f, 0.05f, 0.95f, 0.95f))
    }

    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var initialRect by remember { mutableStateOf(cropRect) }
    var dragMode by remember { mutableStateOf(CropDragMode.NEW) }

    BackHandler {
        onCancel()
    }

    if (bitmap == null) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                "Unable to load screenshot.",
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onCancel) {
                Text("Go Back")
            }
        }

        return
    }

    val imageBitmap = remember(bitmap) {
        bitmap.asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {

        Text(
            text = "Crop Screenshot",
            color = Purple,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Drag the corners or edges to select the area.",
            color = SecondaryText,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(imageBitmap) {

                    val scale = min(
                        size.width.toFloat() / bitmap.width,
                        size.height.toFloat() / bitmap.height
                    )

                    val imageWidth = bitmap.width * scale
                    val imageHeight = bitmap.height * scale

                    val offsetX = (size.width - imageWidth) / 2f
                    val offsetY = (size.height - imageHeight) / 2f

                    val edgeTolerance = 16.dp.toPx()
                    val minimumSize = 0.015f

                    fun getPosition(position: Offset): Offset {
                        return Offset(
                            ((position.x - offsetX) / imageWidth)
                                .coerceIn(0f, 1f),
                            ((position.y - offsetY) / imageHeight)
                                .coerceIn(0f, 1f)
                        )
                    }

                    fun getDragMode(position: Offset): CropDragMode {

                        val x = position.x
                        val y = position.y

                        val nearLeft =
                            abs(x - cropRect.left) * imageWidth <= edgeTolerance

                        val nearRight =
                            abs(x - cropRect.right) * imageWidth <= edgeTolerance

                        val nearTop =
                            abs(y - cropRect.top) * imageHeight <= edgeTolerance

                        val nearBottom =
                            abs(y - cropRect.bottom) * imageHeight <= edgeTolerance

                        val inside =
                            x >= cropRect.left &&
                                    x <= cropRect.right &&
                                    y >= cropRect.top &&
                                    y <= cropRect.bottom

                        if (!inside) return CropDragMode.NEW

                        if (nearLeft && nearTop) return CropDragMode.TOP_LEFT
                        if (nearRight && nearTop) return CropDragMode.TOP_RIGHT
                        if (nearLeft && nearBottom) return CropDragMode.BOTTOM_LEFT
                        if (nearRight && nearBottom) return CropDragMode.BOTTOM_RIGHT

                        if (nearLeft) return CropDragMode.LEFT
                        if (nearRight) return CropDragMode.RIGHT
                        if (nearTop) return CropDragMode.TOP
                        if (nearBottom) return CropDragMode.BOTTOM

                        return CropDragMode.MOVE
                    }

                    detectDragGestures(
                        onDragStart = { start ->

                            val position = getPosition(start)

                            dragStart = position
                            initialRect = cropRect
                            dragMode = getDragMode(position)

                            if (dragMode == CropDragMode.NEW) {
                                cropRect = Rect(
                                    position.x,
                                    position.y,
                                    position.x,
                                    position.y
                                )
                            }
                        },

                        onDrag = { change, _ ->

                            val current = getPosition(change.position)

                            val dx = current.x - dragStart.x
                            val dy = current.y - dragStart.y

                            cropRect = when (dragMode) {

                                CropDragMode.NEW -> Rect(
                                    min(dragStart.x, current.x),
                                    min(dragStart.y, current.y),
                                    max(dragStart.x, current.x),
                                    max(dragStart.y, current.y)
                                )

                                CropDragMode.MOVE -> {

                                    val width = initialRect.width
                                    val height = initialRect.height

                                    val newLeft =
                                        (initialRect.left + dx)
                                            .coerceIn(0f, 1f - width)

                                    val newTop =
                                        (initialRect.top + dy)
                                            .coerceIn(0f, 1f - height)

                                    Rect(
                                        newLeft,
                                        newTop,
                                        newLeft + width,
                                        newTop + height
                                    )
                                }

                                CropDragMode.LEFT -> Rect(
                                    (initialRect.left + dx)
                                        .coerceIn(
                                            0f,
                                            initialRect.right - minimumSize
                                        ),
                                    initialRect.top,
                                    initialRect.right,
                                    initialRect.bottom
                                )

                                CropDragMode.RIGHT -> Rect(
                                    initialRect.left,
                                    initialRect.top,
                                    (initialRect.right + dx)
                                        .coerceIn(
                                            initialRect.left + minimumSize,
                                            1f
                                        ),
                                    initialRect.bottom
                                )

                                CropDragMode.TOP -> Rect(
                                    initialRect.left,
                                    (initialRect.top + dy)
                                        .coerceIn(
                                            0f,
                                            initialRect.bottom - minimumSize
                                        ),
                                    initialRect.right,
                                    initialRect.bottom
                                )

                                CropDragMode.BOTTOM -> Rect(
                                    initialRect.left,
                                    initialRect.top,
                                    initialRect.right,
                                    (initialRect.bottom + dy)
                                        .coerceIn(
                                            initialRect.top + minimumSize,
                                            1f
                                        )
                                )

                                CropDragMode.TOP_LEFT -> Rect(
                                    (initialRect.left + dx)
                                        .coerceIn(
                                            0f,
                                            initialRect.right - minimumSize
                                        ),
                                    (initialRect.top + dy)
                                        .coerceIn(
                                            0f,
                                            initialRect.bottom - minimumSize
                                        ),
                                    initialRect.right,
                                    initialRect.bottom
                                )

                                CropDragMode.TOP_RIGHT -> Rect(
                                    initialRect.left,
                                    (initialRect.top + dy)
                                        .coerceIn(
                                            0f,
                                            initialRect.bottom - minimumSize
                                        ),
                                    (initialRect.right + dx)
                                        .coerceIn(
                                            initialRect.left + minimumSize,
                                            1f
                                        ),
                                    initialRect.bottom
                                )

                                CropDragMode.BOTTOM_LEFT -> Rect(
                                    (initialRect.left + dx)
                                        .coerceIn(
                                            0f,
                                            initialRect.right - minimumSize
                                        ),
                                    initialRect.top,
                                    initialRect.right,
                                    (initialRect.bottom + dy)
                                        .coerceIn(
                                            initialRect.top + minimumSize,
                                            1f
                                        )
                                )

                                CropDragMode.BOTTOM_RIGHT -> Rect(
                                    initialRect.left,
                                    initialRect.top,
                                    (initialRect.right + dx)
                                        .coerceIn(
                                            initialRect.left + minimumSize,
                                            1f
                                        ),
                                    (initialRect.bottom + dy)
                                        .coerceIn(
                                            initialRect.top + minimumSize,
                                            1f
                                        )
                                )
                            }

                            change.consume()
                        }
                    )
                }
        ) {

            val scale = min(
                size.width / bitmap.width.toFloat(),
                size.height / bitmap.height.toFloat()
            )

            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale

            val offsetX = (size.width - imageWidth) / 2f
            val offsetY = (size.height - imageHeight) / 2f

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(
                    offsetX.roundToInt(),
                    offsetY.roundToInt()
                ),
                dstSize = IntSize(
                    imageWidth.roundToInt(),
                    imageHeight.roundToInt()
                )
            )

            val left = offsetX + cropRect.left * imageWidth
            val top = offsetY + cropRect.top * imageHeight
            val right = offsetX + cropRect.right * imageWidth
            val bottom = offsetY + cropRect.bottom * imageHeight

            val shade = Color.Black.copy(alpha = 0.55f)

            drawRect(
                shade,
                Offset(offsetX, offsetY),
                androidx.compose.ui.geometry.Size(
                    imageWidth,
                    top - offsetY
                )
            )

            drawRect(
                shade,
                Offset(offsetX, bottom),
                androidx.compose.ui.geometry.Size(
                    imageWidth,
                    offsetY + imageHeight - bottom
                )
            )

            drawRect(
                shade,
                Offset(offsetX, top),
                androidx.compose.ui.geometry.Size(
                    left - offsetX,
                    bottom - top
                )
            )

            drawRect(
                shade,
                Offset(right, top),
                androidx.compose.ui.geometry.Size(
                    offsetX + imageWidth - right,
                    bottom - top
                )
            )

            drawRect(
                color = Purple,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(
                    right - left,
                    bottom - top
                ),
                style = Stroke(width = 3.dp.toPx())
            )

            val handleRadius = 6.dp.toPx()

            val handles = listOf(
                Offset(left, top),
                Offset((left + right) / 2f, top),
                Offset(right, top),
                Offset(left, (top + bottom) / 2f),
                Offset(right, (top + bottom) / 2f),
                Offset(left, bottom),
                Offset((left + right) / 2f, bottom),
                Offset(right, bottom)
            )

            handles.forEach { point ->

                drawCircle(
                    color = Color.White,
                    radius = handleRadius,
                    center = point
                )

                drawCircle(
                    color = Purple,
                    radius = handleRadius,
                    center = point,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = Purple)
            }

            Button(
                onClick = {

                    if (cropRect.width > 0.02f &&
                        cropRect.height > 0.02f
                    ) {
                        onSave(cropRect)
                    } else {
                        Toast.makeText(
                            context,
                            "Select a larger crop area.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue", color = Background)
            }
        }
    }
}

@Composable
private fun CaptureChoiceScreen(
    imagePath: String,
    onSendToLibrary: () -> Unit,
    onSaveAndSendToLibrary: () -> Unit,
    onCancel: () -> Unit
) {
    val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }

    BackHandler { onCancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "What would you like to do?",
            color = Purple,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Choose where this cropped screenshot should go.",
            color = SecondaryText,
            fontSize = 14.sp
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(18.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Cropped screenshot preview",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("Preview unavailable", color = SecondaryText)
                }
            }
        }
        Button(
            onClick = onSendToLibrary,
            enabled = bitmap != null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Send to Library", color = Background, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onSaveAndSendToLibrary,
            enabled = bitmap != null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save and send to Library", color = Purple, fontWeight = FontWeight.Bold)
        }
        Text(
            "Send to Library keeps it in your Library only. Save and send also adds it to Saved.",
            color = SecondaryText,
            fontSize = 12.sp
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel — crop again", color = Purple)
        }
    }
}

@Composable
private fun CaptureDetailsScreen(
    imagePath: String,
    onCancel: () -> Unit,
    onSave: (String, String, String, String, Boolean) -> Unit
) {
    val bitmap = remember(imagePath) {
        BitmapFactory.decodeFile(imagePath)
    }

    DisposableEffect(bitmap) {
        onDispose {
            bitmap?.recycle()
        }
    }

    var title by remember(imagePath) { mutableStateOf("") }
    var category by remember(imagePath) { mutableStateOf("Other") }
    var note by remember(imagePath) { mutableStateOf("") }
    var tags by remember(imagePath) { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }

    BackHandler {
        onCancel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Save Image",
            color = Purple,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Add a description and details to remember this image.",
            color = SecondaryText,
            fontSize = 14.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = CardColor
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Image to save",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Image unavailable",
                        color = SecondaryText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Description",
            color = Purple,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Title",
            color = Color.White,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Give this image a title")
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Purple
            )
        )

        Text(
            "Category",
            color = Color.White,
            fontWeight = FontWeight.Medium
        )

        Box {
            OutlinedButton(
                onClick = {
                    categoryExpanded = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    category,
                    color = Color.White
                )
            }

            DropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = {
                    categoryExpanded = false
                }
            ) {
                captureCategories.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(item)
                        },
                        onClick = {
                            category = item
                            categoryExpanded = false
                        }
                    )
                }
            }
        }

        Text(
            "Notes",
            color = Color.White,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("What do you want to remember?")
            },
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Purple
            )
        )

        Text(
            "Tags",
            color = Color.White,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Example: shoes, ideas, project")
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Purple
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                onSave(
                    title.trim(),
                    category,
                    note.trim(),
                    tags.trim(),
                    false
                )
            },
            enabled = bitmap != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Purple
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                "Send to Library",
                color = Background,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = {
                onSave(
                    title.trim(),
                    category,
                    note.trim(),
                    tags.trim(),
                    true
                )
            },
            enabled = bitmap != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                "Save and send to Library",
                color = Purple,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Cancel - crop again",
                color = Purple
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun RecallHomeScreen(initialTab: String = "Home") {

    val context = LocalContext.current

    var selectedCategory by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(initialTab) }

    var gestureEnabled by remember {
        mutableStateOf(GestureSettings.isTripleTapEnabled(context))
    }

    var sensitivity by remember {
        mutableStateOf(GestureSettings.getSensitivity(context))
    }

    // Kept for the triple-tap popup. The Home button has been removed.
    var showQuickCapture by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    val onboardingPreferences = remember(context) {
        context.getSharedPreferences(
            "recall_onboarding",
            Context.MODE_PRIVATE
        )
    }

    var showOnboarding by remember {
        mutableStateOf(
            !onboardingPreferences.getBoolean(
                "instructions_shown",
                false
            )
        )
    }

    var selectedGalleryFile by remember {
        mutableStateOf<File?>(null)
    }

    BackHandler {
        when {
            showOnboarding -> {
                // The user can choose Skip or Enable on the onboarding page.
            }

            showQuickCapture -> showQuickCapture = false
            showExitDialog -> showExitDialog = false
            selectedGalleryFile != null -> selectedGalleryFile = null
            selectedTab != "Home" -> selectedTab = "Home"
            else -> showExitDialog = true
        }
    }

    LaunchedEffect(initialTab) {
        if (
            initialTab in listOf(
                "Home",
                "Saved",
                "Library",
                "Timeline",
                "Settings"
            )
        ) {
            selectedTab = initialTab
        }
    }

    DisposableEffect(context) {

        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (intent?.action == "com.recall.app.TRIPLE_TAP") {
                    showQuickCapture = true
                }
            }
        }

        val filter = IntentFilter("com.recall.app.TRIPLE_TAP")

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    val categories = listOf(
        "Products",
        "Articles",
        "Places",
        "Study",
        "Food",
        "Clothing & Accessories",
        "Design & Inspiration",
        "Other"
    )

    fun activateGestureService() {

        val hasOverlayPermission = Settings.canDrawOverlays(context)

        val hasNotificationPermission =
            Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

        if (!hasOverlayPermission) {
            gestureEnabled = false

            Toast.makeText(
                context,
                "Overlay permission is required.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (!hasNotificationPermission) {
            gestureEnabled = false

            Toast.makeText(
                context,
                "Notification permission is required.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

            GestureSettings.setTripleTapEnabled(context, true)

            val serviceIntent = Intent(
                context,
                GestureDetectionService::class.java
            )

            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )

            gestureEnabled = true

            Toast.makeText(
                context,
                "Gesture detection started.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            gestureEnabled = false
            GestureSettings.setTripleTapEnabled(context, false)

            android.util.Log.e(
                "RecallGesture",
                "Could not start gesture detection.",
                e
            )

            Toast.makeText(
                context,
                "Could not start gesture detection: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                activateGestureService()
            } else {
                Toast.makeText(
                    context,
                    "Notification permission is required for gesture detection.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            if (Settings.canDrawOverlays(context)) {

                if (
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    activateGestureService()
                }

            } else {
                Toast.makeText(
                    context,
                    "Overlay permission is required for Recall popup.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    fun requestGestureEnable() {

        if (!Settings.canDrawOverlays(context)) {

            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

            overlayPermissionLauncher.launch(overlayIntent)
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
            return
        }

        activateGestureService()
    }

    fun changeGestureEnabled(enabled: Boolean) {

        if (enabled) {
            requestGestureEnable()
        } else {

            GestureSettings.setTripleTapEnabled(context, false)

            context.stopService(
                Intent(context, GestureDetectionService::class.java)
            )

            gestureEnabled = false

            Toast.makeText(
                context,
                "Triple-tap detection turned off.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        containerColor = Background,

        bottomBar = {

            if (!showOnboarding) {

                NavigationBar(
                    containerColor = CardColor
                ) {

                    listOf(
                        "Home",
                        "Saved",
                        "Library",
                        "Timeline",
                        "Settings"
                    ).forEach { tab ->

                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                            },
                            icon = {
                                // All five tabs now use custom outline icons.
                                RecallNavigationIcon(tab)
                            },
                            label = {
                                Text(tab)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Purple,
                                selectedTextColor = Purple,
                                unselectedIconColor = SecondaryText,
                                unselectedTextColor = SecondaryText,
                                indicatorColor = Color(0xFF393047)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->

        if (showOnboarding) {

            RecallOnboardingScreen(
                modifier = Modifier.padding(padding),

                onEnableGesture = {

                    onboardingPreferences.edit()
                        .putBoolean("instructions_shown", true)
                        .apply()

                    showOnboarding = false

                    requestGestureEnable()
                },

                onSkip = {

                    onboardingPreferences.edit()
                        .putBoolean("instructions_shown", true)
                        .apply()

                    showOnboarding = false
                }
            )

        } else {

            when (selectedTab) {

                "Saved" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        RecallAiSearchScreen()
                    }
                }

                "Library" -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        RecallLibraryScreen()
                    }
                }

                "Timeline" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        RecallTimelineScreen()
                    }
                }

                "Settings" -> {

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        SettingsScreen(
                            tripleTapEnabled = gestureEnabled,
                            sensitivity = sensitivity,

                            onTripleTapChanged = { enabled ->
                                changeGestureEnabled(enabled)
                            },

                            onSensitivityChanged = { newValue ->
                                sensitivity = newValue

                                GestureSettings.setSensitivity(
                                    context,
                                    newValue
                                )
                            }
                        )
                    }
                }

                else -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {

                        Text(
                            text = "Recall",
                            color = Purple,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Remember anything. Anytime.",
                            color = SecondaryText,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = CardColor
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {

                            Column(
                                modifier = Modifier.padding(18.dp)
                            ) {

                                Text(
                                    text = "Gesture Detection",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = if (gestureEnabled) {
                                        "Triple tap the back of your screen to initiate the Recall popup. Gesture detection is enabled. You can switch it off anytime in Settings."
                                    } else {
                                        "Triple tap the back of your screen to initiate the Recall popup. Enable gesture detection to use this feature. You can switch it off whenever you like."
                                    },
                                    color = SecondaryText
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        requestGestureEnable()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Purple
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {

                                    Text(
                                        text = if (gestureEnabled) {
                                            "Restart Gesture Detection"
                                        } else {
                                            "Enable Gesture Detection"
                                        },
                                        color = Background
                                    )
                                }
                            }
                        }

                        // Quick Capture button removed.

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Explore Categories",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            categories.chunked(2).forEach { rowCategories ->

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {

                                    rowCategories.forEach { category ->

                                        CategoryCard(
                                            category = category,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                selectedCategory = category
                                            }
                                        )
                                    }

                                    if (rowCategories.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (selectedCategory.isNotEmpty()) {

                            Spacer(modifier = Modifier.height(12.dp))

                            HomeCategoryGallery(
                                category = selectedCategory,

                                onOpenCapture = { file ->
                                    selectedGalleryFile = file
                                },

                                onOpenLibrary = {
                                    selectedTab = "Library"
                                }
                            )
                        }

                        // Recent Captures section intentionally removed.

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showExitDialog) {

        AlertDialog(
            onDismissRequest = {
                showExitDialog = false
            },
            containerColor = CardColor,
            title = {
                Text(
                    "Exit Recall?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you sure you want to close Recall?",
                    color = SecondaryText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple
                    )
                ) {
                    Text("Exit", color = Background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Stay", color = Purple)
                }
            }
        )
    }

    // Triple-tap still opens the Quick Capture popup.
    if (showQuickCapture) {

        QuickCaptureDialog(
            onDismiss = {
                showQuickCapture = false
            },

            onSave = { _, _ ->
                showQuickCapture = false
            }
        )
    }

    selectedGalleryFile?.let { file ->

        RecallCategoryMemoryDialog(
            file = file,
            onDismiss = {
                selectedGalleryFile = null
            }
        )
    }
}

@Composable
private fun RecallOnboardingScreen(
    modifier: Modifier = Modifier,
    onEnableGesture: () -> Unit,
    onSkip: () -> Unit
) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(Modifier.height(35.dp))

        Text(
            "Welcome to Recall",
            color = Purple,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Remember anything. Anytime.",
            color = SecondaryText,
            fontSize = 15.sp
        )

        Spacer(Modifier.height(30.dp))

        val instructions = listOf(
            "1. Quick Capture" to
                    "Capture something from your screen, crop the part you want, and save it to your Library.",

            "2. Your Library" to
                    "Your saved images and memories are organised here. Open an image to view its details, notes and recognised text.",

            "3. Categories" to
                    "Explore your memories through categories such as Shopping, Places, Food, Study and Articles.",

            "4. Search" to
                    "Find saved memories using titles, notes, categories or text recognised from images.",

            "5. Triple-tap gesture" to
                    "Triple tap the back of your screen to initiate the Recall popup. You can switch gesture detection off whenever you like.",

            "6. Sensitivity" to
                    "Adjust gesture sensitivity in Settings. Your chosen setting is saved automatically."
        )

        instructions.forEach { (heading, description) ->

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = CardColor
                ),
                shape = RoundedCornerShape(16.dp)
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        heading,
                        color = Purple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        description,
                        color = SecondaryText,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Enable triple-tap gesture detection?",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "Recall needs permission to display its popup over other apps and, on supported Android versions, to show its detection notification. You can turn this feature off or change it later in Settings.",
            color = SecondaryText,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onEnableGesture,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Purple
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                "Enable Triple Tap",
                color = Background,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Not now — I'll enable it later",
                color = Purple
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}


@Composable
private fun RecallNavigationIcon(tab: String) {

    val iconColor = androidx.compose.material3.LocalContentColor.current

    if (tab == "Settings") {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        return
    }

    Canvas(
        modifier = Modifier.size(24.dp)
    ) {

        val scale = size.minDimension / 24f
        val strokeWidth = 1.7.dp.toPx()

        val outline = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        when (tab) {

            "Home" -> {

                val path = Path().apply {
                    moveTo(3f * scale, 11f * scale)
                    lineTo(12f * scale, 3.5f * scale)
                    lineTo(21f * scale, 11f * scale)

                    moveTo(5.5f * scale, 9.5f * scale)
                    lineTo(5.5f * scale, 20f * scale)
                    lineTo(18.5f * scale, 20f * scale)
                    lineTo(18.5f * scale, 9.5f * scale)

                    moveTo(10f * scale, 20f * scale)
                    lineTo(10f * scale, 14f * scale)
                    quadraticTo(
                        10f * scale, 13f * scale,
                        11f * scale, 13f * scale
                    )
                    lineTo(13f * scale, 13f * scale)
                    quadraticTo(
                        14f * scale, 13f * scale,
                        14f * scale, 14f * scale
                    )
                    lineTo(14f * scale, 20f * scale)
                }

                drawPath(path, iconColor, style = outline)
            }

            "Saved" -> {

                val path = Path().apply {
                    moveTo(7f * scale, 3f * scale)
                    lineTo(17f * scale, 3f * scale)
                    quadraticTo(
                        19f * scale, 3f * scale,
                        19f * scale, 5f * scale
                    )
                    lineTo(19f * scale, 21f * scale)
                    lineTo(12f * scale, 16f * scale)
                    lineTo(5f * scale, 21f * scale)
                    lineTo(5f * scale, 5f * scale)
                    quadraticTo(
                        5f * scale, 3f * scale,
                        7f * scale, 3f * scale
                    )
                    close()
                }

                drawPath(path, iconColor, style = outline)
            }

            "Library" -> {

                val path = Path().apply {
                    moveTo(3f * scale, 7f * scale)
                    lineTo(9f * scale, 7f * scale)
                    quadraticTo(
                        10f * scale, 7f * scale,
                        11f * scale, 8f * scale
                    )
                    lineTo(12f * scale, 9f * scale)
                    lineTo(19f * scale, 9f * scale)
                    quadraticTo(
                        21f * scale, 9f * scale,
                        21f * scale, 11f * scale
                    )
                    lineTo(21f * scale, 18f * scale)
                    quadraticTo(
                        21f * scale, 21f * scale,
                        18f * scale, 21f * scale
                    )
                    lineTo(6f * scale, 21f * scale)
                    quadraticTo(
                        3f * scale, 21f * scale,
                        3f * scale, 18f * scale
                    )
                    close()
                }

                drawPath(path, iconColor, style = outline)
            }

            "Timeline" -> {

                drawCircle(
                    color = iconColor,
                    radius = 9f * scale,
                    center = Offset(12f * scale, 12f * scale),
                    style = outline
                )

                drawLine(
                    color = iconColor,
                    start = Offset(12f * scale, 7f * scale),
                    end = Offset(12f * scale, 12f * scale),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )

                drawLine(
                    color = iconColor,
                    start = Offset(12f * scale, 12f * scale),
                    end = Offset(16f * scale, 14.5f * scale),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun HomeCategoryGallery(
    category: String,
    onOpenCapture: (File) -> Unit,
    onOpenLibrary: () -> Unit
) {

    val context = LocalContext.current

    // Support older category names while keeping current
    // saved category names unchanged.
    val matchingCategories = when (category) {

        "Products" -> setOf(
            "Products",
            "Shopping"
        )

        "Clothing & Accessories" -> setOf(
            "Clothing & Accessories",
            "Shopping"
        )

        "Design & Inspiration" -> setOf(
            "Design & Inspiration",
            "Designs"
        )

        else -> setOf(category)
    }

    val captures = remember(category) {

        val preferences = context.getSharedPreferences(
            "recall_library",
            Context.MODE_PRIVATE
        )

        // Both folders are part of the Recall Library.
        val libraryFolders = listOf(
            File(context.filesDir, "recall_captures"),
            File(context.filesDir, "recall_library_only")
        )

        val supportedExtensions = setOf(
            "png",
            "jpg",
            "jpeg",
            "webp",
            "gif",
            "bmp",
            "heic",
            "heif"
        )

        libraryFolders
            .flatMap { folder ->
                folder.listFiles()?.toList() ?: emptyList()
            }
            .filter { file ->

                file.isFile &&
                        file.extension.lowercase() in supportedExtensions &&
                        (
                                preferences.getString(
                                    "${file.name}_category",
                                    "Other"
                                ) ?: "Other"
                                ) in matchingCategories
            }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        ),
        shape = RoundedCornerShape(14.dp)
    ) {

        Column(
            modifier = Modifier.padding(14.dp)
        ) {

            Text(
                "$category memories",
                color = Purple,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (captures.isEmpty()) {

                Text(
                    "No images in this category yet.",
                    color = SecondaryText,
                    fontSize = 13.sp
                )

            } else {

                // Display ALL matching images, not just the first six.
                captures.chunked(2).forEach { row ->

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        row.forEach { file ->

                            val bitmap = remember(
                                file.absolutePath,
                                file.lastModified()
                            ) {

                                val bounds = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }

                                BitmapFactory.decodeFile(
                                    file.absolutePath,
                                    bounds
                                )

                                var sample = 1

                                while (
                                    bounds.outWidth / sample > 360 ||
                                    bounds.outHeight / sample > 360
                                ) {
                                    sample *= 2
                                }

                                BitmapFactory.decodeFile(
                                    file.absolutePath,
                                    BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                        inPreferredConfig = Bitmap.Config.RGB_565
                                    }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onOpenCapture(file)
                                    }
                            ) {

                                if (bitmap != null) {

                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = file.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(105.dp),
                                        contentScale = ContentScale.Crop
                                    )

                                } else {

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(105.dp)
                                            .background(CardColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Preview unavailable",
                                            color = SecondaryText,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Text(
                                    context.getSharedPreferences(
                                        "recall_library",
                                        Context.MODE_PRIVATE
                                    ).getString(
                                        "${file.name}_title",
                                        file.nameWithoutExtension
                                    ) ?: file.nameWithoutExtension,
                                    color = Color.White,
                                    maxLines = 1,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                TextButton(
                    onClick = onOpenLibrary
                ) {
                    Text(
                        "Open Library",
                        color = Purple
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryCard(
    category: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    Card(
        modifier = modifier
            .height(90.dp)
            .clickable {
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        ),
        shape = RoundedCornerShape(16.dp)
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {

            Text(
                text = category,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
fun QuickCaptureDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {

    var selectedCategory by remember {
        mutableStateOf("Products")
    }

    var note by remember {
        mutableStateOf("")
    }

    var expanded by remember {
        mutableStateOf(false)
    }

    val categories = listOf(
        "Products",
        "Articles",
        "Places",
        "Study",
        "Food",
        "Clothing & Accessories",
        "Design & Inspiration",
        "Other"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,

        title = {
            Text(
                text = "Quick Capture",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },

        text = {

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "Save something for later.",
                    color = SecondaryText
                )

                Text(
                    text = "Choose a category",
                    color = Color.White
                )

                Box {

                    OutlinedButton(
                        onClick = {
                            expanded = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text(
                            text = selectedCategory,
                            color = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {

                        categories.forEach { category ->

                            DropdownMenuItem(
                                text = {
                                    Text(category)
                                },
                                onClick = {
                                    selectedCategory = category
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "Add a note",
                    color = Color.White
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("What do you want to remember?")
                    },
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple,
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Purple
                    )
                )
            }
        },

        confirmButton = {

            Button(
                onClick = {
                    if (note.isNotBlank()) {
                        onSave(selectedCategory, note)
                    }
                },
                enabled = note.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple
                )
            ) {

                Text(
                    text = "Save",
                    color = Background
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text(
                    text = "Cancel",
                    color = Purple
                )
            }
        }
    )
}
