package com.recall.app

object RecallPhotosSession {

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var accountEmail: String? = null
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(token: String, email: String) {
        accessToken = token
        accountEmail = email
        isConnected = token.isNotBlank()
    }

    fun disconnect() {
        accessToken = null
        accountEmail = null
        isConnected = false
    }
}