
package com.recall.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object RecallPhotosStorage {

    private const val UPLOAD_URL =
        "https://photoslibrary.googleapis.com/v1/uploads"

    private const val CREATE_URL =
        "https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate"

    suspend fun uploadImage(
        imageFile: File,
        accessToken: String
    ): String = withContext(Dispatchers.IO) {

        require(imageFile.exists() && imageFile.isFile) {
            "The selected image could not be found."
        }

        require(accessToken.isNotBlank()) {
            "Google Photos is not connected. Please connect your account again."
        }

        val mimeType = getMimeType(imageFile)

        // STEP 1: Upload image bytes and receive an upload token.

        val uploadConnection =
            (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"
                doOutput = true

                connectTimeout = 30_000
                readTimeout = 60_000

                setRequestProperty(
                    "Authorization",
                    "Bearer $accessToken"
                )

                setRequestProperty(
                    "Content-Type",
                    "application/octet-stream"
                )

                setRequestProperty(
                    "X-Goog-Upload-Content-Type",
                    mimeType
                )

                setRequestProperty(
                    "X-Goog-Upload-Protocol",
                    "raw"
                )
            }

        val uploadToken: String

        try {

            uploadConnection.outputStream.use { output ->

                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            val responseCode = uploadConnection.responseCode
            val responseText = readResponse(uploadConnection)

            if (responseCode !in 200..299) {
                throw Exception(
                    getGoogleError(
                        responseText,
                        "Google Photos could not upload this image."
                    )
                )
            }

            uploadToken = responseText.trim()

            if (uploadToken.isBlank()) {
                throw Exception(
                    "Google Photos did not return an upload confirmation."
                )
            }

        } finally {
            uploadConnection.disconnect()
        }

        // STEP 2: Create the image in Google Photos.

        val newMediaItem = JSONObject().apply {

            put(
                "description",
                "Backed up from Recall"
            )

            put(
                "simpleMediaItem",
                JSONObject().apply {

                    put("uploadToken", uploadToken)
                    put("fileName", imageFile.name)

                }
            )
        }

        val requestBody = JSONObject().apply {

            put(
                "newMediaItems",
                JSONArray().put(newMediaItem)
            )
        }

        val createConnection =
            (URL(CREATE_URL).openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"
                doOutput = true

                connectTimeout = 30_000
                readTimeout = 60_000

                setRequestProperty(
                    "Authorization",
                    "Bearer $accessToken"
                )

                setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
            }

        try {

            createConnection.outputStream.use { output ->

                output.write(
                    requestBody.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val responseCode = createConnection.responseCode
            val responseText = readResponse(createConnection)

            if (responseCode !in 200..299) {
                throw Exception(
                    getGoogleError(
                        responseText,
                        "Google Photos could not save this image."
                    )
                )
            }

            val responseJson = JSONObject(responseText)

            val results =
                responseJson.optJSONArray("newMediaItemResults")

            if (results == null || results.length() == 0) {
                throw Exception(
                    "Google Photos did not confirm that the image was saved."
                )
            }

            val result = results.getJSONObject(0)
            val status = result.optJSONObject("status")

            if (status != null && status.optInt("code", 0) != 0) {

                throw Exception(
                    status.optString(
                        "message",
                        "Google Photos could not save this image."
                    )
                )
            }

            val mediaItem = result.optJSONObject("mediaItem")
            val mediaItemId = mediaItem?.optString("id").orEmpty()

            if (mediaItemId.isBlank()) {
                throw Exception(
                    "The image upload was not confirmed by Google Photos."
                )
            }

            mediaItemId

        } finally {
            createConnection.disconnect()
        }
    }

    private fun getMimeType(file: File): String {

        return when (file.extension.lowercase()) {

            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"

            else -> throw Exception(
                "This image format is not supported for Google Photos backup."
            )
        }
    }

    private fun readResponse(
        connection: HttpURLConnection
    ): String {

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return stream?.bufferedReader()?.use {
            it.readText()
        }.orEmpty()
    }

    private fun getGoogleError(
        response: String,
        fallback: String
    ): String {

        return try {

            JSONObject(response)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: fallback

        } catch (_: Exception) {
            fallback
        }
    }
}
