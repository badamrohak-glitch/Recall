
package com.recall.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RecallGoogleAuth(context: Context) {

    private val appContext = context.applicationContext

    private val credentialManager =
        CredentialManager.create(appContext)

    private val auth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isSignedIn: Boolean
        get() = auth.currentUser != null

    suspend fun signIn(activity: Activity): FirebaseUser {

        val webClientId =
            activity.getString(R.string.default_web_client_id)

        // Show all available Google accounts.
        // Do not automatically select the previous account.
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setServerClientId(webClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        // Open the Google account chooser.
        val response = credentialManager.getCredential(
            context = activity,
            request = request
        )

        val credential = response.credential

        if (
            credential !is CustomCredential ||
            credential.type !=
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException(
                "Google account credential was not returned."
            )
        }

        // Read the selected Google account.
        val googleCredential =
            GoogleIdTokenCredential.createFrom(credential.data)

        // Authenticate the selected account with Firebase.
        val firebaseCredential =
            GoogleAuthProvider.getCredential(
                googleCredential.idToken,
                null
            )

        val result =
            auth.signInWithCredential(firebaseCredential).awaitResult()

        return result.user
            ?: throw IllegalStateException(
                "Firebase sign-in completed without a user."
            )
    }

    suspend fun signOut() {

        // Sign out from Firebase.
        auth.signOut()

        // Clear the Credential Manager session.
        try {
            credentialManager.clearCredentialState(
                ClearCredentialStateRequest()
            )
        } catch (_: Exception) {
            // Firebase sign-out has already completed.
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
                            ?: Exception("Authentication failed.")
                    )
                }
            }
        }
}
