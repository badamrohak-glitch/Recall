
package com.recall.app

import android.app.Activity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class RecallDriveManager {

    fun requestDriveAccess(activity: Activity) {

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope("https://www.googleapis.com/auth/drive.file")
                )
            )
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->

                if (result.hasResolution()) {
                    // Authorization requires user interaction.
                } else {
                    // Authorization completed.
                }

            }
            .addOnFailureListener { error ->
                error.printStackTrace()
            }
    }
}
