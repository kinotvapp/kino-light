package com.arkiv.player.ui.player

import com.arkiv.player.data.DituEntities
import com.arkiv.player.playback.DituLive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Caracol live channel leaves no rows in `playback`, and everything else gets logged same as
 * before. See [shouldLogHistory] (progress and frames) and [shouldMarkInProgress] (on opening).
 */
class LiveCaracolNotLoggedTest {

    private val live = "${DituLive.PREFIX}5"
    private val magis = "magis:2AD2591D4242471D96B68FF04FFD2784::e6"
    private val caracolVod = DituEntities.movieEpisodeId(DituEntities.itemIdFor("42"))

    /** No playlist: what's left while Caracol plays, because `loadDitu` leaves it at null. */
    private val noPlaylist: PlaylistData? = null

    @Test fun `a Caracol live channel's progress does not get logged`() {
        assertFalse(noPlaylist.shouldLogHistory(live))
    }

    @Test fun `Magis and a Caracol VOD's progress get logged as before`() {
        assertTrue(noPlaylist.shouldLogHistory(magis))
        assertTrue(noPlaylist.shouldLogHistory(caracolVod))
    }

    @Test fun `a Caracol live channel is not marked in progress`() {
        assertFalse(shouldMarkInProgress(live, adult = null))
    }

    @Test fun `Magis and a Caracol VOD are marked in progress as before`() {
        assertTrue(shouldMarkInProgress(magis, adult = null))
        assertTrue(shouldMarkInProgress(caracolVod, adult = null))
        // And adult content still doesn't get marked: the old rule didn't change.
        assertFalse(shouldMarkInProgress(magis, adult = true))
    }
}
