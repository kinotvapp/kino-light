package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The subtitle server listens on the LAN: only a URL carrying the current token answers, and it only
 * ever serves what the phone was offered, converted.
 */
class CastSubtitleServerTest {

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `routes parse whole files and HLS segment windows, and nothing else`() {
        assertEquals(CastSubtitleRoutes.Route.Whole(token, 0, 2, "vtt"), CastSubtitleRoutes.parse("/s/$token/0/2.vtt"))
        assertEquals(CastSubtitleRoutes.Route.Whole(token, 90_000, 0, "srt"), CastSubtitleRoutes.parse("/s/$token/90000/0.srt"))
        assertEquals(CastSubtitleRoutes.Route.Segment(token, 0, 1, 6_000, 12_000), CastSubtitleRoutes.parse("/s/$token/0/1/6000-12000.vtt"))
        assertNull(CastSubtitleRoutes.parse("/s/NOTATOKEN/0/2.vtt"))
        assertNull(CastSubtitleRoutes.parse("/s/$token/-5/2.vtt"))
        assertNull(CastSubtitleRoutes.parse("/s/$token/0/2.txt"))
        assertNull(CastSubtitleRoutes.parse("/s/$token/0/1/12000-6000.vtt"))
        assertNull(CastSubtitleRoutes.parse("/s/$token/0/../../etc/passwd"))
        assertNull(CastSubtitleRoutes.parse("/r/$token/master.m3u8"))
    }

    @Test
    fun `only the token being served is authorized`() {
        val route = CastSubtitleRoutes.parse("/s/$token/0/0.vtt")!!
        assertTrue(CastSubtitleRoutes.authorized(route, token))
        assertFalse(CastSubtitleRoutes.authorized(route, "f".repeat(32)))
        assertFalse(CastSubtitleRoutes.authorized(route, null))
    }

    @Test
    fun `a route on a shifted timeline renders cues moved by its offset`() {
        val cues = listOf(SubtitleCue(5_000, 6_000, "antes"), SubtitleCue(65_000, 66_000, "después"))
        val (type, body) = CastSubtitleRoutes.render(CastSubtitleRoutes.Route.Whole(token, 60_000, 0, "vtt"), cues)
        assertEquals("text/vtt; charset=utf-8", type)
        val text = String(body, Charsets.UTF_8)
        assertFalse(text.contains("antes"))
        assertTrue(text.contains("00:00:05.000 --> 00:00:06.000\ndespués"))
        val (_, segment) = CastSubtitleRoutes.render(CastSubtitleRoutes.Route.Segment(token, 0, 0, 60_000, 66_000), cues)
        assertEquals("WEBVTT\n\n00:01:05.000 --> 00:01:06.000\ndespués\n\n", String(segment, Charsets.UTF_8))
        val (srtType, srt) = CastSubtitleRoutes.render(CastSubtitleRoutes.Route.Whole(token, 0, 0, "srt"), cues)
        assertEquals("text/srt; charset=utf-8", srtType)
        assertEquals(0xEF.toByte(), srt[0])
    }

    @Test
    fun `end to end over a socket - converted with the token, refused without it, revoked on a new title`() {
        val fetched = mutableListOf<String>()
        val server = CastSubtitleServer(lanIp = { "127.0.0.1" })
        val latin1Srt = "1\n00:00:01,000 --> 00:00:02,000\nNiño\n".toByteArray(charset("windows-1252"))
        server.offer("ep1", listOf(CastSubtitleSource("es", "https://cdn/es.srt")), { url -> fetched += url; latin1Srt })
        val base = server.baseUrl("ep1", 0)!!
        assertNull(server.baseUrl("ep2", 0))

        val (code, body, cors) = get(CastSubtitleRoutes.fileUrl(base, 0, "vtt"))
        assertEquals(200, code)
        assertEquals("*", cors)
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nNiño\n\n", body)
        // Cached: a second request (a re-cast) doesn't fetch again.
        get(CastSubtitleRoutes.fileUrl(base, 0, "vtt"))
        assertEquals(listOf("https://cdn/es.srt"), fetched)

        assertEquals(404, get(CastSubtitleRoutes.fileUrl(base, 5, "vtt")).first)
        val forged = base.replace(Regex("/s/[0-9a-f]{32}/"), "/s/${"a".repeat(32)}/")
        assertEquals(404, get(CastSubtitleRoutes.fileUrl(forged, 0, "vtt")).first)
        assertTrue(server.revoked(CastSubtitleRoutes.fileUrl(forged, 0, "vtt")))
        assertFalse(server.revoked(CastSubtitleRoutes.fileUrl(base, 0, "vtt")))

        // The same title offered again keeps its URLs; another title revokes them.
        server.offer("ep1", listOf(CastSubtitleSource("es", "https://cdn/es.srt")), { latin1Srt })
        assertEquals(base, server.baseUrl("ep1", 0))
        server.offer("ep2", listOf(CastSubtitleSource("en", "https://cdn/en.srt")), { latin1Srt })
        assertEquals(404, get(CastSubtitleRoutes.fileUrl(base, 0, "vtt")).first)
        assertTrue(server.revoked(CastSubtitleRoutes.fileUrl(base, 0, "vtt")))
        assertNotNull(server.baseUrl("ep2", 0))
    }

    @Test
    fun `a source that can't be fetched answers 502, not an empty subtitle`() {
        val server = CastSubtitleServer(lanIp = { "127.0.0.1" })
        server.offer("ep1", listOf(CastSubtitleSource("es", "https://cdn/es.srt")), { null })
        assertEquals(502, get(CastSubtitleRoutes.fileUrl(server.baseUrl("ep1", 0)!!, 0, "vtt")).first)
    }

    private fun get(url: String): Triple<Int, String, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        return try {
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else ""
            Triple(code, body, c.getHeaderField("Access-Control-Allow-Origin"))
        } finally {
            c.disconnect()
        }
    }
}
