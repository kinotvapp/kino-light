package com.arkiv.player.ui.player

import com.arkiv.player.cast.CastSubtitleSource
import com.arkiv.player.data.subtitles.DownloadedSubtitle
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A downloaded online subtitle as a track: appended in place, labelled, offered to the cast. */
class OnlineSubtitleTrackTest {

    private val source = ResolvedSub("es", "https://cdn/es.vtt")
    private val online = onlineSub(DownloadedSubtitle("en", "Inglés · SubDL · Rel", "/data/cache/online_subtitles/a.srt"))

    @Test
    fun `a downloaded subtitle is a local srt with its own label`() {
        assertEquals("file:/data/cache/online_subtitles/a.srt", online.url)
        assertEquals("srt", online.format)
        assertEquals(true, online.online)
        val configs = listOf(source, online).toExoSubtitleConfigs()
        assertEquals(listOf("kino-sub:0", "kino-sub:1"), configs.map { it.id })
        assertEquals("Inglés · SubDL · Rel", configs[1].label)
        assertEquals(null, configs[0].label)
    }

    @Test
    fun `the player keeps its key while subtitles are only appended`() {
        val base = listOf("a")
        assertSame(base, subtitleKeyFor(base, listOf("a", "b")))
        assertSame(base, subtitleKeyFor(base, listOf("a", "b", "c")))
        assertEquals(listOf("x"), subtitleKeyFor(base, listOf("x")))
        assertEquals(listOf("b", "a"), subtitleKeyFor(base, listOf("b", "a")))
        assertEquals(emptyList<String>(), subtitleKeyFor(base, emptyList()))
        assertEquals(emptyList<String>(), subtitleKeyFor(emptyList(), listOf("a")))
    }

    @Test
    fun `the source's declared languages leave the online ones out, the cast gets all`() {
        val extras = WebExtras("ep1", emptyMap(), listOf(source, online))
        assertEquals(listOf("es"), extras.declaredLanguages)
        assertEquals(
            listOf(CastSubtitleSource("es", "https://cdn/es.vtt"), CastSubtitleSource("en", online.url, "srt")),
            castSubtitleSources("ep1", SourceKind.PLUGIN, extras),
        )
    }
}
