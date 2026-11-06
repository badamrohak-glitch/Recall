
package com.recall.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RecallSyncManager(context: Context) {

    private val prefs = context.getSharedPreferences(
        "recall_sync_queue",
        Context.MODE_PRIVATE
    )

    private val cloudManager = RecallCloudManager()
    private val authManager = RecallAuthManager()

    private fun queueKey(): String? {
        val uid = authManager.getCurrentUser()?.uid ?: return null
        return "pending_$uid"
    }

    fun queueSave(
        memoryId: String,
        metadata: Map<String, Any?>
    ): Boolean {
        val key = queueKey() ?: return false
        val queue = getQueue(key)

        val operation = JSONObject().apply {
            put("type", "save")
            put("memoryId", memoryId)
            put("metadata", JSONObject(metadata))
        }

        removeExisting(queue, memoryId)

        queue.put(operation)
        saveQueue(key, queue)

        return true
    }

    fun queueDelete(memoryId: String): Boolean {
        val key = queueKey() ?: return false
        val queue = getQueue(key)

        removeExisting(queue, memoryId)

        val operation = JSONObject().apply {
            put("type", "delete")
            put("memoryId", memoryId)
        }

        queue.put(operation)
        saveQueue(key, queue)

        return true
    }

    fun getPendingCount(): Int {
        val key = queueKey() ?: return 0
        return getQueue(key).length()
    }

    private fun getQueue(key: String): JSONArray {
        return try {
            JSONArray(prefs.getString(key, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveQueue(key: String, queue: JSONArray) {
        prefs.edit()
            .putString(key, queue.toString())
            .apply()
    }

    private fun removeExisting(
        queue: JSONArray,
        memoryId: String
    ) {
        val updated = JSONArray()

        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue

            if (item.optString("memoryId") != memoryId) {
                updated.put(item)
            }
        }

        for (i in 0 until updated.length()) {
            queue.remove(i)
        }

        while (queue.length() > 0) {
            queue.remove(0)
        }

        for (i in 0 until updated.length()) {
            queue.put(updated.getJSONObject(i))
        }
    }

    fun syncPending(
        onComplete: (successful: Int, remaining: Int) -> Unit
    ) {
        val key = queueKey()

        if (key == null) {
            onComplete(0, 0)
            return
        }

        val queue = getQueue(key)

        if (queue.length() == 0) {
            onComplete(0, 0)
            return
        }

        syncNext(key, 0, 0, onComplete)
    }

    private fun syncNext(
        key: String,
        successful: Int,
        index: Int,
        onComplete: (Int, Int) -> Unit
    ) {
        val queue = getQueue(key)

        if (queue.length() == 0) {
            onComplete(successful, 0)
            return
        }

        val operation = queue.optJSONObject(0)

        if (operation == null) {
            queue.remove(0)
            saveQueue(key, queue)
            syncNext(key, successful, index + 1, onComplete)
            return
        }

        val memoryId = operation.optString("memoryId")
        val type = operation.optString("type")

        val task = when (type) {
            "save" -> {
                val metadataObject =
                    operation.optJSONObject("metadata") ?: JSONObject()

                val metadata = mutableMapOf<String, Any?>()

                val keys = metadataObject.keys()
                while (keys.hasNext()) {
                    val field = keys.next()
                    metadata[field] = metadataObject.get(field)
                }

                cloudManager.saveMemory(memoryId, metadata)
            }

            "delete" -> cloudManager.deleteMemory(memoryId)

            else -> {
                queue.remove(0)
                saveQueue(key, queue)
                syncNext(key, successful, index + 1, onComplete)
                return
            }
        }

        task
            .addOnSuccessListener {
                val latestQueue = getQueue(key)

                if (latestQueue.length() > 0) {
                    latestQueue.remove(0)
                    saveQueue(key, latestQueue)
                }

                syncNext(
                    key,
                    successful + 1,
                    index + 1,
                    onComplete
                )
            }
            .addOnFailureListener {
                onComplete(successful, getQueue(key).length())
            }
    }
}
