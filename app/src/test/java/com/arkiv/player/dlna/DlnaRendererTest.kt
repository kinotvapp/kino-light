package com.arkiv.player.dlna

import com.arkiv.player.cast.CastStrategy
import com.arkiv.player.cast.CastStrategy.Format
import com.arkiv.player.cast.CastStrategy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The renderer's sink list as the INPUT of the shared route decision, the way DLNA asks it. */
class DlnaRendererTest {

    private fun route(sinks: List<String>) = CastStrategy.choose(
        Format.MPEG_TS, needsHeaders = true, directAllowed = false, remuxAvailable = true,
        receiver = DlnaRenderer.receiverOf(sinks),
    )

    @Test
    fun `a renderer that lists the TS gets it as it is, through the proxy`() {
        assertEquals(Route.PROXY, route(listOf("video/mp4", "video/mp2t")))
    }

    @Test
    fun `a renderer that lists HLS but not TS gets the growing remux as HLS`() {
        assertEquals(Route.REMUX, route(listOf("video/mp4", "application/vnd.apple.mpegurl")))
        assertEquals(Route.REMUX, route(listOf("video/mp4", "application/x-mpegURL")))
    }

    @Test
    fun `a renderer that lists neither, or nothing at all, waits for the whole MP4 as before`() {
        assertEquals(Route.REMUX_FILE, route(listOf("video/mp4", "video/x-matroska")))
        assertEquals(Route.REMUX_FILE, route(emptyList()))
    }

    @Test
    fun `an audio playlist type does not make a video HLS renderer`() {
        assertFalse(DlnaRenderer.receiverOf(listOf("audio/mpegurl", "audio/x-mpegurl")).playsHls)
    }

    @Test
    fun `an mp4 is proxied whatever the renderer lists`() {
        assertEquals(
            Route.PROXY,
            CastStrategy.choose(Format.MP4, needsHeaders = true, directAllowed = false, remuxAvailable = true, receiver = DlnaRenderer.receiverOf(emptyList())),
        )
    }

    @Test
    fun `the summary names the types the route depends on`() {
        val s = DlnaRenderer.summary(listOf("video/mp4", "video/mp2t", "application/vnd.apple.mpegurl"))
        assertEquals("3 types · mp4=yes ts=yes hls=yes mkv=no", s)
        assertTrue(DlnaRenderer.summary(emptyList()).startsWith("lists nothing"))
    }

    /** ERRORES-AMF: a Philips "NMR" listing these answered SetAVTransportURI for a live playlist with 716. */
    private val philipsNmr = listOf(
        "audio/mpeg", "audio/l16;rate=44100;channels=2", "image/jpeg", "video/mpeg",
        "video/vnd.dlna.mpeg-tts", "audio/x-ms-asf", "audio/wav", "audio/x-ms-wma", "audio/vnd.dlna.adts",
    )

    @Test
    fun `an HLS playlist is refused before sending to a renderer whose list names no HLS`() {
        assertFalse(DlnaRenderer.takesHls(philipsNmr))
        assertFalse(DlnaRenderer.takesHls(listOf("video/mp4", "video/mp2t", "audio/mpegurl")))
    }

    @Test
    fun `an HLS playlist goes to a renderer that lists an HLS type, or that lists nothing`() {
        assertTrue(DlnaRenderer.takesHls(listOf("video/mp4", "application/vnd.apple.mpegurl")))
        assertTrue(DlnaRenderer.takesHls(listOf("application/x-mpegURL")))
        assertTrue(DlnaRenderer.takesHls(emptyList()))
    }

    @Test
    fun `the refusal says what cannot play, in Spanish`() {
        assertEquals("Este TV no puede reproducir canales en vivo por DLNA", DlnaRenderer.noHlsMessage(live = true))
        assertEquals("Este TV no puede reproducir este título por DLNA", DlnaRenderer.noHlsMessage(live = false))
    }
}
