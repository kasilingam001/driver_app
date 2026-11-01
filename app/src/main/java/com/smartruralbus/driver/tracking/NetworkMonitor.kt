package com.smartruralbus.driver.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Tells the tracking service when the phone gets (or loses) an internet connection, so buffered points are
 * uploaded the moment the connection returns instead of waiting for the next retry.
 *
 * "Online" here only means Android reports a network with internet access; whether the server can really be
 * reached is found out by the upload itself.
 */
class NetworkMonitor(
    context: Context,
    private val onChanged: (online: Boolean) -> Unit,
) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChanged(true)
        override fun onLost(network: Network) = onChanged(isOnline())
    }

    fun isOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun start() {
        if (registered) return
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) { /* the retry timer still works without it */ }
    }

    fun stop() {
        if (!registered) return
        try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        registered = false
    }
}
