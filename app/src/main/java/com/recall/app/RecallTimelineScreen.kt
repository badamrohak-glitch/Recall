
package com.recall.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TimelineBackground = Color(0xFF100F17)
private val TimelineCard = Color(0xFF201E2B)
private val TimelinePurple = Color(0xFFB69CFF)
private val TimelineText = Color(0xFFAAA6B8)
private val TimelineFieldBackground = Color(0xFF292438)
private val TimelineFieldBorder = Color(0xFF75639F)

private const val TIMELINE_THUMBNAIL_SIZE = 500
private const val TIMELINE_FULL_IMAGE_SIZE = 1800

private data class TimelineMemory(
    val file: File,
    val title: String,
    val category: String,
    val note: String,
    val date: Long
)

private enum class TimelineGrouping {
    DAY,
    WEEK,
    MONTH
}

private fun loadTimelineMemories(
    context: Context
): List<TimelineMemory> {

    val folders = listOf(
        File(context.filesDir, "recall_captures"),
        File(context.filesDir, "recall_library_only")
    )

    val preferences = context.getSharedPreferences(
        "recall_library",
        Context.MODE_PRIVATE
    )

    return folders
        .flatMap { folder ->
            folder.listFiles()?.toList().orEmpty()
        }
        .filter { file ->
            file.isFile && file.extension.equals("png", true)
        }
        .distinctBy { it.absolutePath }
        .map { file ->

            TimelineMemory(
                file = file,
                title = preferences.getString(
                    "${file.name}_title",
                    file.nameWithoutExtension
                ) ?: file.nameWithoutExtension,
                category = preferences.getString(
                    "${file.name}_category",
                    "Other"
                ) ?: "Other",
                note = preferences.getString(
                    "${file.name}_note",
                    ""
                ) ?: "",
                date = file.lastModified()
            )
        }
        .sortedByDescending { it.date }
}

private fun timelineGroupKey(
    timestamp: Long,
    grouping: TimelineGrouping
): String {

    val date = Date(timestamp)

    return when (grouping) {

        TimelineGrouping.DAY -> {
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(date)
        }

        TimelineGrouping.MONTH -> {
            SimpleDateFormat(
                "yyyy-MM",
                Locale.getDefault()
            ).format(date)
        }

        TimelineGrouping.WEEK -> {

            val calendar = Calendar.getInstance().apply {
                timeInMillis = timestamp
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4

                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(calendar.time)
        }
    }
}

private fun timelineGroupTitle(
    timestamp: Long,
    grouping: TimelineGrouping
): String {

    return when (grouping) {

        TimelineGrouping.DAY -> {
            SimpleDateFormat(
                "EEEE, dd MMMM yyyy",
                Locale.getDefault()
            ).format(Date(timestamp))
        }

        TimelineGrouping.MONTH -> {
            SimpleDateFormat(
                "MMMM yyyy",
                Locale.getDefault()
            ).format(Date(timestamp))
        }

        TimelineGrouping.WEEK -> {

            val calendar = Calendar.getInstance().apply {
                timeInMillis = timestamp
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4

                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val start = calendar.time
            calendar.add(Calendar.DAY_OF_YEAR, 6)
            val end = calendar.time

            val formatter = SimpleDateFormat(
                "dd MMM",
                Locale.getDefault()
            )

            "Week: ${formatter.format(start)} - ${formatter.format(end)}"
        }
    }
}

/*
 * Loads an image at the requested maximum dimension.
 *
 * Thumbnails use 500px.
 * Details and zoom use 1800px.
 *
 * ARGB_8888 preserves better colour quality.
 */
private fun decodeTimelineImage(
    file: File,
    maximumSize: Int = TIMELINE_THUMBNAIL_SIZE
): Bitmap? {

    if (!file.exists()) return null

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeFile(
        file.absolutePath,
        bounds
    )

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sample = 1

    while (
        bounds.outWidth / sample > maximumSize ||
        bounds.outHeight / sample > maximumSize
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

@Composable
fun RecallTimelineScreen() {

    val context = LocalContext.current

    var memories by remember {
        mutableStateOf(loadTimelineMemories(context))
    }

    var grouping by remember {
        mutableStateOf(TimelineGrouping.DAY)
    }

    var selectedMemory by remember {
        mutableStateOf<TimelineMemory?>(null)
    }

    LaunchedEffect(Unit) {
        memories = loadTimelineMemories(context)
    }

    val groupedMemories = memories
        .groupBy {
            timelineGroupKey(it.date, grouping)
        }
        .toSortedMap(compareByDescending { it })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TimelineBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        Text(
            text = "Memory Timeline",
            color = TimelinePurple,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(5.dp))

        Text(
            text = "Your memories, arranged through time.",
            color = TimelineText,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = TimelineCard
            ),
            shape = RoundedCornerShape(14.dp)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column {

                    Text(
                        text = "${memories.size}",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Saved memories",
                        color = TimelineText,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "LATEST FIRST",
                    color = TimelinePurple,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Group memories by",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            listOf(
                TimelineGrouping.DAY,
                TimelineGrouping.WEEK,
                TimelineGrouping.MONTH
            ).forEach { option ->

                FilterChip(
                    selected = grouping == option,
                    onClick = {
                        grouping = option
                    },
                    label = {
                        Text(
                            when (option) {
                                TimelineGrouping.DAY -> "Day"
                                TimelineGrouping.WEEK -> "Week"
                                TimelineGrouping.MONTH -> "Month"
                            }
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TimelinePurple,
                        selectedLabelColor = TimelineBackground,
                        labelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (memories.isEmpty()) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = TimelineCard
                ),
                shape = RoundedCornerShape(16.dp)
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "Your timeline is empty",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Save some memories and they will appear here in chronological order.",
                        color = TimelineText,
                        fontSize = 13.sp
                    )
                }
            }

        } else {

            groupedMemories.forEach { (_, group) ->

                val firstMemory = group.first()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                TimelinePurple,
                                RoundedCornerShape(50)
                            )
                    )

                    Spacer(Modifier.width(9.dp))

                    Text(
                        text = timelineGroupTitle(
                            firstMemory.date,
                            grouping
                        ),
                        color = TimelinePurple,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = "${group.size} memories",
                        color = TimelineText,
                        fontSize = 11.sp
                    )
                }

                group.forEach { memory ->

                    TimelineMemoryCard(
                        memory = memory,
                        onClick = {
                            selectedMemory = memory
                        }
                    )

                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(14.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    selectedMemory?.let { memory ->

        TimelineMemoryDetailsDialog(
            memory = memory,
            onDismiss = {
                selectedMemory = null
            },
            onSave = { updatedMemory ->

                memories = loadTimelineMemories(context)

                selectedMemory = memories.firstOrNull {
                    it.file.absolutePath ==
                            updatedMemory.file.absolutePath
                }
            }
        )
    }
}

@Composable
private fun TimelineMemoryCard(
    memory: TimelineMemory,
    onClick: () -> Unit
) {

    val lastModified = memory.file.lastModified()

    // Small image for the timeline card.
    val bitmap = remember(
        memory.file.absolutePath,
        lastModified
    ) {
        decodeTimelineImage(
            memory.file,
            TIMELINE_THUMBNAIL_SIZE
        )
    }

    var showImageViewer by remember(memory.file.absolutePath) {
        mutableStateOf(false)
    }

    // Load a separate high-resolution image when zoom is requested.
    val zoomBitmap = remember(
        memory.file.absolutePath,
        lastModified,
        showImageViewer
    ) {
        if (showImageViewer) {
            decodeTimelineImage(
                memory.file,
                TIMELINE_FULL_IMAGE_SIZE
            )
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = TimelineCard
        ),
        shape = RoundedCornerShape(14.dp)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (bitmap != null) {

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = memory.title,
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            TimelineBackground,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            showImageViewer = true
                        },
                    contentScale = ContentScale.Crop
                )

            } else {

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            TimelineBackground,
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        "No image",
                        color = TimelineText,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = memory.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    text = memory.category,
                    color = TimelinePurple,
                    fontSize = 12.sp
                )

                if (memory.note.isNotBlank()) {

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = memory.note,
                        color = TimelineText,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(Date(memory.date)),
                    color = TimelineText,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Tap image to zoom • Tap card for details",
                    color = TimelinePurple,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showImageViewer && zoomBitmap != null) {

        TimelineImageViewer(
            bitmap = zoomBitmap,
            title = memory.title,
            onDismiss = {
                showImageViewer = false
            }
        )
    }
}

@Composable
private fun TimelineMemoryDetailsDialog(
    memory: TimelineMemory,
    onDismiss: () -> Unit,
    onSave: (TimelineMemory) -> Unit
) {

    val context = LocalContext.current

    var editing by remember(memory.file.absolutePath) {
        mutableStateOf(false)
    }

    var title by remember(memory.file.absolutePath) {
        mutableStateOf(memory.title)
    }

    var category by remember(memory.file.absolutePath) {
        mutableStateOf(memory.category)
    }

    var note by remember(memory.file.absolutePath) {
        mutableStateOf(memory.note)
    }

    var showImageViewer by remember(memory.file.absolutePath) {
        mutableStateOf(false)
    }

    // Details use a separate high-resolution bitmap.
    val bitmap = remember(
        memory.file.absolutePath,
        memory.file.lastModified()
    ) {
        decodeTimelineImage(
            memory.file,
            TIMELINE_FULL_IMAGE_SIZE
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TimelineCard,

        title = {
            Text(
                text = if (editing) "Edit Memory" else "Memory Details",
                color = TimelinePurple,
                fontWeight = FontWeight.Bold
            )
        },

        text = {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                if (bitmap != null) {

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = memory.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clickable {
                                showImageViewer = true
                            },
                        contentScale = ContentScale.Fit
                    )

                    Text(
                        text = "Tap image to zoom",
                        color = TimelinePurple,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(14.dp))
                }

                if (editing) {

                    Text(
                        text = "EDITABLE FIELDS",
                        color = TimelinePurple,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                        },
                        label = {
                            Text("Title")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        colors = timelineFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = category,
                        onValueChange = {
                            category = it
                        },
                        label = {
                            Text("Category")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = timelineFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = note,
                        onValueChange = {
                            note = it
                        },
                        label = {
                            Text("Description / Note")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        colors = timelineFieldColors()
                    )

                } else {

                    Text(
                        text = memory.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Category: ${memory.category}",
                        color = TimelinePurple,
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (memory.note.isBlank()) {
                            "No description added."
                        } else {
                            memory.note
                        },
                        color = TimelineText,
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = SimpleDateFormat(
                            "dd MMMM yyyy, hh:mm a",
                            Locale.getDefault()
                        ).format(Date(memory.date)),
                        color = TimelineText,
                        fontSize = 12.sp
                    )
                }
            }
        },

        confirmButton = {

            if (editing) {

                TextButton(
                    onClick = {

                        val preferences =
                            context.getSharedPreferences(
                                "recall_library",
                                Context.MODE_PRIVATE
                            )

                        val savedTitle = title.trim().ifBlank {
                            memory.file.nameWithoutExtension
                        }

                        val savedCategory = category.trim().ifBlank {
                            "Other"
                        }

                        val savedNote = note.trim()

                        preferences.edit()
                            .putString(
                                "${memory.file.name}_title",
                                savedTitle
                            )
                            .putString(
                                "${memory.file.name}_category",
                                savedCategory
                            )
                            .putString(
                                "${memory.file.name}_note",
                                savedNote
                            )
                            .apply()

                        val updatedMemory = memory.copy(
                            title = savedTitle,
                            category = savedCategory,
                            note = savedNote
                        )

                        editing = false
                        onSave(updatedMemory)
                    }
                ) {

                    Text(
                        "Save",
                        color = TimelinePurple
                    )
                }

            } else {

                TextButton(
                    onClick = {
                        editing = true
                    }
                ) {

                    Text(
                        "Edit",
                        color = TimelinePurple
                    )
                }
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text(
                    "Close",
                    color = TimelineText
                )
            }
        }
    )

    if (showImageViewer && bitmap != null) {

        TimelineImageViewer(
            bitmap = bitmap,
            title = memory.title,
            onDismiss = {
                showImageViewer = false
            }
        )
    }
}

@Composable
private fun timelineFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = TimelinePurple,
        unfocusedBorderColor = TimelineFieldBorder,
        focusedContainerColor = TimelineFieldBackground,
        unfocusedContainerColor = TimelineFieldBackground,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = TimelinePurple,
        unfocusedLabelColor = TimelineText,
        cursorColor = TimelinePurple
    )

@Composable
private fun TimelineImageViewer(
    bitmap: Bitmap,
    title: String,
    onDismiss: () -> Unit
) {

    Dialog(
        onDismissRequest = onDismiss,
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

            TimelineZoomableImage(
                bitmap = bitmap,
                contentDescription = title
            )

            TextButton(
                onClick = onDismiss,
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

@Composable
private fun TimelineZoomableImage(
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
                detectTransformGestures { centroid, pan, zoom, _ ->

                    val previousScale = scale
                    val newScale = (scale * zoom).coerceIn(1f, 5f)

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
