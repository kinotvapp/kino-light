package com.arkiv.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device has a network that actually reaches the internet: the default network, with
 * INTERNET and VALIDATED (Android's own probe got through). A Wi-Fi that dropped, or one that is
 * still associating, is not. The Android shell only; what to do about it is [com.arkiv.player.ui.player.VodNetworkRecovery]'s.
 *
 * Anything that fails here (no ConnectivityManager, the system's callback limit) reads as "up":
 * never knowing is the previous behaviour, where nobody waited for the network.
 */
object ValidatedNetwork {

    fun isUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        isUp(cm.getNetworkCapabilities(cm.activeNetwork ?: return false))
    }.getOrDefault(true)

    private fun isUp(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    /** [isUp] now and on every change of the default network, until the collector stops. */
    fun states(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isUp(caps))
            }

            // The default network went away; a new default one reports its own capabilities next.
            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(isUp(context))
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) trySend(true)
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
