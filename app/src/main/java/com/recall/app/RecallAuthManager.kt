
package com.recall.app

import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.tasks.Task

class RecallAuthManager {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    // Currently signed-in user
    fun getCurrentUser() = auth.currentUser

    // Check whether a user is signed in
    fun isLoggedIn(): Boolean {
        return auth.currentUser != null
    }

    // Register using email and password
    fun register(
        email: String,
        password: String
    ): Task<AuthResult> {

        return auth.createUserWithEmailAndPassword(
            email.trim(),
            password
        )
    }

    // Sign in using email and password
    fun login(
        email: String,
        password: String
    ): Task<AuthResult> {

        return auth.signInWithEmailAndPassword(
            email.trim(),
            password
        )
    }

    // Sign in using a Google ID token
    fun signInWithGoogle(
        idToken: String
    ): Task<AuthResult> {

        val credential = GoogleAuthProvider
            .getCredential(idToken, null)

        return auth.signInWithCredential(credential)
    }

    // Send password reset email
    fun resetPassword(
        email: String
    ): Task<Void> {

        return auth.sendPasswordResetEmail(
            email.trim()
        )
    }

    // Sign out
    fun logout() {
        auth.signOut()
    }

    // Delete the currently signed-in account
    fun deleteAccount(): Task<Void>? {
        return auth.currentUser?.delete()
    }
}
