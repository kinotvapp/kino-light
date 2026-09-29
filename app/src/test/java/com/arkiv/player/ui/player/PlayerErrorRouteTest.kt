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
        askHost: Boolean = false,
    ) = playerErrorRoute(live, liveInPlace, drmError, drmSoftwareRefused, audio, askHost)

    // A request refused ONLY because its host is undeclared (and askable) is a question for the
    // person, not an audio track's fault, a live cut or a final error: asking comes first.
    @Test fun `an askable undeclared host is asked about before anything else is blamed`() {
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, audio = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, live = true, liveInPlace = true))
        assertEquals(PlayerErrorRoute.ASK_HOST, route(askHost = true, live = true))
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
}
