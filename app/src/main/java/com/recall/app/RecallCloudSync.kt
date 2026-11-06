
package com.recall.app

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecallCloudMemory(
    val filename: String = "",
    val title: String = "",
    val category: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
)

data class RecallSyncResult(
    val uploaded: Int,
    val unchanged: Int,
    val deleted: Int,
    val cloudNewer: List<RecallCloudMemory>
)

class RecallCloudSync {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    companion object {
        private const val PREFS_NAME = "recall_cloud_sync"
        private const val PENDING_DELETIONS =
            "pending_cloud_deletions"
    }

    private fun userId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException(
                "Please sign in before syncing."
            )
    }

    private fun memoriesCollection() =
        db.collection("users")
            .document(userId())
            .collection("memories")

    private fun validateFilename(filename: String) {
        require(filename.isNotBlank()) {
            "Memory filename cannot be empty."
        }

        require(
            !filename.contains("/") &&
                    !filename.contains("\\") &&
                    filename != "." &&
                    filename != ".."
        ) {
            "Invalid memory filename."
        }
    }

    // Record a deletion so it can be synchronized later.
    // Call this BEFORE deleting a local memory.
    fun recordPendingDeletion(
        context: Context,
        filename: String
    ) {
        validateFilename(filename)

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val pending = prefs.getStringSet(
            PENDING_DELETIONS,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

        pending.add(filename)

        prefs.edit()
            .putStringSet(PENDING_DELETIONS, pending)
            .apply()
    }

    // Upload one metadata record.
    // The image is never uploaded.
    suspend fun uploadMetadata(
        memory: RecallCloudMemory
    ) {
        validateFilename(memory.filename)

        val data = hashMapOf<String, Any>(
            "filename" to memory.filename,
            "title" to memory.title,
            "category" to memory.category,
            "note" to memory.note,
            "tags" to memory.tags,
            "updatedAtMillis" to memory.updatedAtMillis,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        memoriesCollection()
            .document(memory.filename)
            .set(data, SetOptions.merge())
            .awaitResult()
    }

    // Fetch all metadata for the signed-in user.
    suspend fun fetchAllMetadata(): List<RecallCloudMemory> {

        val snapshot = memoriesCollection()
            .get()
            .awaitResult()

        return snapshot.documents.mapNotNull { document ->

            val filename =
                document.getString("filename") ?: document.id

            if (filename.isBlank()) {
                return@mapNotNull null
            }

            RecallCloudMemory(
                filename = filename,
                title = document.getString("title") ?: "",
                category = document.getString("category") ?: "",
                note = document.getString("note") ?: "",
                tags = (document.get("tags") as? List<*>)
                    ?.filterIsInstance<String>()
                    ?: emptyList(),
                updatedAtMillis =
                    document.getLong("updatedAtMillis") ?: 0L
            )
        }
    }

    // Delete only a cloud metadata record.
    suspend fun deleteMetadata(filename: String) {
        validateFilename(filename)

        memoriesCollection()
            .document(filename)
            .delete()
            .awaitResult()
    }

    // Manual synchronization.
    //
    // localMemories must contain metadata for images
    // that currently exist in the local library.
    //
    // Cloud-newer records are returned for later
    // reconciliation. They are NOT silently applied
    // because their image files may not exist locally.
    suspend fun syncMemories(
        context: Context,
        localMemories: List<RecallCloudMemory>
    ): RecallSyncResult {

        userId() // Check authentication before starting.

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val pending = prefs.getStringSet(
            PENDING_DELETIONS,
            emptySet()
        )?.toSet() ?: emptySet()

        var deletedCount = 0

        // Process explicitly recorded deletions first.
        for (filename in pending) {
            validateFilename(filename)
            deleteMetadata(filename)
            deletedCount++
        }

        // Fetch the current cloud metadata.
        val cloudMemories = fetchAllMetadata()
            .associateBy { it.filename }

        var uploadedCount = 0
        var unchangedCount = 0

        val cloudNewer = mutableListOf<RecallCloudMemory>()

        val localNames = mutableSetOf<String>()

        for (local in localMemories) {

            validateFilename(local.filename)

            // Do not re-upload a memory marked for deletion.
            if (local.filename in pending) {
                continue
            }

            // Avoid duplicate records in the same sync.
            if (!localNames.add(local.filename)) {
                continue
            }

            val cloud = cloudMemories[local.filename]

            if (cloud == null) {
                uploadMetadata(local)
                uploadedCount++
                continue
            }

            when {
                local.updatedAtMillis > cloud.updatedAtMillis -> {
                    uploadMetadata(local)
                    uploadedCount++
                }

                cloud.updatedAtMillis > local.updatedAtMillis -> {
                    cloudNewer.add(cloud)
                }

                else -> {
                    unchangedCount++
                }
            }
        }

        // Clear only deletions that were successfully processed.
        if (pending.isNotEmpty()) {
            val remaining = prefs.getStringSet(
                PENDING_DELETIONS,
                emptySet()
            )?.toMutableSet() ?: mutableSetOf()

            remaining.removeAll(pending)

            prefs.edit()
                .putStringSet(PENDING_DELETIONS, remaining)
                .apply()
        }

        return RecallSyncResult(
            uploaded = uploadedCount,
            unchanged = unchangedCount,
            deleted = deletedCount,
            cloudNewer = cloudNewer
        )
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->

            addOnCompleteListener { task ->

                if (!continuation.isActive) {
                    return@addOnCompleteListener
                }

                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    continuation.resumeWithException(
                        task.exception
                            ?: Exception("Cloud sync failed.")
                    )
                }
            }
        }
}
