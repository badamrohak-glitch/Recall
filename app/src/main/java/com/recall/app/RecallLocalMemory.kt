
package com.recall.app

import android.content.Context

object RecallLocalMemory {

    private const val PREFS_NAME = "recall_library"

    private val fields = listOf(
        "title",
        "category",
        "note",
        "tags"
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun getMetadata(
        context: Context,
        memoryId: String
    ): Map<String, Any?> {

        val prefs = preferences(context)
        val result = mutableMapOf<String, Any?>()

        for (field in fields) {
            result[field] = prefs.getString(
                "${memoryId}_$field",
                ""
            ) ?: ""
        }

        return result
    }

    fun getAllMetadata(
        context: Context
    ): Map<String, Map<String, Any?>> {

        val prefs = preferences(context)
        val result = mutableMapOf<String, Map<String, Any?>>()

        val memoryIds = prefs.all.keys
            .mapNotNull { key ->
                val field = fields.firstOrNull {
                    key.endsWith("_$it")
                }

                field?.let {
                    key.removeSuffix("_$it")
                }
            }
            .distinct()

        for (memoryId in memoryIds) {
            result[memoryId] = getMetadata(
                context,
                memoryId
            )
        }

        return result
    }
}
