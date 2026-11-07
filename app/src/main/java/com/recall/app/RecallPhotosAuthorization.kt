
package com.recall.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
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

data class RecallPhotosAuthorizationResult(
    val accessToken: String,
    val accountEmail: String
)

object RecallPhotosAuthorization {

    private const val PREFS_NAME = "recall_photos_authorization"
    private const val EMAIL_KEY = "connected_account_email"

    private val photosScopes = listOf(
        Scope("https://www.googleapis.com/auth/photoslibrary.appendonly"),
        Scope("openid"),
        Scope("profile"),
        Scope("email")
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun saveConnectedAccountEmail(
        context: Context,
        email: String
    ) {
        preferences(context).edit()
            .putString(EMAIL_KEY, email)
            .apply()
    }

    fun getConnectedAccountEmail(
        context: Context
    ): String? {
        return preferences(context)
            .getString(EMAIL_KEY, null)
    }

    fun clearConnectedAccountEmail(
        context: Context
    ) {
        preferences(context).edit()
            .remove(EMAIL_KEY)
            .apply()
    }

    suspend fun getAuthorization(
        context: Context
    ): RecallPhotosAuthorizationResult? {

        val appContext = context.applicationContext

        val savedEmail =
            getConnectedAccountEmail(appContext)
                ?: return null

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(photosScopes)
            .build()

        val result = Identity
            .getAuthorizationClient(appContext)
            .authorize(request)
            .awaitResult()

        // Background workers cannot display authorization screens.
        if (result.hasResolution()) {
            return null
        }

        val token = result.accessToken

        if (token.isNullOrBlank()) {
            return null
        }

        val actualEmail = fetchAccountEmail(token)

        if (!actualEmail.equals(savedEmail, ignoreCase = true)) {
            return null
        }

        RecallPhotosSession.connect(
            token = token,
            email = actualEmail
        )

        return RecallPhotosAuthorizationResult(
            accessToken = token,
            accountEmail = actualEmail
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
                                "Google Photos authorization failed."
                            )
                    )
                }
            }
        }
}
