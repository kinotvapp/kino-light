package com.arkiv.player.ui.player

import com.arkiv.player.cast.CastSubtitleSource
import com.arkiv.player.cast.CastTextSelection
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the player screen hands the cast: the title's own subtitles, and the menu's choice. */
class CastSubtitlesSyncTest {

    private val extras = WebExtras(
        episodeId = "ep1",
        headers = emptyMap(),
        subtitles = listOf(ResolvedSub("es", "https://cdn/es.srt", "srt"), ResolvedSub("en", "data:text/vtt,x"), ResolvedSub("en", "https://cdn/en.vtt")),
    )

    @Test
    fun `only the title's own http subtitles are offered, never a live channel's`() {
        assertEquals(
            listOf(CastSubtitleSource("es", "https://cdn/es.srt", "srt"), CastSubtitleSource("en", "https://cdn/en.vtt", "")),
            castSubtitleSources("ep1", SourceKind.PLUGIN, extras),
        )
        assertEquals(emptyList<CastSubtitleSource>(), castSubtitleSources("ep2", SourceKind.PLUGIN, extras))
        assertEquals(emptyList<CastSubtitleSource>(), castSubtitleSources("ep1", SourceKind.LIVE, extras))
        assertEquals(emptyList<CastSubtitleSource>(), castSubtitleSources("ep1", null, null))
    }

    @Test
    fun `the menu's choice is unknown until the player reports tracks, then off or the track`() {
        val formats = listOf("kino-sub:0" to "es", "kino-sub:1" to "en")
        assertEquals(CastTextSelection.Unknown, castTextSelectionOf(-1, 0) { formats.getOrNull(it) })
        assertEquals(CastTextSelection.Off, castTextSelectionOf(-1, 2) { formats.getOrNull(it) })
        assertEquals(CastTextSelection.On("kino-sub:1", "en"), castTextSelectionOf(1, 2) { formats.getOrNull(it) })
        assertEquals(CastTextSelection.Off, castTextSelectionOf(5, 2) { formats.getOrNull(it) })
    }

    @Test
    fun `the phone player's subtitle configs carry the index the cast maps back`() {
        val configs = extras.subtitles.toExoSubtitleConfigs()
        assertEquals(listOf("kino-sub:0", "kino-sub:1", "kino-sub:2"), configs.map { it.id })
    }
}
