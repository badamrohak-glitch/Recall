
package com.recall.app

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

class RecallCloudManager {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // Get the current user's memories collection
    private fun getMemoriesCollection() =
        auth.currentUser?.uid?.let { uid ->
            firestore.collection("users")
                .document(uid)
                .collection("memories")
        }

    // Save or update memory metadata
    fun saveMemory(
        memoryId: String,
        metadata: Map<String, Any?>
    ): Task<Void> {

        val collection = getMemoriesCollection()
            ?: return Tasks.forException(
                IllegalStateException("User is not signed in.")
            )

        if (memoryId.isBlank()) {
            return Tasks.forException(
                IllegalArgumentException("Memory ID cannot be empty.")
            )
        }

        val data = HashMap(metadata)

        data["memoryId"] = memoryId
        data["updatedAt"] = FieldValue.serverTimestamp()

        return collection
            .document(memoryId)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
    }

    // Retrieve all memories belonging to the current user
    fun getMemories(): Task<QuerySnapshot> {

        val collection = getMemoriesCollection()
            ?: return Tasks.forException(
                IllegalStateException("User is not signed in.")
            )

        return collection.get()
    }

    // Delete one memory from cloud storage
    fun deleteMemory(
        memoryId: String
    ): Task<Void> {

        val collection = getMemoriesCollection()
            ?: return Tasks.forException(
                IllegalStateException("User is not signed in.")
            )

        return collection
            .document(memoryId)
            .delete()
    }

    // Delete all cloud memory records for the current user
    fun deleteAllMemories(): Task<Void> {

        val collection = getMemoriesCollection()
            ?: return Tasks.forException(
                IllegalStateException("User is not signed in.")
            )

        return collection.get().continueWithTask { task ->

            if (!task.isSuccessful) {
                throw task.exception
                    ?: Exception("Unable to retrieve memories.")
            }

            val batch = firestore.batch()

            task.result?.documents?.forEach { document ->
                batch.delete(document.reference)
            }

            batch.commit()
        }
    }
}
