package com.recall.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryBackground = Color(0xFF100F17)
private val LibraryCard = Color(0xFF201E2B)
private val LibraryPurple = Color(0xFFB69CFF)
private val LibraryText = Color(0xFFAAA6B8)
private val LibraryFieldBackground = Color(0xFF292438)
private val LibraryFieldBorder = Color(0xFF75639F)

private val libraryCategories = listOf(
    "Shopping",
    "Places",
    "Food",
    "Study",
    "Designs",
    "Articles",
    "Other"
)

private data class LibraryCapture(
    val file: File,
    val title: String,
    val category: String,
    val note: String,
    val tags: String,
    val recognizedText: String = "",
    val labels: String = "",
    val description: String = ""
)

private fun libraryPreferences(context: Context) =
    context.getSharedPreferences(
        "recall_library",
        Context.MODE_PRIVATE
    )

private fun decodeLibraryImage(
    file: File,
    maxSize: Int
): Bitmap? {

    if (!file.exists()) return null

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeFile(file.absolutePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sample = 1

    while (
        bounds.outWidth / sample > maxSize ||
        bounds.outHeight / sample > maxSize
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return BitmapFactory.decodeFile(
        file.absolutePath,
        options
    )
}

private fun loadLibraryCaptures(
    context: Context
): List<LibraryCapture> {

    val savedFolder = File(
        context.filesDir,
        "recall_captures"
    )

    val libraryOnlyFolder = File(
        context.filesDir,
        "recall_library_only"
    )

    if (!savedFolder.exists()) savedFolder.mkdirs()
    if (!libraryOnlyFolder.exists()) libraryOnlyFolder.mkdirs()

    val preferences = libraryPreferences(context)

    val indexedMemories = RecallAiIndexer
        .load(context, includeExcluded = true)
        .associateBy { it.fileName }

    val allFiles = (
            savedFolder.listFiles()?.toList().orEmpty() +
                    libraryOnlyFolder.listFiles()?.toList().orEmpty()
            )
        .filter {
            it.isFile && it.extension.equals("png", true)
        }
        .distinctBy {
            it.absolutePath
        }
        .sortedByDescending {
            it.lastModified()
        }

    return allFiles.map { file ->

        val memory = indexedMemories[file.name]

        LibraryCapture(
            file = file,

            title = preferences.getString(
                "${file.name}_title",
                memory?.title ?: file.nameWithoutExtension
            ) ?: file.nameWithoutExtension,

            category = preferences.getString(
                "${file.name}_category",
                memory?.category ?: "Other"
            ) ?: "Other",

            note = preferences.getString(
                "${file.name}_note",
                memory?.note ?: ""
            ) ?: "",

            tags = preferences.getString(
                "${file.name}_tags",
                memory?.tags ?: ""
            ) ?: "",

            recognizedText = memory?.recognizedText ?: "",

            labels = memory?.labels?.joinToString(" ") ?: "",

            description = memory?.description ?: ""
        )
    }
}

/*
 * Opens a category image using the same details dialog
 * that is used by images inside the main Library.
 */
@Composable
fun RecallCategoryMemoryDialog(
    file: File,
    onDismiss: () -> Unit
) {

    val context = LocalContext.current

    val capture = remember(
        file.absolutePath,
        file.lastModified()
    ) {
        loadLibraryCaptures(context).firstOrNull {
            it.file.absolutePath == file.absolutePath
        }
    }

    if (capture == null) {

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = LibraryCard,

            title = {
                Text(
                    "Memory unavailable",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This image could not be found in your Library.",
                    color = LibraryText
                )
            },

            confirmButton = {
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(
                        "Close",
                        color = LibraryPurple
                    )
                }
            }
        )

        return
    }

    LibraryDetailsDialog(
        capture = capture,

        onDismiss = onDismiss,

        onSave = { title, category, note, tags ->

            libraryPreferences(context).edit()
                .putString("${file.name}_title", title)
                .putString("${file.name}_category", category)
                .putString("${file.name}_note", note)
                .putString("${file.name}_tags", tags)
                .apply()

            RecallAiIndexer.updateMemoryMetadata(
                context,
                file.name,
                title,
                category,
                note,
                tags
            )

            Toast.makeText(
                context,
                "Memory updated",
                Toast.LENGTH_SHORT
            ).show()

            onDismiss()
        },

        onDelete = {

            val deleted = !file.exists() || file.delete()

            if (deleted) {

                libraryPreferences(context).edit()
                    .remove("${file.name}_title")
                    .remove("${file.name}_category")
                    .remove("${file.name}_note")
                    .remove("${file.name}_tags")
                    .remove("${file.name}_updatedAtMillis")
                    .apply()

                RecallAiIndexer.removeMemory(
                    context,
                    file.name
                )

                RecallBackupStatus.clearStatus(
                    context,
                    file.name
                )

                Toast.makeText(
                    context,
                    "Deleted permanently",
                    Toast.LENGTH_SHORT
                ).show()

                onDismiss()

            } else {

                Toast.makeText(
                    context,
                    "Could not delete this image",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )
}

@Composable
fun RecallLibraryScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var captures by remember {
        mutableStateOf(loadLibraryCaptures(context))
    }

    var searchText by remember {
        mutableStateOf("")
    }

    var selectedCategory by remember {
        mutableStateOf("All")
    }

    var selectedCapture by remember {
        mutableStateOf<LibraryCapture?>(null)
    }

    var selectionMode by remember {
        mutableStateOf(false)
    }

    var selectedFiles by remember {
        mutableStateOf(setOf<String>())
    }

    var showBulkDeleteConfirmation by remember {
        mutableStateOf(false)
    }

    var isBackingUp by remember {
        mutableStateOf(false)
    }

    var backupRefresh by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(Unit) {
        captures = loadLibraryCaptures(context)
    }

    val filteredCaptures = captures.filter { capture ->

        val query = searchText.trim()

        val matchesSearch =
            query.isBlank() ||
                    capture.title.contains(query, true) ||
                    capture.category.contains(query, true) ||
                    capture.note.contains(query, true) ||
                    capture.tags.contains(query, true) ||
                    capture.recognizedText.contains(query, true) ||
                    capture.labels.contains(query, true) ||
                    capture.description.contains(query, true)

        val matchesCategory =
            selectedCategory == "All" ||
                    capture.category == selectedCategory

        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {

        Text(
            text = "Your Library",
            color = LibraryPurple,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(5.dp))

        Text(
            text = "${captures.size} images in your Library",
            color = LibraryText,
            fontSize = 14.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            if (!selectionMode) {

                Button(
                    onClick = {
                        selectionMode = true
                        selectedFiles = emptySet()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LibraryPurple
                    )
                ) {
                    Text(
                        "Select images",
                        color = LibraryBackground,
                        fontWeight = FontWeight.Bold
                    )
                }

            } else {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LibraryCard
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, LibraryPurple)
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = "${selectedFiles.size} selected",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )

                            Spacer(Modifier.weight(1f))

                            TextButton(
                                onClick = {
                                    selectionMode = false
                                    selectedFiles = emptySet()
                                }
                            ) {
                                Text(
                                    "Cancel",
                                    color = LibraryPurple
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                            OutlinedButton(
                                onClick = {
                                    selectedFiles =
                                        if (
                                            filteredCaptures.isNotEmpty() &&
                                            filteredCaptures.all {
                                                it.file.name in selectedFiles
                                            }
                                        ) {
                                            emptySet()
                                        } else {
                                            filteredCaptures.map {
                                                it.file.name
                                            }.toSet()
                                        }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (
                                        filteredCaptures.isNotEmpty() &&
                                        filteredCaptures.all {
                                            it.file.name in selectedFiles
                                        }
                                    ) {
                                        "Deselect all"
                                    } else {
                                        "Select all"
                                    },
                                    color = LibraryPurple
                                )
                            }

                            Button(
                                onClick = {

                                    val destination =
                                        RecallBackupPreferences.getDestination(context)

                                    val isPhotos =
                                        destination == RecallBackupPreferences.PHOTOS

                                    val token: String
                                    val folderId: String?

                                    if (isPhotos) {

                                        if (!RecallPhotosSession.isConnected) {

                                            Toast.makeText(
                                                context,
                                                "Connect Google Photos in Settings first.",
                                                Toast.LENGTH_LONG
                                            ).show()

                                            return@Button
                                        }

                                        token =
                                            RecallPhotosSession.accessToken.orEmpty()

                                        if (token.isBlank()) {

                                            Toast.makeText(
                                                context,
                                                "Google Photos connection expired. Reconnect in Settings.",
                                                Toast.LENGTH_LONG
                                            ).show()

                                            return@Button
                                        }

                                        folderId = null

                                    } else {

                                        if (!RecallDriveSession.isConnected) {

                                            Toast.makeText(
                                                context,
                                                "Connect Google Drive in Settings first.",
                                                Toast.LENGTH_LONG
                                            ).show()

                                            return@Button
                                        }

                                        token =
                                            RecallDriveSession.accessToken.orEmpty()

                                        folderId =
                                            RecallDriveSession.folderId

                                        if (
                                            token.isBlank() ||
                                            folderId.isNullOrBlank()
                                        ) {

                                            Toast.makeText(
                                                context,
                                                "Google Drive connection is unavailable. Reconnect it in Settings.",
                                                Toast.LENGTH_LONG
                                            ).show()

                                            return@Button
                                        }
                                    }

                                    val filesToBackup = captures.filter {
                                        it.file.name in selectedFiles
                                    }

                                    scope.launch {

                                        isBackingUp = true

                                        var successful = 0
                                        var failed = 0

                                        for (capture in filesToBackup) {

                                            RecallBackupStatus.setStatus(
                                                context,
                                                capture.file.name,
                                                RecallBackupStatus.PENDING,
                                                destination
                                            )

                                            backupRefresh++

                                            try {

                                                if (isPhotos) {

                                                    withContext<String>(
                                                        Dispatchers.IO
                                                    ) {
                                                        RecallPhotosStorage.uploadImage(
                                                            capture.file,
                                                            token
                                                        )
                                                    }

                                                    RecallBackupStatus.setStatus(
                                                        context,
                                                        capture.file.name,
                                                        RecallBackupStatus.BACKED_UP,
                                                        RecallBackupPreferences.PHOTOS
                                                    )

                                                } else {

                                                    val uploadedFileId =
                                                        withContext(
                                                            Dispatchers.IO
                                                        ) {
                                                            RecallDriveStorage(
                                                                context
                                                            ).uploadImage(
                                                                capture.file,
                                                                token,
                                                                folderId!!
                                                            )
                                                        }

                                                    RecallBackupStatus.setDriveFileId(
                                                        context,
                                                        capture.file.name,
                                                        uploadedFileId
                                                    )
                                                }

                                                successful++

                                            } catch (e: Exception) {

                                                RecallBackupStatus.setStatus(
                                                    context,
                                                    capture.file.name,
                                                    RecallBackupStatus.FAILED,
                                                    destination
                                                )

                                                failed++
                                            }

                                            backupRefresh++
                                        }

                                        isBackingUp = false

                                        Toast.makeText(
                                            context,
                                            "Backup complete: $successful successful, $failed failed.",
                                            Toast.LENGTH_LONG
                                        ).show()

                                        selectedFiles = emptySet()
                                        selectionMode = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = selectedFiles.isNotEmpty() &&
                                        !isBackingUp,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LibraryPurple,
                                    disabledContainerColor = Color.DarkGray
                                )
                            ) {
                                Text(
                                    if (isBackingUp) {
                                        "Backing up..."
                                    } else {
                                        "Backup"
                                    },
                                    color = LibraryBackground
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showBulkDeleteConfirmation = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedFiles.isNotEmpty() &&
                                    !isBackingUp,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                Color(0xFFFF7777)
                            )
                        ) {
                            Text(
                                "Delete permanently (${selectedFiles.size})",
                                color = Color(0xFFFF7777)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Search titles, notes or text in images...",
                    color = LibraryText
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = libraryFieldColors()
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            val filters = listOf("All") + libraryCategories

            filters.forEach { category ->

                FilterChip(
                    selected = selectedCategory == category,
                    onClick = {
                        selectedCategory = category
                    },
                    label = {
                        Text(category)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LibraryPurple,
                        selectedLabelColor = LibraryBackground,
                        labelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (filteredCaptures.isEmpty()) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = LibraryCard
                ),
                shape = RoundedCornerShape(18.dp)
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = if (captures.isEmpty()) {
                            "Your library is empty"
                        } else {
                            "No matching memories found"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (captures.isEmpty()) {
                            "Capture and save something to see it here."
                        } else {
                            "Try another search or category."
                        },
                        color = LibraryText
                    )
                }
            }

        } else {

            filteredCaptures.chunked(2).forEach { rowCaptures ->

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    rowCaptures.forEach { capture ->

                        LibraryImageCard(
                            capture = capture,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (selectionMode) {

                                    selectedFiles =
                                        if (
                                            capture.file.name in selectedFiles
                                        ) {
                                            selectedFiles - capture.file.name
                                        } else {
                                            selectedFiles + capture.file.name
                                        }

                                } else {
                                    selectedCapture = capture
                                }
                            },
                            selected = capture.file.name in selectedFiles,
                            selectionMode = selectionMode,
                            backupRefresh = backupRefresh
                        )
                    }

                    if (rowCaptures.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showBulkDeleteConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showBulkDeleteConfirmation = false
            },

            containerColor = LibraryCard,

            title = {
                Text(
                    "Delete permanently?",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This permanently deletes ${selectedFiles.size} selected image(s) and their saved details. This cannot be undone.",
                    color = LibraryText
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        val preferences = libraryPreferences(context)
                        val editor = preferences.edit()

                        var failed = 0

                        captures
                            .filter {
                                it.file.name in selectedFiles
                            }
                            .forEach { capture ->

                                if (
                                    !capture.file.exists() ||
                                    capture.file.delete()
                                ) {

                                    editor
                                        .remove("${capture.file.name}_title")
                                        .remove("${capture.file.name}_category")
                                        .remove("${capture.file.name}_note")
                                        .remove("${capture.file.name}_tags")
                                        .remove("${capture.file.name}_updatedAtMillis")

                                    RecallAiIndexer.removeMemory(
                                        context,
                                        capture.file.name
                                    )

                                    RecallBackupStatus.clearStatus(
                                        context,
                                        capture.file.name
                                    )

                                } else {
                                    failed++
                                }
                            }

                        editor.apply()

                        captures = loadLibraryCaptures(context)
                        selectedFiles = emptySet()
                        selectionMode = false
                        showBulkDeleteConfirmation = false

                        Toast.makeText(
                            context,
                            if (failed == 0) {
                                "Deleted permanently"
                            } else {
                                "$failed image(s) could not be deleted"
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Text(
                        "Delete permanently",
                        color = Color(0xFFFF7777)
                    )
                }
            },

            dismissButton = {
                TextButton(
                    onClick = {
                        showBulkDeleteConfirmation = false
                    }
                ) {
                    Text(
                        "Cancel",
                        color = LibraryPurple
                    )
                }
            }
        )
    }

    selectedCapture?.let { capture ->

        LibraryDetailsDialog(
            capture = capture,

            onDismiss = {
                selectedCapture = null
            },

            onSave = { title, category, note, tags ->

                val preferences = libraryPreferences(context)

                preferences.edit()
                    .putString("${capture.file.name}_title", title)
                    .putString("${capture.file.name}_category", category)
                    .putString("${capture.file.name}_note", note)
                    .putString("${capture.file.name}_tags", tags)
                    .apply()

                RecallAiIndexer.updateMemoryMetadata(
                    context,
                    capture.file.name,
                    title,
                    category,
                    note,
                    tags
                )

                captures = loadLibraryCaptures(context)
                selectedCapture = null

                Toast.makeText(
                    context,
                    "Memory updated",
                    Toast.LENGTH_SHORT
                ).show()
            },

            onDelete = {

                val deleted =
                    !capture.file.exists() || capture.file.delete()

                if (deleted) {

                    libraryPreferences(context)
                        .edit()
                        .remove("${capture.file.name}_title")
                        .remove("${capture.file.name}_category")
                        .remove("${capture.file.name}_note")
                        .remove("${capture.file.name}_tags")
                        .remove("${capture.file.name}_updatedAtMillis")
                        .apply()

                    RecallAiIndexer.removeMemory(
                        context,
                        capture.file.name
                    )

                    RecallBackupStatus.clearStatus(
                        context,
                        capture.file.name
                    )

                    captures = loadLibraryCaptures(context)
                    selectedCapture = null

                    Toast.makeText(
                        context,
                        "Deleted permanently",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    Toast.makeText(
                        context,
                        "Could not delete this image",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun LibraryImageCard(
    capture: LibraryCapture,
    modifier: Modifier,
    onClick: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    backupRefresh: Int
) {

    val context = LocalContext.current

    val bitmap = remember(
        capture.file.absolutePath,
        capture.file.lastModified()
    ) {
        decodeLibraryImage(capture.file, 1200)
    }

    val backupStatus = remember(
        capture.file.name,
        backupRefresh
    ) {
        RecallBackupStatus.getStatus(
            context,
            capture.file.name
        )
    }

    var showImageViewer by remember(
        capture.file.absolutePath
    ) {
        mutableStateOf(false)
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            showImageViewer = false
        }
    }

    Card(
        modifier = modifier.clickable {
            onClick()
        },
        colors = CardDefaults.cardColors(
            containerColor = LibraryCard
        ),
        shape = RoundedCornerShape(14.dp),
        border = if (selected) {
            BorderStroke(2.dp, LibraryPurple)
        } else {
            null
        }
    ) {

        Column {

            Box {

                if (bitmap != null) {

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = capture.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(145.dp)
                            .clickable {
                                if (selectionMode) {
                                    onClick()
                                } else {
                                    showImageViewer = true
                                }
                            },
                        contentScale = ContentScale.Crop
                    )

                } else {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(145.dp)
                            .background(LibraryCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Image unavailable",
                            color = LibraryText
                        )
                    }
                }

                if (selectionMode && selected) {

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .size(25.dp)
                            .background(
                                LibraryPurple,
                                RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "✓",
                            color = LibraryBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (bitmap != null && !selectionMode) {

                    Text(
                        text = "ZOOM",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .background(
                                Color(0x99000000),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(
                                horizontal = 7.dp,
                                vertical = 4.dp
                            )
                    )
                }
            }

            Column(
                modifier = Modifier.padding(11.dp)
            ) {

                Text(
                    text = capture.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = capture.category,
                    color = LibraryPurple,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    text = "Backup: $backupStatus",
                    color = when (backupStatus) {
                        RecallBackupStatus.BACKED_UP ->
                            Color(0xFF81C784)

                        RecallBackupStatus.FAILED ->
                            Color(0xFFFF7777)

                        RecallBackupStatus.PENDING ->
                            Color(0xFFFFD180)

                        else -> LibraryText
                    },
                    fontSize = 11.sp
                )
            }
        }
    }

    if (
        showImageViewer &&
        bitmap != null &&
        !selectionMode
    ) {

        Dialog(
            onDismissRequest = {
                showImageViewer = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {

                RecallZoomableImage(
                    bitmap = bitmap,
                    contentDescription = capture.title
                )

                TextButton(
                    onClick = {
                        showImageViewer = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Text(
                        "Close",
                        color = Color.White
                    )
                }

                Text(
                    text = "Pinch to zoom • Drag to move • Double-tap to reset",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryDetailsDialog(
    capture: LibraryCapture,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDelete: () -> Unit
) {

    var title by remember(capture.file.name) {
        mutableStateOf(capture.title)
    }

    var category by remember(capture.file.name) {
        mutableStateOf(capture.category)
    }

    var note by remember(capture.file.name) {
        mutableStateOf(capture.note)
    }

    var tags by remember(capture.file.name) {
        mutableStateOf(capture.tags)
    }

    var categoryExpanded by remember {
        mutableStateOf(false)
    }

    var deleteConfirmation by remember {
        mutableStateOf(false)
    }

    var showImageViewer by remember(capture.file.name) {
        mutableStateOf(false)
    }

    val bitmap = remember(
        capture.file.absolutePath,
        capture.file.lastModified()
    ) {
        decodeLibraryImage(capture.file, 1800)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LibraryCard,

        title = {
            Text(
                "Memory Details",
                color = LibraryPurple,
                fontWeight = FontWeight.Bold
            )
        },

        text = {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (bitmap != null) {

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clickable {
                                showImageViewer = true
                            },
                        contentScale = ContentScale.Fit
                    )

                    Text(
                        "Tap image to zoom",
                        color = LibraryPurple,
                        fontSize = 11.sp
                    )

                } else {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(
                                LibraryBackground,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Image unavailable",
                            color = LibraryText
                        )
                    }
                }

                Text(
                    text = "Saved: ${
                        SimpleDateFormat(
                            "dd MMM yyyy, hh:mm a",
                            Locale.getDefault()
                        ).format(
                            Date(capture.file.lastModified())
                        )
                    }",
                    color = LibraryText,
                    fontSize = 12.sp
                )

                if (capture.recognizedText.isNotBlank()) {

                    Text(
                        "Recognized text",
                        color = LibraryPurple,
                        fontWeight = FontWeight.Bold
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = LibraryBackground
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = capture.recognizedText,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Text(
                    "EDITABLE DETAILS",
                    color = LibraryPurple,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Title",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = libraryFieldColors()
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
                        border = BorderStroke(
                            1.dp,
                            LibraryFieldBorder
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = LibraryFieldBackground
                        )
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

                        libraryCategories.forEach { item ->

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
                    onValueChange = {
                        note = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("What do you want to remember?")
                    },
                    minLines = 2,
                    colors = libraryFieldColors()
                )

                Text(
                    "Tags",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = tags,
                    onValueChange = {
                        tags = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Example: shoes, birthday, ideas")
                    },
                    singleLine = true,
                    colors = libraryFieldColors()
                )

                Text(
                    "Tap any highlighted field to edit it.",
                    color = LibraryPurple,
                    fontSize = 11.sp
                )
            }
        },

        confirmButton = {

            Button(
                onClick = {
                    onSave(
                        title.ifBlank {
                            capture.file.nameWithoutExtension
                        },
                        category,
                        note,
                        tags
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = LibraryPurple
                )
            ) {
                Text(
                    "Save",
                    color = LibraryBackground
                )
            }
        },

        dismissButton = {

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                TextButton(
                    onClick = {
                        deleteConfirmation = true
                    }
                ) {
                    Text(
                        "Delete permanently",
                        color = Color(0xFFFF7777)
                    )
                }

                TextButton(
                    onClick = onDismiss
                ) {
                    Text(
                        "Close",
                        color = LibraryPurple
                    )
                }
            }
        }
    )

    if (deleteConfirmation) {

        AlertDialog(
            onDismissRequest = {
                deleteConfirmation = false
            },
            containerColor = LibraryCard,

            title = {
                Text(
                    "Delete permanently?",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This permanently deletes this image and its saved details. This cannot be undone.",
                    color = LibraryText
                )
            },

            confirmButton = {

                TextButton(
                    onClick = onDelete
                ) {
                    Text(
                        "Delete permanently",
                        color = Color(0xFFFF7777)
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        deleteConfirmation = false
                    }
                ) {
                    Text(
                        "Cancel",
                        color = LibraryPurple
                    )
                }
            }
        )
    }

    if (showImageViewer && bitmap != null) {

        Dialog(
            onDismissRequest = {
                showImageViewer = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {

                RecallZoomableImage(
                    bitmap = bitmap,
                    contentDescription = title
                )

                TextButton(
                    onClick = {
                        showImageViewer = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Text(
                        "Close",
                        color = Color.White
                    )
                }

                Text(
                    text = "Pinch to zoom • Drag to move • Double-tap to reset",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
fun RecallZoomableImage(
    bitmap: Bitmap,
    contentDescription: String
) {

    var scale by remember(bitmap) {
        mutableFloatStateOf(1f)
    }

    var offset by remember(bitmap) {
        mutableStateOf(Offset.Zero)
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures {
                        centroid, pan, zoom, _ ->

                    val previousScale = scale

                    val newScale = (scale * zoom).coerceIn(
                        1f,
                        5f
                    )

                    val center = Offset(
                        size.width / 2f,
                        size.height / 2f
                    )

                    val relativeCentroid = centroid - center

                    offset =
                        (offset + relativeCentroid) *
                                (newScale / previousScale) -
                                relativeCentroid + pan

                    scale = newScale

                    if (scale <= 1f) {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    )
}

@Composable
private fun libraryFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LibraryPurple,
        unfocusedBorderColor = LibraryFieldBorder,
        focusedContainerColor = LibraryFieldBackground,
        unfocusedContainerColor = LibraryFieldBackground,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = LibraryPurple,
        unfocusedLabelColor = LibraryText,
        cursorColor = LibraryPurple
    )