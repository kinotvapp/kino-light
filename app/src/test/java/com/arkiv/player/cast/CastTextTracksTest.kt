package com.arkiv.player.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The track list a receiver is given, and how the phone's choice maps onto the receiver's tracks. */
class CastTextTracksTest {

    private val sources = listOf(
        CastSubtitleSource("es", "https://cdn/es.srt", "srt"),
        CastSubtitleSource("en", "https://cdn/en.vtt", "vtt"),
        CastSubtitleSource("es", "https://cdn/es-forced.srt"),
        CastSubtitleSource("Português", "https://cdn/pt.srt"),
        CastSubtitleSource("", "https://cdn/unknown.srt"),
    )

    @Test
    fun `every source becomes a text track with a stable id, a language tag and a Spanish name that never repeats`() {
        val tracks = CastTextTracks.build(sources, { i -> "http://lan/s/t/0/$i.vtt" })
        assertEquals(listOf(1000L, 1001L, 1002L, 1003L, 1004L), tracks.map { it.id })
        assertEquals(listOf("es", "en", "es", "pt", "und"), tracks.map { it.language })
        assertEquals(listOf("Español", "Inglés", "Español (2)", "Português", "Subtítulos 5"), tracks.map { it.name })
        assertEquals("http://lan/s/t/0/3.vtt", tracks[3].url)
    }

    @Test
    fun `the list is capped and a source with no URL is left out`() {
        val many = (0 until 20).map { CastSubtitleSource("es", "https://cdn/$it.srt") }
        assertEquals(CastTextTracks.MAX_TRACKS, CastTextTracks.build(many, { "u$it" }).size)
        val some = CastTextTracks.build(sources, { i -> if (i == 1) null else "u$i" })
        assertEquals(listOf(0, 2, 3, 4), some.map { it.index })
    }

    @Test
    fun `language tags from codes, three-letter codes and names`() {
        assertEquals("es", CastTextTracks.languageTag("spa"))
        assertEquals("es-419", CastTextTracks.languageTag("es-419"))
        assertEquals("pt-BR", CastTextTracks.languageTag("pt_br"))
        assertEquals("es", CastTextTracks.languageTag("Español"))
        assertEquals("en", CastTextTracks.languageTag("English"))
        assertEquals("es-419", CastTextTracks.languageTag("Latino"))
        assertEquals("und", CastTextTracks.languageTag("  "))
        assertEquals("und", CastTextTracks.languageTag("Klingon (forzado)"))
        assertEquals("Español (Latinoamérica)", CastTextTracks.displayName("es-419", 0))
    }

    @Test
    fun `the phone's choice maps back by its id first, then by language`() {
        assertEquals(2, CastTextTracks.indexOf(CastTextSelection.On("kino-sub:2", "es"), sources))
        assertEquals(2, CastTextTracks.indexOf(CastTextSelection.On("1:kino-sub:2", "es"), sources))
        assertEquals(1, CastTextTracks.indexOf(CastTextSelection.On(null, "eng"), sources))
        assertEquals(0, CastTextTracks.indexOf(CastTextSelection.On("1/34", "spa"), sources))
        assertNull(CastTextTracks.indexOf(CastTextSelection.On("kino-sub:9", "fr"), sources))
        assertNull(CastTextTracks.indexOf(CastTextSelection.Off, sources))
        assertNull(CastTextTracks.indexOf(CastTextSelection.Unknown, sources))
    }

    @Test
    fun `the receiver's track is found by our id, by its URL, by name, or by a language only it has`() {
        val sidecar = listOf(ReceiverTrack(1001, true, "en", "Inglés", "http://lan/s/t/0/1.vtt"), ReceiverTrack(1, false, "es", null, null))
        assertEquals(1001L, CastTextTracks.receiverIdOf(sidecar, 1, "Inglés", "en"))
        val manifest = listOf(
            ReceiverTrack(1, false, null, null, null),
            ReceiverTrack(3, true, "es", "Español", "http://lan/r/t/subs0.m3u8"),
            ReceiverTrack(4, true, "en", "Inglés", "subs1.m3u8"),
            ReceiverTrack(5, true, "es", "Español (2)", "x"),
        )
        assertEquals(4L, CastTextTracks.receiverIdOf(manifest, 1, "nada", "fr"))
        assertEquals(5L, CastTextTracks.receiverIdOf(manifest, 2, "Español (2)", "es"))
        assertEquals(4L, CastTextTracks.receiverIdOf(manifest.map { it.copy(contentId = null, name = null) }, 7, "x", "en"))
        // Two Spanish tracks and nothing else to go on: no guess.
        assertNull(CastTextTracks.receiverIdOf(manifest.map { it.copy(contentId = null, name = null) }, 7, "x", "es"))
    }

    @Test
    fun `switching the text track keeps the audio and video on and says nothing when already right`() {
        val tracks = listOf(ReceiverTrack(1, false, null, null, null), ReceiverTrack(2, false, null, null, null), ReceiverTrack(1000, true, "es", "Español", null), ReceiverTrack(1001, true, "en", "Inglés", null))
        assertArrayEquals(longArrayOf(1, 2, 1001), CastTextTracks.activeIdsFor(longArrayOf(1, 2, 1000), tracks, 1001))
        assertArrayEquals(longArrayOf(2), CastTextTracks.activeIdsFor(longArrayOf(2, 1000), tracks, null))
        assertNull(CastTextTracks.activeIdsFor(longArrayOf(2, 1000), tracks, 1000))
        assertNull(CastTextTracks.activeIdsFor(longArrayOf(2), tracks, null))
    }

    @Test
    fun `the remux HLS gets the subtitles in its manifest, everything else as sidecars, unless overridden`() {
        assertEquals(CastSubtitleDelivery.MANIFEST, CastTextTracks.deliveryFor(hlsFmp4 = true, override = null))
        assertEquals(CastSubtitleDelivery.SIDECAR, CastTextTracks.deliveryFor(hlsFmp4 = false, override = ""))
        assertEquals(CastSubtitleDelivery.SIDECAR, CastTextTracks.deliveryFor(hlsFmp4 = true, override = "sidecar"))
        assertEquals(CastSubtitleDelivery.SIDECAR, CastTextTracks.deliveryFor(hlsFmp4 = false, override = "manifest"))
        assertEquals(CastSubtitleDelivery.OFF, CastTextTracks.deliveryFor(hlsFmp4 = true, override = " OFF "))
    }

    @Test
    fun `our URLs name their subtitle index`() {
        assertEquals(3, CastTextTracks.urlIndex("http://lan:1/s/abc/0/3.vtt"))
        assertEquals(3, CastTextTracks.urlIndex("http://lan:1/s/abc/0/3.srt"))
        assertEquals(2, CastTextTracks.urlIndex("http://lan:1/s/abc/0/2/6000-12000.vtt"))
        assertEquals(4, CastTextTracks.urlIndex("http://lan:1/r/abc/subs4.m3u8"))
        assertNull(CastTextTracks.urlIndex("https://cdn/3.vtt"))
    }
}
