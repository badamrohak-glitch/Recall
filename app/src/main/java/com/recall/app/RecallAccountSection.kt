
package com.recall.app

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@Composable
fun RecallAccountSection() {

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val googleAuth = remember {
        RecallGoogleAuth(context)
    }

    val firebaseAuth = remember {
        FirebaseAuth.getInstance()
    }

    var currentEmail by remember {
        mutableStateOf(firebaseAuth.currentUser?.email)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("")
    }

    DisposableEffect(firebaseAuth) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentEmail = auth.currentUser?.email
        }

        firebaseAuth.addAuthStateListener(listener)

        onDispose {
            firebaseAuth.removeAuthStateListener(listener)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = "Recall Account",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Sign in to access your private cloud backup."
            )

            if (currentEmail == null) {

                Text("Not signed in")

                Button(
                    onClick = {
                        if (activity == null) {
                            status = "Unable to open Google Sign-in."
                            return@Button
                        }

                        scope.launch {
                            loading = true
                            status = ""

                            try {
                                googleAuth.signIn(activity)
                                status = "Google account connected."
                            } catch (e: Exception) {
                                status = e.localizedMessage
                                    ?: "Sign-in failed."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (loading) "Signing in..."
                        else "Continue with Google"
                    )
                }

            } else {

                Text("Signed in as")

                Text(
                    text = currentEmail ?: "",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        scope.launch {
                            loading = true

                            try {
                                googleAuth.signOut()
                                status = "Signed out successfully."
                            } catch (e: Exception) {
                                status = e.localizedMessage
                                    ?: "Sign-out failed."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign out")
                }
            }

            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
