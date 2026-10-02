package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A direct cast that fails before playing is loaded once more through the phone's proxy ([CastFallback]). */
class CastFallbackTest {

    private val proxied = CastRequest(
        uri = "http://192.168.1.20:4100/t/tok/stream", mimeType = "video/mp4", episodeId = "plugin:p:1::e1",
        title = "T", subtitle = "", artworkUrl = "", startPositionMs = 0L, route = "proxy",
    )
    private val direct = proxied.copy(uri = "https://vd1.mycdn.me/?expires=1&sig=x", route = "direct", fallback = proxied)

    @Test
    fun `a direct load that fails before playing falls back to the proxy, once`() {
        val next = CastFallback.next(direct, failed = true, neverPlayed = true)
        assertEquals(proxied, next)
        // The fallback has none of its own: a second failure is reported and asked about.
        assertNull(CastFallback.next(next, failed = true, neverPlayed = true))
    }

    @Test
    fun `a stream that played and then dropped is not rerouted`() {
        assertNull(CastFallback.next(direct, failed = true, neverPlayed = false))
    }

    @Test
    fun `nothing failed, or nothing to fall back to, means no fallback`() {
        assertNull(CastFallback.next(direct, failed = false, neverPlayed = true))
        assertNull(CastFallback.next(proxied, failed = true, neverPlayed = true))
        assertNull(CastFallback.next(null, failed = true, neverPlayed = true))
    }

    @Test
    fun `the report names the host, never a path or token, and the phone's servers as lan`() {
        assertEquals("vd1.mycdn.me", CastFallback.hostOf("https://vd1.mycdn.me/?expires=1&sig=x"))
        assertEquals("lan", CastFallback.hostOf("http://192.168.1.20:4100/t/tok/stream"))
        assertEquals("lan", CastFallback.hostOf("http://172.20.0.4:80/x"))
        assertEquals("lan", CastFallback.hostOf("http://10.0.0.2/x"))
        assertEquals("cdn.example", CastFallback.hostOf("https://user:pw@CDN.example:8443/a.m3u8"))
        assertEquals("", CastFallback.hostOf(null))
    }
}
