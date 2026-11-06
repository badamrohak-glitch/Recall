
package com.recall.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class RecallNetworkMonitor(
    context: Context,
    private val onConnectionChanged: (Boolean) -> Unit
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

    private var registered = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                onConnectionChanged(isInternetAvailable())
            }

            override fun onLost(network: Network) {
                onConnectionChanged(isInternetAvailable())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                onConnectionChanged(isInternetAvailable())
            }
        }

    fun isInternetAvailable(): Boolean {

        val network =
            connectivityManager.activeNetwork ?: return false

        val capabilities =
            connectivityManager.getNetworkCapabilities(network)
                ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) && capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
    }

    fun start() {

        if (registered) return

        val request = NetworkRequest.Builder()
            .addCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            .build()

        try {
            connectivityManager.registerNetworkCallback(
                request,
                callback
            )

            registered = true
        } catch (_: Exception) {
            registered = false
        }

        onConnectionChanged(isInternetAvailable())
    }

    fun stop() {

        if (!registered) return

        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }

        registered = false
    }
}
