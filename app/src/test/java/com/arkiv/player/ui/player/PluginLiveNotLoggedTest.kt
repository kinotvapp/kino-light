package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.DituLive
import com.arkiv.player.playback.PlayerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's live channel leaves no rows in `playback`: no resume position, no "Continuar viendo",
 * no captured frame, no "in progress" mark on opening. A plugin movie or chapter keeps being logged
 * exactly as before. See [shouldLogHistory], [shouldMarkInProgress] and [savesProgress].
 */
class PluginLiveNotLoggedTest {

    private val live = PluginIds.liveEpisodeId("demo", "canal-1")
    private val movie = "plugin:demo:m1::0"
    private val chapter = "plugin:demo:s1::t2e3"

    /** What's left while a plugin plays: `loadPlugin` leaves the playlist at null (the item is in `_magisItem`). */
    private val noPlaylist: PlaylistData? = null

    @Test fun `a plugin live channel's progress does not get logged`() {
        assertFalse(noPlaylist.shouldLogHistory(live))
        assertFalse(savesProgress(live, adult = null))
        assertFalse(savesProgress(live, adult = false))
    }

    @Test fun `every live channel refuses the magis-slot branch too`() {
        assertFalse(savesProgress("${PlayerSource.LIVE_PREFIX}caracoltv", adult = null))
        assertFalse(savesProgress("${DituLive.PREFIX}5", adult = null))
    }

    @Test fun `a plugin movie or chapter's progress gets logged as before`() {
        assertTrue(noPlaylist.shouldLogHistory(movie))
        assertTrue(noPlaylist.shouldLogHistory(chapter))
        assertTrue(savesProgress(movie, adult = null))
        assertTrue(savesProgress(chapter, adult = false))
        // And adult content still doesn't get saved: the old rule didn't change.
        assertFalse(savesProgress(movie, adult = true))
    }

    @Test fun `a plugin live channel is not marked in progress on opening`() {
        assertFalse(shouldMarkInProgress(live, adult = null))
        assertTrue(shouldMarkInProgress(movie, adult = null))
    }
}
