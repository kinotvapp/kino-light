package com.arkiv.player.playback

import org.junit.Test

/**
 * When a network change leaves the connections that were already open DEAD.
 *
 * Today the app knows nothing about it: there isn't a single `ConnectivityManager` anywhere in the
 * code. And the price is measured in [OriginPolicy]'s own constants: the BODY read deadline is
 * 90 s on archive and 30 s on magis, on purpose -- "a network hiccup mid-playback recovers on its
 * own, cutting off fast here breaks the film". That reasoning holds for a hiccup. When the phone
 * switches from WiFi to mobile data, the old socket was left tied to an interface that no longer
 * exists: it will never recover, and those 90 s are 90 s of frozen picture before even the first
 * retry begins.
 *
 * The original app reports this to its delivery engine: `SYS_EVENT_TYPE_NET` with
 * `wired`/`wlan`/`cellular` (`vc/EnumC5904d` + `dd/AbstractC3049d`), alongside screen, foreground
 * and Doze.
 *
 * It's a pure function because the failure mode is expensive both ways: being too sensitive cuts
 * healthy connections on every blip of `onCapabilitiesChanged` (which Android fires all the time),
 * and being too lenient leaves the hang this exists to kill.
 */
class NetworkChangeTest {

    /** Network identifiers (`Network.networkHandle`); the value doesn't matter, only that they differ. */
    private val wifi = 100L
    private val cellular = 200L

    @Test fun `switching from one network to another invalidates what was open`() {
        assert(NetworkChange.invalidatesConnections(previous = wifi, current = cellular))
        assert(NetworkChange.invalidatesConnections(previous = cellular, current = wifi))
    }

    /** Losing the network entirely: what was open is dead and has to stop being waited on. */
    @Test fun `losing the network invalidates what was open`() {
        assert(NetworkChange.invalidatesConnections(previous = wifi, current = null))
    }

    /**
     * The FIRST network ever seen invalidates nothing: there was no connection running against
     * another one. Without this, starting the app would close the connections of the very first playback.
     */
    @Test fun `the first network ever seen invalidates nothing`() {
        assert(!NetworkChange.invalidatesConnections(previous = null, current = wifi))
        assert(!NetworkChange.invalidatesConnections(previous = null, current = null))
    }

    /**
     * The same notice repeated doesn't count. `onCapabilitiesChanged` fires constantly -every
     * signal change, every internet validation, every metered flag- and they all arrive with the
     * SAME network. Reacting to each one would cut the download every few seconds.
     */
    @Test fun `the same notice repeated invalidates nothing`() {
        assert(!NetworkChange.invalidatesConnections(previous = wifi, current = wifi))
    }

    /** A blip (the SAME network is lost and comes back) invalidates only once, on losing it. */
    @Test fun `a blip of the same network invalidates only on losing it`() {
        assert(NetworkChange.invalidatesConnections(previous = wifi, current = null))
        // And on coming back, the previous state is already "no network": it doesn't cut again.
        assert(!NetworkChange.invalidatesConnections(previous = null, current = wifi))
    }
}

/**
 * Tracking which network we're on right now, which is where the subtleties live.
 *
 * Kept apart from `ConnectivityManager` on purpose: the Android part is registering a callback
 * (four lines, nothing to decide) and the part that can get it wrong is this one -- especially the
 * `onLost` of a network that was NOT the one we were using, which arrives all the same and mustn't
 * cut anything off.
 */
class NetworkTrackerTest {

    private val wifi = 100L
    private val cellular = 200L

    @Test fun `the first WiFi to appear cuts nothing`() {
        assert(!NetworkTracker().onAppeared(wifi))
    }

    @Test fun `switching from WiFi to cellular cuts`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        assert(t.onAppeared(cellular))
    }

    @Test fun `the same notice repeated does not cut`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        assert(!t.onAppeared(wifi))
        assert(!t.onAppeared(wifi))
    }

    @Test fun `losing the network we were using cuts`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        assert(t.onNetworkLost(wifi))
    }

    /**
     * THE SUBTLETY. Android reports the `onLost` of ANY network that drops, not just the one we're
     * using: with mobile data active and WiFi turning off in the background, an `onLost(wifi)`
     * arrives while playback over cellular is going perfectly. Cutting off there would be cutting
     * off just in case.
     */
    @Test fun `losing ANOTHER network cuts nothing`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        t.onAppeared(cellular)   // now we're on cellular
        assert(!t.onNetworkLost(wifi))
    }

    /** After losing it, coming back to the same network doesn't cut: nothing was left open against it anymore. */
    @Test fun `coming back after losing it does not cut`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        t.onNetworkLost(wifi)
        assert(!t.onAppeared(wifi))
    }

    /** Losing the same network twice (duplicate notices) cuts only once. */
    @Test fun `losing it twice cuts only once`() {
        val t = NetworkTracker()
        t.onAppeared(wifi)
        assert(t.onNetworkLost(wifi))
        assert(!t.onNetworkLost(wifi))
    }
}
