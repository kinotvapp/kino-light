package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceTest {
    @Test fun `an id with no known prefix is an unknown source`() {
        assertEquals(SourceKind.UNKNOWN, PlayerSource.kindFor("some-old-archive-identifier"))
    }

    @Test fun ditu_prefix_is_ditu() {
        assertEquals(SourceKind.DITU, PlayerSource.kindFor("ditu:12345::e1"))
    }

    @Test fun magis_prefix_is_still_magis() {
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }

    // --- is it a live channel? ------------------------------------------------------------------

    @Test fun magis_live_is_a_live_channel() {
        assertTrue(PlayerSource.isLiveChannel("${PlayerSource.LIVE_PREFIX}caracoltv"))
    }

    /** The case that was missing: a Caracol channel came out with a movie's progress bar. */
    @Test fun caracol_live_is_a_live_channel() {
        assertTrue(PlayerSource.isLiveChannel("${DituLive.PREFIX}12345"))
    }

    @Test fun caracol_vod_is_not_a_live_channel() {
        assertFalse(PlayerSource.isLiveChannel("ditu:12345::e1"))
    }

    @Test fun magis_vod_is_not_a_live_channel() {
        assertFalse(PlayerSource.isLiveChannel("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
        assertFalse(PlayerSource.isLiveChannel("someitem::3"))
    }
}
