package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorRouteTest {
    private fun route(
        live: Boolean = false,
        liveInPlace: Boolean = false,
        drmError: Boolean = false,
        drmSoftwareRefused: Boolean = false,
        audio: Boolean = false,
        stuck: Boolean = false,
        stuckRetriesLeft: Int = 0,
        askHost: Boolean = false,
        network: Boolean = false,
    ) = playerErrorRoute(live, liveInPlace, drmError, drmSoftwareRefused, audio, stuck, stuckRetriesLeft, askableHost = askHost, networkRetry = network)

    // A request refused ONLY because its host is undeclared (and askable) is a question for the
    // person, not an audio track's fault, a live cut, a stuck re-prepare or a final error: asking comes first.
    @Test fun `an askable undeclared host is asked about before anything else is blamed`() {
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, audio = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, live = true, liveInPlace = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, live = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, stuck = true, stuckRetriesLeft = 2))
    }

    @Test fun `a VOD error with nothing to blame is final`() {
        assertEquals(PlayerErrorRoute.FINAL, route())
    }

    @Test fun `a VOD DRM error is final as DRM, even with audio tracks merged`() {
        assertEquals(PlayerErrorRoute.DRM_FINAL, route(drmError = true, audio = true))
        assertEquals(PlayerErrorRoute.DRM_FINAL, route(drmError = true, drmSoftwareRefused = true))
    }

    @Test fun `any other error blames the side audio tracks first`() {
        assertEquals(PlayerErrorRoute.DROP_AUDIO, route(audio = true))
        assertEquals(PlayerErrorRoute.DROP_AUDIO, route(live = true, audio = true))
    }

    @Test fun `a live playlist-level error is fixed in place before anything else`() {
        assertEquals(PlayerErrorRoute.LIVE_IN_PLACE, route(live = true, liveInPlace = true, audio = true, drmError = true))
    }

    @Test fun `a live channel's cut goes through its reopen budget, a license failure too`() {
        assertEquals(PlayerErrorRoute.LIVE_CUT, route(live = true))
        assertEquals(PlayerErrorRoute.LIVE_CUT, route(live = true, drmError = true))
    }

    /** No reopen can change a device that refuses Widevine L3: three reopens would end as "Se cortó la señal". */
    @Test fun `a live channel on a device that refused the software level is DRM-final at once`() {
        assertEquals(PlayerErrorRoute.DRM_FINAL, route(live = true, drmError = true, drmSoftwareRefused = true))
    }

    @Test fun `in-place is only for a live channel`() {
        assertEquals(PlayerErrorRoute.FINAL, route(liveInPlace = true))
    }

    @Test fun `a VOD player stuck with retries left is re-prepared in place, before anything is blamed`() {
        assertEquals(PlayerErrorRoute.STUCK_RETRY, route(stuck = true, stuckRetriesLeft = 2))
        assertEquals(PlayerErrorRoute.STUCK_RETRY, route(stuck = true, stuckRetriesLeft = 1, audio = true))
    }

    @Test fun `a stuck VOD player out of retries falls to the usual routes`() {
        assertEquals(PlayerErrorRoute.FINAL, route(stuck = true, stuckRetriesLeft = 0))
        assertEquals(PlayerErrorRoute.DROP_AUDIO, route(stuck = true, stuckRetriesLeft = 0, audio = true))
    }

    @Test fun `stuck retries are for VOD only and never for a DRM failure`() {
        assertEquals(PlayerErrorRoute.LIVE_CUT, route(live = true, stuck = true, stuckRetriesLeft = 2))
        assertEquals(PlayerErrorRoute.DRM_FINAL, route(drmError = true, stuck = true, stuckRetriesLeft = 2))
    }

    @Test fun `only the player's own stuck watchdog counts as stuck`() {
        val runtimeCheck = 1003
        assertEquals(true, isStuckPlayer(runtimeCheck, listOf("Unexpected runtime error", "Player stuck playing with no progress for 10000 ms")))
        assertEquals(true, isStuckPlayer(runtimeCheck, listOf("Player stuck buffering and not loading for 4000 ms")))
        assertEquals(false, isStuckPlayer(runtimeCheck, listOf("flush() is valid only at Executing states")))
        assertEquals(false, isStuckPlayer(2000, listOf("Player stuck playing with no progress for 10000 ms")))
    }

    // A lost connection takes the video and its side audio down together: it is recovered before
    // a dub is blamed for it, and an undeclared host or a DRM failure is still decided first.
    @Test fun `a VOD network error with attempts left is recovered before anything is blamed`() {
        assertEquals(PlayerErrorRoute.NETWORK_RETRY, route(network = true))
        assertEquals(PlayerErrorRoute.NETWORK_RETRY, route(network = true, audio = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(network = true, askHost = true))
        assertEquals(PlayerErrorRoute.DRM_FINAL, route(network = true, drmError = true))
    }

    @Test fun `network recovery is never a live channel's, whose reopen budget decides`() {
        assertEquals(PlayerErrorRoute.LIVE_CUT, route(network = true, live = true))
        assertEquals(PlayerErrorRoute.LIVE_IN_PLACE, route(network = true, live = true, liveInPlace = true))
    }
}
