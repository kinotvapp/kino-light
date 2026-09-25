package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginRedirectTest {

    private val playlist = "http://cdn.example.com/live/chan.m3u8"

    @Test
    fun `an http to https upgrade on the same host is followed`() {
        assertEquals("https://cdn.example.com/live/chan.m3u8", OriginRedirect.sameHost(playlist, "https://cdn.example.com/live/chan.m3u8"))
    }

    @Test
    fun `a relative location is resolved against the current url`() {
        assertEquals("http://cdn.example.com/live/other.m3u8", OriginRedirect.sameHost(playlist, "other.m3u8"))
        assertEquals("http://cdn.example.com/x/y.m3u8", OriginRedirect.sameHost(playlist, "/x/y.m3u8"))
    }

    @Test
    fun `the host comparison ignores case`() {
        assertEquals("https://CDN.example.com/a", OriginRedirect.sameHost(playlist, "https://CDN.example.com/a"))
    }

    @Test
    fun `another host is never followed because the request carries the channel's credentials`() {
        assertNull(OriginRedirect.sameHost(playlist, "https://evil.example.net/live/chan.m3u8"))
        assertNull(OriginRedirect.sameHost(playlist, "//other.example.com/live/chan.m3u8"))
    }

    @Test
    fun `only http and https are followed`() {
        assertNull(OriginRedirect.sameHost(playlist, "ftp://cdn.example.com/live/chan.m3u8"))
        assertNull(OriginRedirect.sameHost(playlist, "file:///etc/passwd"))
    }

    @Test
    fun `a missing or malformed location is not followed`() {
        assertNull(OriginRedirect.sameHost(playlist, null))
        assertNull(OriginRedirect.sameHost(playlist, "  "))
        assertNull(OriginRedirect.sameHost(playlist, "http://bad host/x"))
    }

    @Test
    fun `the redirect codes are the four that keep the request`() {
        assertEquals(setOf(301, 302, 307, 308), OriginRedirect.CODES)
    }
}
