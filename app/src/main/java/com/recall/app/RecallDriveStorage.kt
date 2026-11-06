package com.recall.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RecallDriveStorage(context: Context) {

    private val preferences = context.getSharedPreferences(
        "recall_drive_storage",
        Context.MODE_PRIVATE
    )

    // Save the connected Google account email.
    fun saveConnectedAccountEmail(email: String) {
        preferences.edit()
            .putString("connected_account_email", email)
            .apply()
    }

    // Retrieve the connected Google account email.
    fun getConnectedAccountEmail(): String? {
        return preferences.getString(
            "connected_account_email",
            null
        )
    }

    // Clear the saved Google account email.
    fun clearConnectedAccountEmail() {
        preferences.edit()
            .remove("connected_account_email")
            .apply()
    }

    // Create or retrieve the user's private Recall folder.
    suspend fun getOrCreateRecallFolder(
        accessToken: String,
        accountEmail: String
    ): String = withContext(Dispatchers.IO) {

        require(accessToken.isNotBlank()) {
            "Google Drive access token is missing."
        }

        require(accountEmail.isNotBlank()) {
            "Google account email is missing."
        }

        val preferenceKey =
            "recall_folder_${accountEmail.lowercase()}"

        val savedFolderId = preferences.getString(
            preferenceKey,
            null
        )

        if (!savedFolderId.isNullOrBlank()) {
            return@withContext savedFolderId
        }

        val query = "name = 'Recall' and " +
                "mimeType = 'application/vnd.google-apps.folder' and " +
                "trashed = false"

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val searchUrl =
            "https://www.googleapis.com/drive/v3/files" +
                    "?q=$encodedQuery" +
                    "&spaces=drive" +
                    "&pageSize=100" +
                    "&fields=files(id,name,mimeType)"

        val searchResponse = makeRequest(
            url = searchUrl,
            method = "GET",
            accessToken = accessToken
        )

        val files = searchResponse.optJSONArray("files")

        if (files != null && files.length() > 0) {

            val folderId = files
                .getJSONObject(0)
                .getString("id")

            preferences.edit()
                .putString(preferenceKey, folderId)
                .apply()

            return@withContext folderId
        }

        val folderData = JSONObject().apply {
            put("name", "Recall")
            put(
                "mimeType",
                "application/vnd.google-apps.folder"
            )
        }

        val createResponse = makeRequest(
            url = "https://www.googleapis.com/drive/v3/files",
            method = "POST",
            accessToken = accessToken,
            body = folderData.toString()
        )

        val newFolderId = createResponse.optString("id")

        if (newFolderId.isBlank()) {
            throw Exception(
                "Google Drive did not return the new folder ID."
            )
        }

        preferences.edit()
            .putString(preferenceKey, newFolderId)
            .apply()

        newFolderId
    }

    // Upload an image into the user's private Recall folder.
    suspend fun uploadImage(
        imageFile: File,
        accessToken: String,
        folderId: String
    ): String = withContext(Dispatchers.IO) {

        require(imageFile.exists()) {
            "Image file does not exist."
        }

        require(imageFile.isFile) {
            "The selected path is not a file."
        }

        require(accessToken.isNotBlank()) {
            "Google Drive access token is missing."
        }

        require(folderId.isNotBlank()) {
            "Recall folder ID is missing."
        }

        val mimeType = when (
            imageFile.extension.lowercase()
        ) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }

        val boundary = "RecallBoundary${System.currentTimeMillis()}"

        val metadata = JSONObject().apply {
            put("name", imageFile.name)
            put("mimeType", mimeType)
            put(
                "parents",
                org.json.JSONArray().put(folderId)
            )
        }

        val connection = URL(
            "https://www.googleapis.com/upload/drive/v3/files" +
                    "?uploadType=multipart" +
                    "&fields=id,name,mimeType"
        ).openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Content-Type",
                "multipart/related; boundary=$boundary"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            connection.outputStream.use { output ->

                // JSON metadata section.
                output.write(
                    "--$boundary\r\n".toByteArray(Charsets.UTF_8)
                )

                output.write(
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                        .toByteArray(Charsets.UTF_8)
                )

                output.write(
                    metadata.toString().toByteArray(Charsets.UTF_8)
                )

                output.write(
                    "\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8)
                )

                // Image data section.
                output.write(
                    "Content-Type: $mimeType\r\n\r\n"
                        .toByteArray(Charsets.UTF_8)
                )

                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }

                output.write(
                    "\r\n--$boundary--\r\n"
                        .toByteArray(Charsets.UTF_8)
                )

                output.flush()
            }

            val responseCode = connection.responseCode

            val responseStream = if (
                responseCode in 200..299
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""

            if (responseCode !in 200..299) {
                throw Exception(
                    "Google Drive upload failed. " +
                            "HTTP $responseCode: $responseText"
                )
            }

            val response = JSONObject(responseText)

            val uploadedFileId = response.optString("id")

            if (uploadedFileId.isBlank()) {
                throw Exception(
                    "Google Drive did not return the uploaded file ID."
                )
            }

            uploadedFileId

        } finally {
            connection.disconnect()
        }
    }

    // Common HTTP request helper.
    private fun makeRequest(
        url: String,
        method: String,
        accessToken: String,
        body: String? = null
    ): JSONObject {

        val connection = URL(url).openConnection()
                as HttpURLConnection

        try {

            connection.requestMethod = method

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (body != null) {

                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.outputStream.use { output ->
                    output.write(
                        body.toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val responseCode = connection.responseCode

            val responseStream = if (
                responseCode in 200..299
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""

            if (responseCode !in 200..299) {
                throw Exception(
                    "Google Drive request failed. " +
                            "HTTP $responseCode: $responseText"
                )
            }

            return if (responseText.isBlank()) {
                JSONObject()
            } else {
                JSONObject(responseText)
            }

        } finally {
            connection.disconnect()
        }
    }
}