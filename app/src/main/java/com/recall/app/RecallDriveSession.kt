package com.recall.app

internal object RecallDriveSession {

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var accountEmail: String? = null
        private set

    @Volatile
    var folderId: String? = null
        private set

    val isConnected: Boolean
        get() = !accessToken.isNullOrBlank() &&
                !accountEmail.isNullOrBlank() &&
                !folderId.isNullOrBlank()

    fun connect(
        token: String,
        email: String,
        recallFolderId: String
    ) {
        accessToken = token
        accountEmail = email
        folderId = recallFolderId
    }

    fun disconnect() {
        accessToken = null
        accountEmail = null
        folderId = null
    }
}