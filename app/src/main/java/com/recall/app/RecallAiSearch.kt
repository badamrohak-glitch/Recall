package com.recall.app

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast

import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

private val AiBackground = Color(0xFF100F17)
private val AiCard = Color(0xFF201E2B)
private val AiPurple = Color(0xFFB69CFF)
private val AiSecondary = Color(0xFFAAA6B8)

private val aiCategories = listOf(
    "Shopping",
    "Places",
    "Food",
    "Study",
    "Designs",
    "Articles",
    "Other"
)

// ---------------------------------------------------------------------
// IMAGE DECODING
// ---------------------------------------------------------------------

private fun decodeSampledImage(
    file: File,
    maximumSize: Int
): Bitmap? {

    if (!file.exists()) return null

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeFile(file.absolutePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sampleSize = 1

    while (
        bounds.outWidth / sampleSize > maximumSize ||
        bounds.outHeight / sampleSize > maximumSize
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return BitmapFactory.decodeFile(
        file.absolutePath,
        options
    )
}

// ---------------------------------------------------------------------
// REUSABLE FULL-SCREEN IMAGE VIEWER
// Uses RecallZoomableImage from RecallLibraryScreen.kt
// ---------------------------------------------------------------------

@Composable
private fun AiImageViewerDialog(
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
                .background(Color(0xFF08070D))
        ) {

            RecallZoomableImage(
                bitmap = bitmap,
                contentDescription = title
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(
                        Color(0xFF292238),
                        RoundedCornerShape(50)
                    )
            ) {

                Text(
                    "✕ Close",
                    color = AiPurple
                )
            }

            Text(
                "Pinch to zoom • Drag to move • Double-tap to reset",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(
                        Color(0xCC201E2B),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(
                        horizontal = 14.dp,
                        vertical = 10.dp
                    )
            )
        }
    }
}

// ---------------------------------------------------------------------
// DATA MODELS
// ---------------------------------------------------------------------

internal data class AiMemory(
    val fileName: String,
    val title: String,
    val category: String,
    val note: String,
    val tags: String,
    val labels: List<String>,
    val recognizedText: String,
    val description: String,
    val modified: Long,
    val signature: String,
    val localAnalyzed: Boolean = false,
    val geminiAttemptedAt: Long = 0L,
    val geminiRetryAfter: Long = 0L,
    val imageSignature: String = ""
)

internal data class AiSyncResult(
    val total: Int,
    val failed: Int,
    val lastError: String? = null,
    val localFailed: Int = 0,
    val geminiFailed: Int = 0,
    val quotaBlocked: Boolean = false
)

// ---------------------------------------------------------------------
// AI INDEXER
// ---------------------------------------------------------------------

internal object RecallAiIndexer {

    private const val PREFS_NAME = "recall_ai_search"
    private const val INDEX_KEY = "indexed_memories"
    private const val QUOTA_BLOCK_KEY = "gemini_quota_blocked_until"
    private const val MAX_GEMINI_REQUESTS_PER_REFRESH = 4

    private const val OCR_INDEX_VERSION = "ocr-v3"

    private const val QUOTA_RETRY_DELAY =
        22L * 60L * 60L * 1000L

    private const val NORMAL_RETRY_DELAY =
        60L * 60L * 1000L

    private const val CHECKPOINT_INTERVAL = 3

    private val geminiModel by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel("gemini-3.8-flash")
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun load(
        context: Context,
        includeExcluded: Boolean = false
    ): List<AiMemory> {

        val json = preferences(context)
            .getString(INDEX_KEY, "[]") ?: "[]"

        return try {

            val array = JSONArray(json)

            (0 until array.length()).mapNotNull { index ->

                val item = array.optJSONObject(index)
                    ?: return@mapNotNull null

                AiMemory(
                    fileName = item.optString("fileName"),
                    title = item.optString("title"),
                    category = item.optString("category"),
                    note = item.optString("note"),
                    tags = item.optString("tags"),
                    labels = item.optJSONArray("labels").toStringList(),
                    recognizedText = item.optString("recognizedText"),
                    description = item.optString("description"),
                    modified = item.optLong("modified"),
                    signature = item.optString("signature"),
                    localAnalyzed = item.optBoolean("localAnalyzed", false),
                    geminiAttemptedAt = item.optLong("geminiAttemptedAt", 0L),
                    geminiRetryAfter = item.optLong("geminiRetryAfter", 0L),
                    imageSignature = item.optString("imageSignature", "")
                )
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(
        context: Context,
        memories: List<AiMemory>
    ) {

        val array = JSONArray()

        memories.forEach { memory ->

            array.put(
                JSONObject().apply {
                    put("fileName", memory.fileName)
                    put("title", memory.title)
                    put("category", memory.category)
                    put("note", memory.note)
                    put("tags", memory.tags)
                    put("labels", JSONArray(memory.labels))
                    put("recognizedText", memory.recognizedText)
                    put("description", memory.description)
                    put("modified", memory.modified)
                    put("signature", memory.signature)
                    put("localAnalyzed", memory.localAnalyzed)
                    put("geminiAttemptedAt", memory.geminiAttemptedAt)
                    put("geminiRetryAfter", memory.geminiRetryAfter)
                    put("imageSignature", memory.imageSignature)
                }
            )
        }

        preferences(context)
            .edit()
            .putString(INDEX_KEY, array.toString())
            .apply()
    }

    private fun loadAllIncludingExcluded(context: Context): List<AiMemory> =
        load(context, includeExcluded = true)

    fun updateMemoryDescription(
        context: Context,
        fileName: String,
        description: String
    ) {

        val updated = loadAllIncludingExcluded(context).map { memory ->

            if (memory.fileName == fileName) {
                memory.copy(description = description)
            } else {
                memory
            }
        }

        saveIndex(context, updated)
    }

    suspend fun understandSearchQuery(query: String): String {

        if (query.isBlank()) return ""

        val prompt = content {

            text(
                """
                You are the natural-language search assistant for Recall.

                Recall is an application that stores screenshots and personal
                photos. The user wants to find something they previously saved.

                Understand the user's request and convert it into a concise
                list of useful search concepts.

                USER REQUEST:
                $query

                Instructions:

                1. Understand what the user is actually trying to find.
                2. Identify important objects, colours, clothing, applications,
                   interface elements, visible text and visual details.
                3. Include a few relevant synonyms or alternative descriptions.
                4. Understand conversational descriptions such as
                   "that purple page I saw" or "the food picture".
                5. Do not invent objects, people, places or details.
                6. Do not add unrelated generic search terms.
                7. Do not interpret dates or times as visual objects.
                8. Keep the important meaning of the original request.
                9. Return only 3 to 8 useful search terms or short phrases.
                10. Separate the terms with spaces.
                11. Do not explain your answer.

                Return only the search terms.
                """.trimIndent()
            )
        }

        return withTimeoutOrNull(60000L) {

            geminiModel.generateContent(prompt)
                .text
                ?.trim()
                ?.take(500)
                .orEmpty()

        } ?: throw IllegalStateException(
            "Search understanding timed out."
        )
    }

    fun updateMemoryMetadata(
        context: Context,
        fileName: String,
        title: String,
        category: String,
        note: String,
        tags: String
    ) {

        val updated = load(context).map { memory ->

            if (memory.fileName == fileName) {
                memory.copy(
                    title = title,
                    category = category,
                    note = note,
                    tags = tags
                )
            } else {
                memory
            }
        }

        saveIndex(context, updated)
    }

    fun removeMemory(
        context: Context,
        fileName: String
    ) {

        saveIndex(
            context,
            load(context).filterNot {
                it.fileName == fileName
            }
        )
    }

    fun removeFromSaved(
        context: Context,
        fileName: String
    ): Boolean {

        val savedFolder = File(
            context.filesDir,
            "recall_captures"
        )

        val libraryFolder = File(
            context.filesDir,
            "recall_library_only"
        )

        val source = File(savedFolder, fileName)
        val destination = File(libraryFolder, fileName)

        if (!source.exists()) return false
        if (destination.exists()) return false

        if (!libraryFolder.exists() && !libraryFolder.mkdirs()) {
            return false
        }

        var moved = source.renameTo(destination)

        if (!moved) {

            moved = try {

                source.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (source.delete()) {
                    true
                } else {
                    destination.delete()
                    false
                }

            } catch (_: Exception) {
                destination.delete()
                false
            }
        }

        if (moved) {
            removeMemory(context, fileName)
        }

        return moved
    }

    suspend fun syncLibrary(
        context: Context,
        onProgress: (Int, Int) -> Unit
    ): AiSyncResult = withContext(Dispatchers.IO) {

        val folder = File(context.filesDir, "recall_captures")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val files = folder.listFiles()
            ?.filter {
                it.isFile && it.extension.equals("png", true)
            }
            ?.sortedByDescending {
                it.lastModified()
            }
            ?: emptyList()

        val metadata = context.getSharedPreferences(
            "recall_library",
            Context.MODE_PRIVATE
        )

        val previous = load(context).associateBy {
            it.fileName
        }

        val updated = mutableListOf<AiMemory>()

        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.DEFAULT_OPTIONS
        )

        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        var localFailed = 0
        var geminiFailed = 0
        var lastError: String? = null
        var geminiRequests = 0
        var quotaBlocked = false

        val currentTime = System.currentTimeMillis()

        val storedQuotaBlock = preferences(context)
            .getLong(QUOTA_BLOCK_KEY, 0L)

        val globalQuotaBlocked = storedQuotaBlock > currentTime

        withContext(Dispatchers.Main) {
            onProgress(0, files.size)
        }

        try {

            files.forEachIndexed { index, file ->

                val title = metadata.getString(
                    "${file.name}_title",
                    file.nameWithoutExtension
                ) ?: file.nameWithoutExtension

                val category = metadata.getString(
                    "${file.name}_category",
                    "Other"
                ) ?: "Other"

                val note = metadata.getString(
                    "${file.name}_note",
                    ""
                ) ?: ""

                val tags = metadata.getString(
                    "${file.name}_tags",
                    ""
                ) ?: ""

                val imageSignature = listOf(
                    file.length(),
                    file.lastModified(),
                    OCR_INDEX_VERSION
                ).joinToString("|")

                val old = previous[file.name]

                val oldImageIsUnchanged =
                    old != null && old.imageSignature == imageSignature

                var memory: AiMemory? = if (
                    old != null && oldImageIsUnchanged
                ) {

                    old.copy(
                        title = title,
                        category = category,
                        note = note,
                        tags = tags,
                        modified = file.lastModified(),
                        signature = imageSignature,
                        imageSignature = imageSignature
                    )

                } else {
                    null
                }

                try {

                    if (memory == null || !memory.localAnalyzed) {

                        val image = InputImage.fromFilePath(
                            context,
                            Uri.fromFile(file)
                        )

                        val labelsResult = runCatching {
                            labeler.process(image)
                                .awaitResult()
                                .map { it.text }
                        }

                        val ocrResult = runCatching {
                            recognizer.process(image)
                                .awaitResult()
                                .text
                                .take(12000)
                        }

                        if (
                            labelsResult.isFailure ||
                            ocrResult.isFailure
                        ) {

                            localFailed++

                            lastError =
                                labelsResult.exceptionOrNull()?.message
                                    ?: ocrResult.exceptionOrNull()?.message
                                            ?: "Local image analysis failed."
                        }

                        val labels =
                            labelsResult.getOrDefault(emptyList())

                        val recognizedText =
                            ocrResult.getOrDefault("")

                        memory = AiMemory(
                            fileName = file.name,
                            title = title,
                            category = category,
                            note = note,
                            tags = tags,
                            labels = labels,
                            recognizedText = recognizedText,
                            description = old?.description.orEmpty(),
                            modified = file.lastModified(),
                            signature = imageSignature,
                            localAnalyzed =
                                labelsResult.isSuccess && ocrResult.isSuccess,
                            geminiAttemptedAt =
                                old?.geminiAttemptedAt ?: 0L,
                            geminiRetryAfter =
                                old?.geminiRetryAfter ?: 0L,
                            imageSignature = imageSignature
                        )
                    }

                    val existing = memory!!

                    val canTryGemini =
                        existing.description.isBlank() &&
                                existing.geminiRetryAfter <= currentTime &&
                                !globalQuotaBlocked &&
                                !quotaBlocked &&
                                geminiRequests < MAX_GEMINI_REQUESTS_PER_REFRESH

                    if (canTryGemini) {

                        geminiRequests++

                        try {

                            val generatedDescription =
                                withTimeoutOrNull(45_000L) {
                                    describeImageWithGemini(file)
                                } ?: throw IllegalStateException(
                                    "Image analysis timed out."
                                )

                            if (generatedDescription.isNotBlank()) {

                                memory = existing.copy(
                                    description = generatedDescription,
                                    geminiAttemptedAt =
                                        System.currentTimeMillis(),
                                    geminiRetryAfter = 0L
                                )

                            } else {

                                memory = existing.copy(
                                    geminiAttemptedAt =
                                        System.currentTimeMillis(),
                                    geminiRetryAfter =
                                        System.currentTimeMillis() +
                                                NORMAL_RETRY_DELAY
                                )

                                geminiFailed++

                                lastError =
                                    "Image description could not be generated."
                            }

                        } catch (error: Exception) {

                            val message = error.message
                                ?.takeIf { it.isNotBlank() }
                                ?: error.javaClass.simpleName

                            val isQuotaError =
                                message.contains("quota", true) ||
                                        message.contains("429", true) ||
                                        message.contains("resource_exhausted", true) ||
                                        message.contains("rate limit", true)

                            geminiFailed++
                            lastError =
                                "Some image descriptions could not be generated."

                            if (isQuotaError) {

                                quotaBlocked = true

                                preferences(context)
                                    .edit()
                                    .putLong(
                                        QUOTA_BLOCK_KEY,
                                        System.currentTimeMillis() +
                                                QUOTA_RETRY_DELAY
                                    )
                                    .apply()
                            }

                            val retryDelay = if (isQuotaError) {
                                QUOTA_RETRY_DELAY
                            } else {
                                NORMAL_RETRY_DELAY
                            }

                            memory = existing.copy(
                                geminiAttemptedAt =
                                    System.currentTimeMillis(),
                                geminiRetryAfter =
                                    System.currentTimeMillis() + retryDelay
                            )
                        }
                    }

                    updated.add(memory!!)

                } catch (error: Exception) {

                    localFailed++

                    lastError = "Some images could not be analysed."

                    updated.add(
                        memory ?: AiMemory(
                            fileName = file.name,
                            title = title,
                            category = category,
                            note = note,
                            tags = tags,
                            labels = emptyList(),
                            recognizedText = "",
                            description = old?.description.orEmpty(),
                            modified = file.lastModified(),
                            signature = imageSignature,
                            localAnalyzed = false,
                            geminiAttemptedAt =
                                old?.geminiAttemptedAt ?: 0L,
                            geminiRetryAfter =
                                old?.geminiRetryAfter ?: 0L,
                            imageSignature = imageSignature
                        )
                    )
                }

                if (
                    updated.size % CHECKPOINT_INTERVAL == 0 ||
                    index == files.lastIndex
                ) {
                    saveIndex(context, updated)
                }

                withContext(Dispatchers.Main) {
                    onProgress(index + 1, files.size)
                }
            }

        } finally {
            labeler.close()
            recognizer.close()
        }

        saveIndex(context, updated)

        AiSyncResult(
            total = updated.size,
            failed = localFailed + geminiFailed,
            lastError = lastError,
            localFailed = localFailed,
            geminiFailed = geminiFailed,
            quotaBlocked = quotaBlocked || globalQuotaBlocked
        )
    }

    private suspend fun describeImageWithGemini(
        file: File
    ): String {

        val bitmap = decodeForGemini(file)
            ?: throw IllegalStateException(
                "Could not decode image."
            )

        return try {

            val prompt = content {

                image(bitmap)

                text(
                    """
                    Analyse this image for a personal screenshot
                    and photo search application.

                    Give a useful, factual description.

                    Identify:
                    - Visible objects and their characteristics.
                    - Colours, clothing and surroundings.
                    - The type of screen or application, if identifiable.
                    - Visible text, headings, buttons and interface elements.
                    - Important visual relationships and layout.
                    - Distinctive details that could help someone find
                      this image later.

                    Include useful search terms naturally.

                    Do not invent names, locations, brands or information
                    that cannot be established from the image.

                    Return only the description.
                    """.trimIndent()
                )
            }

            geminiModel.generateContent(prompt)
                .text
                ?.trim()
                ?.take(2000)
                .orEmpty()

        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeForGemini(file: File): Bitmap? {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sampleSize = 1

        while (
            bounds.outWidth / sampleSize > 1280 ||
            bounds.outHeight / sampleSize > 1280
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            options
        )
    }

    private fun JSONArray?.toStringList(): List<String> {

        if (this == null) return emptyList()

        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->

            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }
}

// ---------------------------------------------------------------------
// SMART SEARCH ENGINE
// ---------------------------------------------------------------------

private object RecallAiSearchEngine {

    private val ignoredWords = setOf(
        "the", "a", "an", "and", "or", "with",
        "from", "that", "this", "in", "on", "at",
        "to", "my", "me", "i", "we", "you",
        "was", "were", "is", "it", "of", "for",
        "show", "find", "where", "when", "saved",
        "save", "picture", "photo", "photos",
        "image", "images", "memory", "memories",
        "please", "can", "get", "all", "about",
        "what", "which", "do", "did", "have",
        "has", "had", "looking", "look", "search",
        "searching", "something", "anything",
        "saw", "see", "seen", "there", "here",
        "want", "need", "give", "last", "week",
        "month", "year", "today", "yesterday",
        "morning", "afternoon", "evening", "night",
        "around", "during", "near", "some",
        "kind", "type", "one"
    )

    private val synonymGroups = listOf(
        setOf("laptop", "computer", "notebook", "pc"),
        setOf("phone", "mobile", "smartphone", "cellphone"),
        setOf("shoes", "shoe", "sneakers", "trainers", "footwear"),
        setOf("clothes", "clothing", "outfit", "fashion", "apparel"),
        setOf("food", "meal", "dish", "restaurant", "cafe"),
        setOf("car", "vehicle", "automobile"),
        setOf("bike", "bicycle", "cycle"),
        setOf("code", "coding", "programming", "software", "development"),
        setOf("screenshot", "screen", "display", "interface", "ui"),
        setOf("document", "pdf", "file", "paper"),
        setOf("receipt", "invoice", "bill", "payment"),
        setOf("map", "location", "navigation", "directions"),
        setOf("travel", "trip", "journey", "vacation", "holiday"),
        setOf("picture", "photo", "image", "photograph"),
        setOf("money", "price", "cost", "payment", "expense"),
        setOf("meeting", "appointment", "event", "schedule"),
        setOf("website", "webpage", "web", "site", "portal", "online", "browser"),
        setOf("learn", "learning", "study", "studying", "tutorial", "course", "education", "lesson"),
        setOf("login", "signin", "sign-in", "authentication", "account"),
        setOf("button", "control", "element", "menu", "navigation", "tab"),
        setOf("shopping", "shop", "store", "purchase", "buy", "product"),
        setOf("notes", "note", "text", "writing", "written", "message"),
        setOf("place", "address", "destination", "venue", "area")
    )

    fun search(
        query: String,
        memories: List<AiMemory>,
        expandedQuery: String = ""
    ): List<AiMemory> {

        if (query.isBlank()) return emptyList()

        val normalizedOriginal = normalize(query)

        val normalized = normalize(
            listOf(query, expandedQuery)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )

        val dateFilter = getDateFilter(normalizedOriginal)
        val timeFilter = getTimeFilter(normalizedOriginal)

        val tokens = normalized
            .split(Regex("\\s+"))
            .filter { token ->

                token.length > 1 &&
                        token !in ignoredWords &&
                        !token.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                        !token.matches(Regex("\\d{1,2}:\\d{2}")) &&
                        !token.matches(Regex("\\d{1,2}(am|pm)"))
            }
            .distinct()

        val searchConcepts = tokens.map {
            expandToken(it)
        }

        return memories.mapNotNull { memory ->

            val date = Date(memory.modified)

            if (dateFilter != null && !dateFilter(date)) {
                return@mapNotNull null
            }

            if (timeFilter != null && !timeFilter(date)) {
                return@mapNotNull null
            }

            val title = normalize(memory.title)
            val category = normalize(memory.category)
            val note = normalize(memory.note)
            val tags = normalize(memory.tags)
            val labels = normalize(memory.labels.joinToString(" "))
            val ocr = normalize(memory.recognizedText)
            val description = normalize(memory.description)

            val allText = listOf(
                title,
                category,
                note,
                tags,
                labels,
                ocr,
                description
            ).joinToString(" ")

            var score = 0
            var matchedConcepts = 0

            if (
                normalizedOriginal.length > 2 &&
                allText.contains(normalizedOriginal)
            ) {
                score += 14
            }

            searchConcepts.forEach { alternatives ->

                val titleMatch = matchesAny(title, alternatives)
                val categoryMatch = matchesAny(category, alternatives)
                val tagMatch = matchesAny(tags, alternatives)
                val noteMatch = matchesAny(note, alternatives)
                val labelMatch = matchesAny(labels, alternatives)
                val ocrMatch = matchesAny(ocr, alternatives)
                val descriptionMatch = matchesAny(description, alternatives)

                val matched = titleMatch ||
                        categoryMatch ||
                        tagMatch ||
                        noteMatch ||
                        labelMatch ||
                        ocrMatch ||
                        descriptionMatch

                if (matched) matchedConcepts++

                if (titleMatch) score += 9
                if (categoryMatch) score += 6
                if (tagMatch) score += 7
                if (noteMatch) score += 7
                if (labelMatch) score += 4
                if (ocrMatch) score += 5
                if (descriptionMatch) score += 7
            }

            val hasDateOrTimeFilter =
                dateFilter != null || timeFilter != null

            if (hasDateOrTimeFilter) score += 3

            if (searchConcepts.isEmpty() && !hasDateOrTimeFilter) {
                return@mapNotNull null
            }

            val requiredMatches = when {
                searchConcepts.isEmpty() -> 0
                searchConcepts.size == 1 -> 1
                else -> ceil(searchConcepts.size * 0.5).toInt()
            }

            if (matchedConcepts < requiredMatches) {
                return@mapNotNull null
            }

            if (score > 0) memory to score else null

        }.sortedWith(
            compareByDescending<Pair<AiMemory, Int>> {
                it.second
            }.thenByDescending {
                it.first.modified
            }
        ).map {
            it.first
        }
    }

    private fun matchesAny(
        text: String,
        alternatives: Set<String>
    ): Boolean {

        if (text.isBlank()) return false

        val words = text.split(Regex("\\s+"))

        return alternatives.any { alternative ->

            words.any { word ->

                word == alternative ||

                        (
                                minOf(word.length, alternative.length) >= 3 &&
                                        (
                                                word.startsWith(alternative) ||
                                                        alternative.startsWith(word)
                                                )
                                ) ||

                        (
                                minOf(word.length, alternative.length) >= 4 &&
                                        levenshteinDistance(word, alternative) <=
                                        if (maxOf(word.length, alternative.length) >= 8) 2 else 1
                                )
            }
        }
    }

    private fun levenshteinDistance(
        first: String,
        second: String
    ): Int {

        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        var previous = IntArray(second.length + 1) {
            it
        }

        for (i in first.indices) {

            val current = IntArray(second.length + 1)
            current[0] = i + 1

            for (j in second.indices) {

                val cost = if (first[i] == second[j]) 0 else 1

                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }

            previous = current
        }

        return previous[second.length]
    }

    private fun normalize(value: String): String {

        return value
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s:/-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun expandToken(token: String): Set<String> {

        val results = mutableSetOf(token)

        synonymGroups.forEach { group ->
            if (token in group) {
                results.addAll(group)
            }
        }

        if (token.endsWith("ies") && token.length > 4) {
            results.add(token.dropLast(3) + "y")
        } else if (token.endsWith("s") && token.length > 3) {
            results.add(token.dropLast(1))
        }

        return results
    }

    private fun getDateFilter(
        query: String
    ): ((Date) -> Boolean)? {

        val today = Calendar.getInstance()

        if (query.contains("today")) {
            return { date -> sameDay(date, today.time) }
        }

        if (query.contains("yesterday")) {

            val yesterday = today.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)

            return { date -> sameDay(date, yesterday.time) }
        }

        if (query.contains("last week") || query.contains("this week")) {

            val start = today.clone() as Calendar

            start.set(Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)

            if (query.contains("last week")) {
                start.add(Calendar.WEEK_OF_YEAR, -1)
            }

            val end = start.clone() as Calendar
            end.add(Calendar.DAY_OF_YEAR, 7)

            return { date ->
                date.time >= start.timeInMillis &&
                        date.time < end.timeInMillis
            }
        }

        if (query.contains("last month") || query.contains("this month")) {

            val start = today.clone() as Calendar

            start.set(Calendar.DAY_OF_MONTH, 1)
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)

            if (query.contains("last month")) {
                start.add(Calendar.MONTH, -1)
            }

            val end = start.clone() as Calendar
            end.add(Calendar.MONTH, 1)

            return { date ->
                date.time >= start.timeInMillis &&
                        date.time < end.timeInMillis
            }
        }

        val isoDate = Regex("\\d{4}-\\d{2}-\\d{2}")
            .find(query)?.value

        if (isoDate != null) {

            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.isLenient = false

            val target = try {
                format.parse(isoDate)
            } catch (_: Exception) {
                null
            }

            if (target != null) {
                return { date -> sameDay(date, target) }
            }
        }

        val weekdays = mapOf(
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY
        )

        weekdays.forEach { (name, dayNumber) ->

            if (Regex("\\b${name}\\b").containsMatchIn(query)) {

                return { date ->

                    val calendar = Calendar.getInstance().apply {
                        time = date
                    }

                    calendar.get(Calendar.DAY_OF_WEEK) == dayNumber
                }
            }
        }

        val months = listOf(
            "january", "february", "march", "april",
            "may", "june", "july", "august",
            "september", "october", "november", "december"
        )

        val monthIndex = months.indexOfFirst {
            Regex("\\b${it}\\b").containsMatchIn(query)
        }

        if (monthIndex >= 0) {

            val year = Regex("\\b20\\d{2}\\b")
                .find(query)
                ?.value
                ?.toIntOrNull()

            return { date ->

                val calendar = Calendar.getInstance().apply {
                    time = date
                }

                calendar.get(Calendar.MONTH) == monthIndex &&
                        (
                                year == null ||
                                        calendar.get(Calendar.YEAR) == year
                                )
            }
        }

        return null
    }

    private fun getTimeFilter(
        query: String
    ): ((Date) -> Boolean)? {

        val period = when {
            query.contains("morning") -> 5..11
            query.contains("afternoon") -> 12..16
            query.contains("evening") -> 17..20
            query.contains("night") -> 21..23
            else -> null
        }

        if (period != null) {

            return { date ->

                val hour = Calendar.getInstance().apply {
                    time = date
                }.get(Calendar.HOUR_OF_DAY)

                if (query.contains("night")) {
                    hour in 21..23 || hour in 0..4
                } else {
                    hour in period
                }
            }
        }

        val match = Regex(
            "\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b"
        ).find(query) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val periodText = match.groupValues[3]

        if (hour !in 1..12 || minute !in 0..59) return null

        if (periodText == "pm" && hour != 12) hour += 12
        if (periodText == "am" && hour == 12) hour = 0

        return { date ->

            val calendar = Calendar.getInstance().apply {
                time = date
            }

            val savedMinutes =
                calendar.get(Calendar.HOUR_OF_DAY) * 60 +
                        calendar.get(Calendar.MINUTE)

            val requestedMinutes = hour * 60 + minute

            kotlin.math.abs(savedMinutes - requestedMinutes) <= 60
        }
    }

    private fun sameDay(
        first: Date,
        second: Date
    ): Boolean {

        val firstCalendar = Calendar.getInstance().apply {
            time = first
        }

        val secondCalendar = Calendar.getInstance().apply {
            time = second
        }

        return firstCalendar.get(Calendar.YEAR) ==
                secondCalendar.get(Calendar.YEAR) &&
                firstCalendar.get(Calendar.DAY_OF_YEAR) ==
                secondCalendar.get(Calendar.DAY_OF_YEAR)
    }
}

// ---------------------------------------------------------------------
// AI SEARCH SCREEN
// ---------------------------------------------------------------------

@Composable
fun RecallAiSearchScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember {
        mutableStateOf("")
    }

    var expandedQuery by remember {
        mutableStateOf("")
    }

    var aiSearching by remember {
        mutableStateOf(false)
    }

    var memories by remember {
        mutableStateOf(RecallAiIndexer.load(context))
    }

    var indexing by remember {
        mutableStateOf(false)
    }

    var progress by remember {
        mutableIntStateOf(0)
    }

    var total by remember {
        mutableIntStateOf(0)
    }

    var status by remember {
        mutableStateOf("Preparing your saved memories...")
    }

    var selectedMemory by remember {
        mutableStateOf<AiMemory?>(null)
    }

    var showManagement by remember {
        mutableStateOf(false)
    }

    var zoomedBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var zoomedImageTitle by remember {
        mutableStateOf("")
    }

    val results = remember(query, expandedQuery, memories) {
        RecallAiSearchEngine.search(
            query = query,
            memories = memories,
            expandedQuery = expandedQuery
        )
    }

    fun refreshIndex() {

        if (indexing) return

        scope.launch {

            indexing = true
            progress = 0
            total = 0
            status = "Checking your Recall Library..."

            try {

                val result = RecallAiIndexer.syncLibrary(context) { done, count ->

                    progress = done
                    total = count

                    status = when {
                        count == 0 ->
                            "Your Library is empty."

                        done == 0 ->
                            "Starting image analysis..."

                        else ->
                            "Updating your saved memories: $done of $count"
                    }
                }

                memories = RecallAiIndexer.load(context)

                status = when {

                    result.total == 0 ->
                        "Your Library is empty. Save something first."

                    result.localFailed > 0 && result.geminiFailed > 0 ->
                        "${result.total} memories checked. Some images need another analysis attempt."

                    result.localFailed > 0 ->
                        "${result.total} memories checked. Some images need another analysis attempt."

                    result.quotaBlocked ->
                        "${result.total} memories are ready. Some smart features will be available later."

                    result.geminiFailed > 0 ->
                        "${result.total} memories indexed. Some descriptions will be updated later. Search is ready."

                    else ->
                        "Your memories are ready to search."
                }

            } catch (_: Exception) {

                status = "Couldn't update your saved memories. Please try again."

            } finally {
                indexing = false
            }
        }
    }

    fun askRecallAI() {

        if (query.isBlank() || aiSearching) return

        scope.launch {

            aiSearching = true
            expandedQuery = ""

            try {

                val understoodQuery =
                    RecallAiIndexer.understandSearchQuery(query)

                expandedQuery = understoodQuery

                if (understoodQuery.isNotBlank()) {

                    Toast.makeText(
                        context,
                        "Your search is ready!",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    Toast.makeText(
                        context,
                        "Using standard search.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (_: Exception) {

                expandedQuery = ""

                Toast.makeText(
                    context,
                    "Smart search is taking a break. You can still search normally.",
                    Toast.LENGTH_LONG
                ).show()

            } finally {
                aiSearching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshIndex()
    }

    if (showManagement) {

        BackHandler {
            showManagement = false
        }

        RecallMemoryManagementScreen(
            onBack = {
                showManagement = false
            },
            onChanged = {
                memories = RecallAiIndexer.load(context)
            }
        )

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBackground)
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 8.dp)
    ) {

        Text(
            text = "Saved",
            color = AiPurple,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Find anything you've saved in Recall with AI-powered search.",
            color = AiSecondary,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                showManagement = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "Manage saved memories",
                color = AiPurple
            )
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expandedQuery = ""
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Describe what you're looking for...",
                    color = AiSecondary
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            trailingIcon = {

                if (query.isNotEmpty()) {

                    TextButton(
                        onClick = {
                            query = ""
                            expandedQuery = ""
                        },
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text(
                            text = "×",
                            color = AiPurple,
                            fontSize = 25.sp
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiPurple,
                unfocusedBorderColor = Color(0xFF76649E),
                focusedContainerColor = Color(0xFF292238),
                unfocusedContainerColor = Color(0xFF252131),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = AiPurple,
                focusedTrailingIconColor = AiPurple,
                unfocusedTrailingIconColor = AiSecondary
            )
        )

        Spacer(Modifier.height(9.dp))

        Button(
            onClick = {
                askRecallAI()
            },
            enabled = query.isNotBlank() && !aiSearching,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AiPurple,
                contentColor = AiBackground,
                disabledContainerColor = Color(0xFF554B69),
                disabledContentColor = AiSecondary
            )
        ) {

            if (aiSearching) {

                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = AiBackground,
                    strokeWidth = 2.dp
                )

                Spacer(Modifier.width(9.dp))

                Text(
                    "Understanding your search...",
                    fontWeight = FontWeight.SemiBold
                )

            } else {

                Text(
                    "✦ Ask Recall AI",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (expandedQuery.isNotBlank()) {

            Spacer(Modifier.height(5.dp))

            Text(
                text = "AI understood: $expandedQuery",
                color = AiSecondary,
                fontSize = 11.sp,
                maxLines = 2
            )
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {

                Text(
                    text = "Try: \"black shoes\" • \"purple login screen\" • \"saved yesterday\"",
                    color = AiSecondary,
                    fontSize = 12.sp
                )
            }

            item {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AiCard
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {

                    Column(Modifier.padding(14.dp)) {

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = "Your Recall index",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )

                            TextButton(
                                onClick = {
                                    refreshIndex()
                                },
                                enabled = !indexing
                            ) {
                                Text(
                                    "Refresh",
                                    color = AiPurple
                                )
                            }
                        }

                        Text(
                            text = status,
                            color = AiSecondary,
                            fontSize = 12.sp
                        )

                        if (indexing && total > 0) {

                            Spacer(Modifier.height(9.dp))

                            LinearProgressIndicator(
                                progress = {
                                    progress.toFloat() /
                                            total.coerceAtLeast(1)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = AiPurple,
                                trackColor = Color(0xFF393047)
                            )
                        }

                        Text(
                            text = "${memories.size} saved memories indexed",
                            color = AiPurple,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (query.isNotBlank()) {

                item {

                    Text(
                        text = "${results.size} matching memories",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (results.isEmpty()) {

                    item {

                        Text(
                            text = when {
                                indexing ->
                                    "Preparing your search index..."

                                memories.isEmpty() ->
                                    "No saved images are indexed yet. Save an image to your Recall Library first."

                                aiSearching ->
                                    "Recall is understanding your search..."

                                else ->
                                    "No matching memories found. Try Ask Recall AI or use different words."
                            },
                            color = AiSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 30.dp)
                        )
                    }

                } else {

                    items(
                        items = results,
                        key = {
                            it.fileName
                        }
                    ) { memory ->

                        AiSearchResultCard(
                            memory = memory,
                            context = context,
                            onClick = {
                                selectedMemory = memory
                            },
                            onImageClick = { bitmap ->
                                zoomedBitmap = bitmap
                                zoomedImageTitle = memory.title
                            }
                        )
                    }
                }

            } else {

                item {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Text(
                            text = "All saved images",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = memories.size.toString(),
                            color = AiPurple,
                            fontSize = 13.sp
                        )
                    }
                }

                val visibleMemories = memories.filter { memory ->
                    File(
                        File(context.filesDir, "recall_captures"),
                        memory.fileName
                    ).exists()
                }

                if (visibleMemories.isEmpty()) {

                    item {

                        Text(
                            text = if (indexing) {
                                "Loading your saved images..."
                            } else {
                                "No saved images yet. Save a screenshot to see it here."
                            },
                            color = AiSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 30.dp)
                        )
                    }

                } else {

                    items(
                        items = visibleMemories.chunked(2),
                        key = { row ->
                            row.first().fileName
                        }
                    ) { rowMemories ->

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            rowMemories.forEach { memory ->

                                AiSavedGridCard(
                                    memory = memory,
                                    context = context,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedMemory = memory
                                    },
                                    onImageClick = { bitmap ->
                                        zoomedBitmap = bitmap
                                        zoomedImageTitle = memory.title
                                    }
                                )
                            }

                            if (rowMemories.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    selectedMemory?.let { memory ->

        AiMemoryDetailsDialog(
            memory = memory,

            onDismiss = {
                selectedMemory = null
            },

            onSave = { title, category, note, tags, description ->

                val preferences = context.getSharedPreferences(
                    "recall_library",
                    Context.MODE_PRIVATE
                )

                preferences.edit()
                    .putString("${memory.fileName}_title", title)
                    .putString("${memory.fileName}_category", category)
                    .putString("${memory.fileName}_note", note)
                    .putString("${memory.fileName}_tags", tags)
                    .apply()

                RecallAiIndexer.updateMemoryMetadata(
                    context = context,
                    fileName = memory.fileName,
                    title = title,
                    category = category,
                    note = note,
                    tags = tags
                )

                RecallAiIndexer.updateMemoryDescription(
                    context,
                    memory.fileName,
                    description
                )

                memories = RecallAiIndexer.load(context)
                selectedMemory = null

                Toast.makeText(
                    context,
                    "Memory updated",
                    Toast.LENGTH_SHORT
                ).show()
            },

            onRemoveFromSaved = {

                val moved = RecallAiIndexer.removeFromSaved(
                    context,
                    memory.fileName
                )

                if (moved) {

                    memories = RecallAiIndexer.load(context)
                    selectedMemory = null

                    Toast.makeText(
                        context,
                        "Removed from Saved. Still available in Library.",
                        Toast.LENGTH_LONG
                    ).show()

                } else {

                    Toast.makeText(
                        context,
                        "Could not move this image to your Library.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },

            onDelete = {

                val file = File(
                    File(context.filesDir, "recall_captures"),
                    memory.fileName
                )

                val deleted = !file.exists() || file.delete()

                if (deleted) {

                    context.getSharedPreferences(
                        "recall_library",
                        Context.MODE_PRIVATE
                    ).edit()
                        .remove("${memory.fileName}_title")
                        .remove("${memory.fileName}_category")
                        .remove("${memory.fileName}_note")
                        .remove("${memory.fileName}_tags")
                        .remove("${memory.fileName}_updatedAtMillis")
                        .apply()

                    RecallAiIndexer.removeMemory(
                        context,
                        memory.fileName
                    )

                    memories = RecallAiIndexer.load(context)
                    selectedMemory = null

                    Toast.makeText(
                        context,
                        "Deleted permanently",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    Toast.makeText(
                        context,
                        "Could not delete this image",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },

            onImageClick = { bitmap ->
                zoomedBitmap = bitmap
                zoomedImageTitle = memory.title
            }
        )
    }

    zoomedBitmap?.let { bitmap ->

        AiImageViewerDialog(
            bitmap = bitmap,
            title = zoomedImageTitle,
            onDismiss = {
                zoomedBitmap = null
            }
        )
    }
}

// ---------------------------------------------------------------------
// SAVED MEMORY MANAGEMENT
// ---------------------------------------------------------------------

private data class RecallManageItem(
    val file: File,
    val title: String,
    val category: String,
    val description: String
)

@Composable
private fun RecallMemoryManagementScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    var selected by remember {
        mutableStateOf(setOf<String>())
    }

    var showDeleteConfirmation by remember {
        mutableStateOf(false)
    }

    var zoomedBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var zoomedImageTitle by remember {
        mutableStateOf("")
    }

    val items = remember(refreshKey) {

        val folder = File(
            context.filesDir,
            "recall_captures"
        )

        val prefs = context.getSharedPreferences(
            "recall_library",
            Context.MODE_PRIVATE
        )

        val indexed = RecallAiIndexer.load(context)
            .associateBy { it.fileName }

        folder.listFiles()
            ?.filter {
                it.isFile && it.extension.equals("png", true)
            }
            ?.sortedByDescending {
                it.lastModified()
            }
            ?.map { file ->

                val memory = indexed[file.name]

                RecallManageItem(
                    file = file,
                    title = prefs.getString(
                        "${file.name}_title",
                        file.nameWithoutExtension
                    ) ?: file.nameWithoutExtension,
                    category = prefs.getString(
                        "${file.name}_category",
                        "Other"
                    ) ?: "Other",
                    description = memory?.description
                        ?.takeIf { it.isNotBlank() }
                        ?: memory?.recognizedText
                            ?.takeIf { it.isNotBlank() }
                        ?: "No description available yet."
                )
            } ?: emptyList()
    }

    val selectedItems = items.filter {
        it.file.name in selected
    }

    val allSelected =
        items.isNotEmpty() && selected.size == items.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBackground)
            .padding(16.dp)
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            TextButton(onClick = onBack) {
                Text(
                    "← Back",
                    color = AiPurple
                )
            }

            Text(
                "Manage Saved",
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            "Delete a memory to remove it permanently from Saved and AI Search.",
            color = AiSecondary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "${items.size} saved memories",
                color = AiSecondary,
                fontSize = 13.sp
            )

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = {
                    selected =
                        if (allSelected) {
                            emptySet()
                        } else {
                            items.map { it.file.name }.toSet()
                        }
                }
            ) {
                Text(
                    if (allSelected) "Deselect all" else "Select all",
                    color = AiPurple
                )
            }

            TextButton(
                onClick = {
                    selected = emptySet()
                    refreshKey++
                }
            ) {
                Text(
                    "Refresh",
                    color = AiSecondary
                )
            }
        }

        if (items.isEmpty()) {

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No saved images yet.",
                    color = AiSecondary
                )
            }

        } else {

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {

                items(
                    items = items.chunked(2),
                    key = { row ->
                        row.first().file.name
                    }
                ) { rowItems ->

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        rowItems.forEach { item ->

                            val isSelected =
                                item.file.name in selected

                            val bitmap = remember(
                                item.file.absolutePath,
                                item.file.lastModified()
                            ) {
                                decodeSampledImage(item.file, 600)
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {

                                        selected =
                                            if (isSelected) {
                                                selected - item.file.name
                                            } else {
                                                selected + item.file.name
                                            }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = AiCard
                                ),
                                shape = RoundedCornerShape(14.dp),
                                border = if (isSelected) {
                                    androidx.compose.foundation.BorderStroke(
                                        2.dp,
                                        AiPurple
                                    )
                                } else {
                                    null
                                }
                            ) {

                                Column {

                                    Box {

                                        if (bitmap != null) {

                                            Image(
                                                bitmap.asImageBitmap(),
                                                item.title,
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(132.dp)
                                                    .clickable {
                                                        scope.launch {
                                                            val highResolutionBitmap =
                                                                withContext(Dispatchers.IO) {
                                                                    decodeSampledImage(
                                                                        item.file,
                                                                        1800
                                                                    )
                                                                }

                                                            if (highResolutionBitmap != null) {
                                                                zoomedBitmap =
                                                                    highResolutionBitmap

                                                                zoomedImageTitle =
                                                                    item.title
                                                            } else {
                                                                Toast.makeText(
                                                                    context,
                                                                    "Unable to load the full-resolution image.",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    },
                                                contentScale = ContentScale.Crop
                                            )

                                        } else {

                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(132.dp)
                                                    .background(AiBackground),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "Image unavailable",
                                                    color = AiSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (isSelected) {

                                            Box(
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(7.dp)
                                                    .size(25.dp)
                                                    .background(
                                                        AiPurple,
                                                        RoundedCornerShape(50)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "✓",
                                                    color = AiBackground,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Column(
                                        Modifier.padding(10.dp)
                                    ) {

                                        Text(
                                            item.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )

                                        Text(
                                            item.category,
                                            color = AiPurple,
                                            fontSize = 11.sp
                                        )

                                        Spacer(Modifier.height(5.dp))

                                        Text(
                                            item.description,
                                            color = AiSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 4
                                        )
                                    }
                                }
                            }
                        }

                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (selectedItems.isNotEmpty()) {

            Spacer(Modifier.height(8.dp))

            Text(
                "${selectedItems.size} selected",
                color = AiSecondary,
                fontSize = 13.sp
            )

            Button(
                onClick = {
                    showDeleteConfirmation = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB94E63)
                )
            ) {
                Text(
                    "Delete permanently (${selectedItems.size})",
                    color = Color.White
                )
            }
        }
    }

    if (showDeleteConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmation = false
            },
            containerColor = AiCard,

            title = {
                Text(
                    "Delete selected memories?",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This permanently deletes ${selectedItems.size} image(s) and removes them from Saved, the Library, and AI Search. This cannot be undone.",
                    color = AiSecondary
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        val prefs = context.getSharedPreferences(
                            "recall_library",
                            Context.MODE_PRIVATE
                        )

                        val editor = prefs.edit()
                        var failed = 0

                        selectedItems.forEach { item ->

                            if (
                                !item.file.exists() ||
                                item.file.delete()
                            ) {

                                editor
                                    .remove("${item.file.name}_title")
                                    .remove("${item.file.name}_category")
                                    .remove("${item.file.name}_note")
                                    .remove("${item.file.name}_tags")
                                    .remove("${item.file.name}_updatedAtMillis")

                                RecallAiIndexer.removeMemory(
                                    context,
                                    item.file.name
                                )

                            } else {
                                failed++
                            }
                        }

                        editor.apply()

                        selected = emptySet()
                        refreshKey++
                        onChanged()
                        showDeleteConfirmation = false

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
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(
                        "Cancel",
                        color = AiPurple
                    )
                }
            }
        )
    }

    zoomedBitmap?.let { bitmap ->

        AiImageViewerDialog(
            bitmap = bitmap,
            title = zoomedImageTitle,
            onDismiss = {
                zoomedBitmap = null
            }
        )
    }
}

// ---------------------------------------------------------------------
// SAVED IMAGE GRID CARD
// ---------------------------------------------------------------------

@Composable
private fun AiSavedGridCard(
    memory: AiMemory,
    context: Context,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onImageClick: (Bitmap) -> Unit
) {

    val file = File(
        File(context.filesDir, "recall_captures"),
        memory.fileName
    )

    val bitmap = remember(
        memory.fileName,
        file.lastModified()
    ) {
        decodeSampledImage(file, 700)
    }

    val scope = rememberCoroutineScope()

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = AiCard
        )
    ) {

        Column {

            if (bitmap != null) {

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = memory.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.88f)
                        .clickable {
                            scope.launch {

                                val highResolutionBitmap =
                                    withContext(Dispatchers.IO) {
                                        decodeSampledImage(file, 1800)
                                    }

                                if (highResolutionBitmap != null) {
                                    onImageClick(highResolutionBitmap)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Unable to load the full-resolution image.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                    contentScale = ContentScale.Crop
                )

            } else {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.88f)
                        .background(AiBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Image unavailable",
                        color = AiSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Column(
                modifier = Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 9.dp
                )
            ) {

                Text(
                    text = memory.title.ifBlank {
                        memory.fileName.substringBeforeLast('.')
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = memory.category,
                    color = AiPurple,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// SEARCH RESULT CARD
// ---------------------------------------------------------------------

@Composable
private fun AiSearchResultCard(
    memory: AiMemory,
    context: Context,
    onClick: () -> Unit,
    onImageClick: (Bitmap) -> Unit
) {

    val file = File(
        File(context.filesDir, "recall_captures"),
        memory.fileName
    )

    val bitmap = remember(
        memory.fileName,
        file.lastModified()
    ) {
        decodeSampledImage(file, 600)
    }

    val scope = rememberCoroutineScope()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AiCard
        ),
        shape = RoundedCornerShape(14.dp)
    ) {

        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (bitmap != null) {

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = memory.title,
                    modifier = Modifier
                        .size(94.dp)
                        .background(
                            AiBackground,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            scope.launch {

                                val highResolutionBitmap =
                                    withContext(Dispatchers.IO) {
                                        decodeSampledImage(file, 1800)
                                    }

                                if (highResolutionBitmap != null) {
                                    onImageClick(highResolutionBitmap)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Unable to load the full-resolution image.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                    contentScale = ContentScale.Crop
                )

            } else {

                Box(
                    modifier = Modifier
                        .size(94.dp)
                        .background(
                            AiBackground,
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Image\nunavailable",
                        color = AiSecondary,
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
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = memory.category,
                    color = AiPurple,
                    fontSize = 12.sp
                )

                if (memory.note.isNotBlank()) {

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = memory.note,
                        color = AiSecondary,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                if (memory.labels.isNotEmpty()) {

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = memory.labels.take(4).joinToString(", "),
                        color = AiSecondary,
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(5.dp))

                Text(
                    text = SimpleDateFormat(
                        "dd MMM yyyy, hh:mm a",
                        Locale.getDefault()
                    ).format(Date(memory.modified)),
                    color = AiSecondary,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    text = "Tap image to zoom • Tap card for details",
                    color = AiPurple,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// MEMORY DETAILS
// ---------------------------------------------------------------------

@Composable
private fun AiMemoryDetailsDialog(
    memory: AiMemory,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
    onRemoveFromSaved: () -> Unit,
    onDelete: () -> Unit,
    onImageClick: (Bitmap) -> Unit
) {

    var title by remember(memory.fileName) {
        mutableStateOf(memory.title)
    }

    var category by remember(memory.fileName) {
        mutableStateOf(memory.category)
    }

    var note by remember(memory.fileName) {
        mutableStateOf(memory.note)
    }

    var tags by remember(memory.fileName) {
        mutableStateOf(memory.tags)
    }

    var description by remember(memory.fileName) {
        mutableStateOf(memory.description)
    }

    var categoryExpanded by remember {
        mutableStateOf(false)
    }

    var deleteConfirmation by remember {
        mutableStateOf(false)
    }

    var removeConfirmation by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current

    val file = File(
        File(context.filesDir, "recall_captures"),
        memory.fileName
    )

    val bitmap = remember(
        memory.fileName,
        file.lastModified()
    ) {
        decodeSampledImage(file, 1800)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AiCard,

        title = {
            Text(
                "Memory Details",
                color = AiPurple,
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
                            .height(210.dp)
                            .clickable {
                                onImageClick(bitmap)
                            },
                        contentScale = ContentScale.Fit
                    )

                    Text(
                        "Tap image to zoom",
                        color = AiPurple,
                        fontSize = 11.sp
                    )

                } else {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(
                                AiBackground,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Image unavailable",
                            color = AiSecondary
                        )
                    }
                }

                Text(
                    text = "Saved: ${
                        SimpleDateFormat(
                            "dd MMM yyyy, hh:mm a",
                            Locale.getDefault()
                        ).format(Date(memory.modified))
                    }",
                    color = AiSecondary,
                    fontSize = 12.sp
                )

                Text(
                    "EDITABLE DETAILS",
                    color = AiPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "These fields can be changed and saved.",
                    color = AiSecondary,
                    fontSize = 11.sp
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
                    colors = aiFieldColors()
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
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            AiPurple.copy(alpha = 0.65f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF292238)
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

                        aiCategories.forEach { item ->

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
                    colors = aiFieldColors()
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
                    colors = aiFieldColors()
                )

                if (memory.labels.isNotEmpty()) {

                    Text(
                        "Detected objects",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        memory.labels.joinToString(", "),
                        color = AiSecondary,
                        fontSize = 13.sp
                    )
                }

                if (memory.recognizedText.isNotBlank()) {

                    Text(
                        "Recognized text",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        memory.recognizedText.take(1500),
                        color = AiSecondary,
                        fontSize = 13.sp
                    )
                }

                Text(
                    "Image description",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Edit the image description...")
                    },
                    minLines = 3,
                    colors = aiFieldColors()
                )
            }
        },

        confirmButton = {

            Button(
                onClick = {
                    onSave(
                        title.ifBlank {
                            memory.fileName.substringBeforeLast(".")
                        },
                        category,
                        note,
                        tags,
                        description
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AiPurple
                )
            ) {
                Text(
                    "Save",
                    color = AiBackground
                )
            }
        },

        dismissButton = {

            Column(
                horizontalAlignment = Alignment.End
            ) {

                TextButton(
                    onClick = {
                        removeConfirmation = true
                    }
                ) {
                    Text(
                        "Remove from saved",
                        color = AiPurple
                    )
                }

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
                        color = AiSecondary
                    )
                }
            }
        }
    )

    if (removeConfirmation) {

        AlertDialog(
            onDismissRequest = {
                removeConfirmation = false
            },
            containerColor = AiCard,

            title = {
                Text(
                    "Remove from Saved?",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This image will disappear from Saved and AI Search, but it will remain in Your Library. Its notes, tags and other details will be kept.",
                    color = AiSecondary
                )
            },

            confirmButton = {

                TextButton(
                    onClick = onRemoveFromSaved
                ) {
                    Text(
                        "Remove from saved",
                        color = AiPurple
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        removeConfirmation = false
                    }
                ) {
                    Text(
                        "Cancel",
                        color = AiSecondary
                    )
                }
            }
        )
    }

    if (deleteConfirmation) {

        AlertDialog(
            onDismissRequest = {
                deleteConfirmation = false
            },
            containerColor = AiCard,

            title = {
                Text(
                    "Delete permanently?",
                    color = Color.White
                )
            },

            text = {
                Text(
                    "This permanently deletes the image and its saved details from Recall. It will no longer be available in Saved, Library or AI Search. This cannot be undone.",
                    color = AiSecondary
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
                        color = AiPurple
                    )
                }
            }
        )
    }
}

// ---------------------------------------------------------------------
// EDITABLE FIELD COLORS
// ---------------------------------------------------------------------

@Composable
private fun aiFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AiPurple,
        unfocusedBorderColor = Color(0xFF76649E),
        focusedContainerColor = Color(0xFF292238),
        unfocusedContainerColor = Color(0xFF252131),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = AiPurple,
        focusedLabelColor = AiPurple,
        unfocusedLabelColor = AiSecondary
    )