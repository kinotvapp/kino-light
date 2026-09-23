package com.arkiv.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Notifies when the device switches networks, so the delivery layer abandons whatever was left
 * tied to the previous one.
 *
 * It's the Android shell and nothing more: ALL the decision lives in [NetworkTracker], which is
 * pure and covered by tests. Here only the callback gets registered and `Network` gets translated
 * to `networkHandle`.
 *
 * Listens to the DEFAULT network (`registerDefaultNetworkCallback`) and not all of them: the one
 * that matters is exactly the one our sockets go out through, which is that one.
 *
 * Why it exists: until now there wasn't a single `ConnectivityManager` in the app, so switching
 * from WiFi to mobile data mid-playback left the old socket hanging against an interface that no
 * longer exists, and the read waited out [OriginPolicy]'s BODY deadline -90 s in archive, 30 s in
 * magis- before even the first retry began. See [NetworkChange].
 *
 * Ported from magis's original app's system-status reporting, which sends its delivery engine
 * network changes alongside screen, foreground and Doze (`dd/AbstractC3049d`).
 */
class NetworkWatchdog(
    private val context: Context,
    private val onNetworkChanged: (reason: String) -> Unit,
) {

    private val tracker = NetworkTracker()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (tracker.onAppeared(network.networkHandle)) {
                onNetworkChanged("network changed")
            }
        }

        override fun onLost(network: Network) {
            if (tracker.onNetworkLost(network.networkHandle)) {
                onNetworkChanged("network lost")
            }
        }
    }

    /**
     * Starts listening. Wrapped because `registerDefaultNetworkCallback` can throw
     * (`SecurityException` without the permission, or the system's callback limit), and being left
     * without a watchdog is exactly the previous behaviour: worse, but not broken.
     */
    fun start() {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(callback)
            android.util.Log.w("ArkivNetwork", "network watchdog active")
        }.onFailure {
            android.util.Log.w("ArkivNetwork", "couldn't watch the network: ${it.message}")
        }
    }

    fun stop() {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }
}
