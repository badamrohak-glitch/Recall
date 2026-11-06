
package com.recall.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecallDriveAuthorizationResult(
    val accessToken: String,
    val accountEmail: String,
    val folderId: String
)

object RecallDriveAuthorization {

    private val driveScopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.file"),
        Scope("openid"),
        Scope("profile"),
        Scope("email")
    )

    suspend fun getAuthorization(
        context: Context
    ): RecallDriveAuthorizationResult? {

        val appContext = context.applicationContext

        val storage = RecallDriveStorage(appContext)

        // Retrieve the previously connected account.
        val savedEmail =
            storage.getConnectedAccountEmail()
                ?: return null

        // Request authorization without launching a screen.
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(driveScopes)
            .build()

        val result = Identity
            .getAuthorizationClient(appContext)
            .authorize(request)
            .awaitResult()

        // User interaction is required.
        // A background worker cannot launch this authorization screen.
        if (result.hasResolution()) {
            return null
        }

        val token = result.accessToken

        if (token.isNullOrBlank()) {
            return null
        }

        // Verify that Google returned the expected account.
        val actualEmail = fetchAccountEmail(token)

        if (!actualEmail.equals(savedEmail, ignoreCase = true)) {
            return null
        }

        // Retrieve or create the Recall folder.
        val folderId = storage.getOrCreateRecallFolder(
            accessToken = token,
            accountEmail = actualEmail
        )

        if (folderId.isBlank()) {
            return null
        }

        return RecallDriveAuthorizationResult(
            accessToken = token,
            accountEmail = actualEmail,
            folderId = folderId
        )
    }

    private suspend fun fetchAccountEmail(
        accessToken: String
    ): String = withContext(Dispatchers.IO) {

        val connection = URL(
            "https://www.googleapis.com/oauth2/v3/userinfo"
        ).openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(
                    "Unable to verify Google account. HTTP $responseCode"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val userInfo = JSONObject(response)

            val email = userInfo.optString("email", "")
            val verified = userInfo.optBoolean(
                "email_verified",
                false
            )

            if (email.isBlank() || !verified) {
                throw Exception(
                    "Google account email could not be verified."
                )
            }

            email

        } finally {
            connection.disconnect()
        }
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
                            ?: Exception(
                                "Google Drive authorization failed."
                            )
                    )
                }
            }
        }
}
