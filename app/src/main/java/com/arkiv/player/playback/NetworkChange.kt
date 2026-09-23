package com.arkiv.player.playback

/**
 * When a network change leaves DEAD the connections to the origin that were already open.
 *
 * Until now the app knew nothing about it: there wasn't a single `ConnectivityManager` in the
 * code. And the price is written into [OriginPolicy]'s constants: the BODY read deadline is 90 s
 * on archive and 30 s on magis, and it's generous ON PURPOSE -- "a network hiccup mid-playback
 * recovers on its own; cutting off fast here fixes nothing, it breaks the film".
 *
 * That reasoning holds for a hiccup. It doesn't hold when the phone switches from WiFi to mobile
 * data: there, the old socket was left tied to an interface that no longer exists and will never
 * recover, so those 90 s are 90 s of frozen picture BEFORE even the first retry begins. The
 * difference between the two situations can't be told apart from inside the read -- the network
 * itself has to be watched.
 *
 * Ported from the original app's system-status reporting, which notifies its delivery engine of
 * network changes (`SYS_EVENT_TYPE_NET` with `wired`/`wlan`/`cellular`, in `vc/EnumC5904d` and
 * `dd/AbstractC3049d`) alongside screen, foreground and Doze.
 *
 * Pure function because the failure mode is expensive both ways: being too sensitive cuts healthy
 * connections on every `onCapabilitiesChanged` (which Android fires constantly), and being too
 * lenient leaves the hang this exists to kill.
 */
object NetworkChange {

    /**
     * Whether switching from network [previous] to network [current] invalidates what was already open.
     *
     * The identifiers are `Network.networkHandle`; null is "no network". Compared by identity and
     * not by transport TYPE on purpose: switching from one WiFi to another also kills the sockets,
     * and by transport that wouldn't show.
     */
    fun invalidatesConnections(previous: Long?, current: Long?): Boolean {
        // The first network we see invalidates nothing: nothing was running against another one.
        if (previous == null) return false
        return previous != current
    }
}

/**
 * Tracks which network we're currently going out through and answers, on every notice, whether
 * what was open has to be abandoned.
 *
 * Lives apart from `ConnectivityManager` on purpose: the Android part is registering a callback --
 * four lines, nothing to decide -- and the part that can get it wrong is this one. Especially the
 * `onLost` of a network we were NOT using: Android reports the drop of ANY network, so with mobile
 * data running and WiFi turning off in the background an `onLost(wifi)` arrives while playback is
 * going perfectly. Cutting off there would be cutting off just in case.
 *
 * Not thread-safe and doesn't need to be: network callbacks all arrive on the same `Handler`.
 */
class NetworkTracker {

    private var current: Long? = null

    /** Network [network] appeared (or switched to). Returns whether that invalidates what was open. */
    fun onAppeared(network: Long): Boolean {
        val invalidates = NetworkChange.invalidatesConnections(current, network)
        current = network
        return invalidates
    }

    /**
     * Network [network] was lost. Returns whether that invalidates what was open -- i.e. only when
     * the one lost was the one we were using.
     */
    fun onNetworkLost(network: Long): Boolean {
        if (current != network) return false
        val invalidates = NetworkChange.invalidatesConnections(current, null)
        current = null
        return invalidates
    }
}
