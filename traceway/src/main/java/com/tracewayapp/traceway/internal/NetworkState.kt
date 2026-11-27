package com.tracewayapp.traceway.internal

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock

/**
 * Whether the device's connection is unmetered (Wi-Fi, Ethernet): what the
 * `wifi` replay image policy asks before a frame records pictures. Read as
 * [ConnectivityManager.isActiveNetworkMetered] says it; kept up to date by a
 * default-network callback on Android 7+, read again at most every
 * [POLL_MS] below that. Unknown counts as metered. Thread-safe.
 */
internal class NetworkState(context: Context) {
    private val manager: ConnectivityManager? = try {
        context.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (_: Throwable) {
        null
    }

    @Volatile
    private var lastUnmetered = false

    @Volatile
    private var readAtMs = Long.MIN_VALUE

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        lastUnmetered = read()
        readAtMs = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 24) listen()
    }

    /** Whether the active connection is unmetered now. */
    val unmetered: Boolean
        get() {
            if (callback == null) {
                val now = SystemClock.elapsedRealtime()
                if (now - readAtMs >= POLL_MS) {
                    readAtMs = now
                    lastUnmetered = read()
                }
            }
            return lastUnmetered
        }

    @SuppressLint("MissingPermission")
    private fun read(): Boolean = try {
        val m = manager
        m != null && m.activeNetworkInfoConnected() && !m.isActiveNetworkMetered
    } catch (_: Throwable) {
        // No ACCESS_NETWORK_STATE, or the service failed: assume metered.
        false
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun ConnectivityManager.activeNetworkInfoConnected(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) activeNetwork != null else activeNetworkInfo?.isConnected == true

    @SuppressLint("MissingPermission", "NewApi")
    private fun listen() {
        val m = manager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                lastUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    (Build.VERSION.SDK_INT >= 30 &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                lastUnmetered = read()
            }
        }
        try {
            m.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (_: Throwable) {
            // Too many callbacks registered by the app, or no permission: poll instead.
        }
    }

    /** Stops following the connection. */
    fun close() {
        val cb = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
