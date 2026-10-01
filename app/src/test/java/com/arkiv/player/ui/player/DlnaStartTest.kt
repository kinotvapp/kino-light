package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a DLNA cast starts: the Chromecast's rule, and nothing for a live channel. */
class DlnaStartTest {

    private fun item(kind: SourceKind, startMs: Long = 0L, episodeId: String = "ep1") = PlayerData(
        episodeId = episodeId, itemId = "i", title = "T", subtitle = "",
        mediaUrl = "http://127.0.0.1:1/t/x/stream", castUrl = null, artworkUrl = "",
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = kind, startPositionMs = startMs,
    )

    @Test
    fun `a movie starts where the phone is`() {
        assertEquals(1_380_000, dlnaStartMs(item(SourceKind.MAGIS), livePositionMs = 1_380_000))
    }

    @Test
    fun `a player that answers 0 falls back to where the item was opened, never the top`() {
        assertEquals(600_000, dlnaStartMs(item(SourceKind.MAGIS, startMs = 600_000), livePositionMs = 0))
        assertEquals(600_000, dlnaStartMs(item(SourceKind.MAGIS, startMs = 600_000), livePositionMs = null))
    }

    @Test
    fun `a live channel, or nothing playing, starts nowhere in particular`() {
        assertEquals(0, dlnaStartMs(item(SourceKind.LIVE), livePositionMs = 50_000))
        assertEquals(0, dlnaStartMs(item(SourceKind.PLUGIN, episodeId = PluginIds.liveEpisodeId("p", "c")), livePositionMs = 50_000))
        assertEquals(0, dlnaStartMs(null, livePositionMs = 50_000))
    }
}
