package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The LAN spelling of a proxy URL, which is what makes casting Magis possible at all.
 *
 * Magis VOD sits behind `Content-Auth` and `Content-License`, and the Cast Default Media Receiver
 * cannot send custom headers — only a custom receiver app could. So the receiver can never fetch
 * the CDN directly (it answers 401), and the only URL it can be given is our own proxy's, which
 * puts those headers on the request to the origin.
 *
 * The socket already accepts that connection: [ArchiveCacheProxy.start] opens `ServerSocket(0)`
 * with no bind address, which listens on EVERY interface — the same convention `LiveHlsProxy`
 * documents and `LocalFileServer` relies on. Only the URL string was hardcoded to loopback, so
 * this is a rewrite of that string and nothing else: same port, same path, same query, byte for
 * byte. Diverging from the URL the local player already uses would mean the receiver and the phone
 * fetch two different things.
 */
class ArchiveCacheProxyLanUrlTest {

    /**
     * The query carries the auth headers (`h=`) and the percent-encoded origin (`u=`). Rewriting
     * the host must not disturb a single byte of it: `h` is decoded with [HeaderCodec] on the way
     * in, so any re-encoding would corrupt the very headers the proxy exists to inject.
     */
    @Test
    fun `swaps loopback for the LAN ip and leaves port, path and query untouched`() {
        val local = "http://127.0.0.1:41234/s?h=Q29udGVudC1BdXRoOmFiYw&d=1&u=https%3A%2F%2Fcdn.magis.tv%2Fv%2Fx.ts"
        assertEquals(
            "http://192.168.3.20:41234/s?h=Q29udGVudC1BdXRoOmFiYw&d=1&u=https%3A%2F%2Fcdn.magis.tv%2Fv%2Fx.ts",
            ArchiveCacheProxy.lanUrl(local, "192.168.3.20"),
        )
    }

    /** No headers is a real shape of [ArchiveCacheProxy.proxyUrl] (the `h=` branch is conditional). */
    @Test
    fun `works for a proxy url without headers`() {
        assertEquals(
            "http://10.0.0.5:8080/s?d=1&u=https%3A%2F%2Fcdn%2Fx.mp4",
            ArchiveCacheProxy.lanUrl("http://127.0.0.1:8080/s?d=1&u=https%3A%2F%2Fcdn%2Fx.mp4", "10.0.0.5"),
        )
    }

    /**
     * The origin lives inside the query, and an origin that itself mentions the loopback address
     * must survive: a blind `replace("127.0.0.1", ip)` would rewrite the origin too and send the
     * receiver to fetch from a machine that isn't serving it.
     */
    @Test
    fun `only the authority is rewritten, never a loopback inside the origin`() {
        val local = "http://127.0.0.1:41234/s?u=http%3A%2F%2F127.0.0.1%3A9000%2Fx.ts"
        assertEquals(
            "http://192.168.3.20:41234/s?u=http%3A%2F%2F127.0.0.1%3A9000%2Fx.ts",
            ArchiveCacheProxy.lanUrl(local, "192.168.3.20"),
        )
    }

    /**
     * Null rather than the input unchanged, deliberately — unlike [ArchiveCacheProxy.withFraction],
     * whose caller can live with a no-op. Here handing back a loopback URL would be cast to the TV
     * and fail there, with nothing in our logs to say why; null is a case the cast path already
     * knows how to report ("la TV no puede alcanzar este stream").
     */
    @Test
    fun `returns null for anything that is not a loopback proxy url`() {
        assertNull(ArchiveCacheProxy.lanUrl("https://cdn.magis.tv/v/x.ts", "192.168.3.20"))
        assertNull(ArchiveCacheProxy.lanUrl("", "192.168.3.20"))
        assertNull(ArchiveCacheProxy.lanUrl("http://127.0.0.1/s?u=x", "192.168.3.20")) // no port
    }

    /** An empty or blank ip is "no LAN yet" (see `LanIp.current`), not a host to build a URL with. */
    @Test
    fun `returns null when there is no ip`() {
        assertNull(ArchiveCacheProxy.lanUrl("http://127.0.0.1:41234/s?u=x", ""))
        assertNull(ArchiveCacheProxy.lanUrl("http://127.0.0.1:41234/s?u=x", "   "))
    }

    /**
     * The port is what ties the receiver to the SAME server instance the phone is playing from.
     * [ArchiveCacheProxy.start] is idempotent but a stop()+start() moves the port, so the LAN URL
     * is always derived from a live local URL rather than rebuilt from a remembered port.
     */
    @Test
    fun `keeps the port verbatim`() {
        val lan = ArchiveCacheProxy.lanUrl("http://127.0.0.1:1/s?u=x", "192.168.1.7")
        assertEquals("http://192.168.1.7:1/s?u=x", lan)
    }

    /**
     * The receiver is handed the PLAYLIST path, not the raw stream: it refuses a bare transport
     * stream served progressively ("FFmpegDemuxer: open context failed", read off its own log)
     * and plays the identical bytes announced as byte ranges. Only the path changes -- host, port
     * and the whole query, auth headers included, must survive, because `/s` serves the media
     * with exactly that query.
     */
    @Test
    fun `the playlist url keeps everything but the path`() {
        val local = "http://127.0.0.1:41234/s?h=QUJD&d=1&u=https%3A%2F%2Fcdn%2Fx.ts"
        assertEquals(
            "http://192.168.3.20:41234/hls.m3u8?h=QUJD&d=1&u=https%3A%2F%2Fcdn%2Fx.ts",
            ArchiveCacheProxy.lanPlaylistUrl(local, "192.168.3.20"),
        )
    }

    /** Same guards as [ArchiveCacheProxy.lanUrl]: no ip or not one of ours means no cast. */
    @Test
    fun `no playlist url without a LAN ip or a proxy url`() {
        assertNull(ArchiveCacheProxy.lanPlaylistUrl("http://127.0.0.1:1/s?u=x", ""))
        assertNull(ArchiveCacheProxy.lanPlaylistUrl("https://cdn.magis.tv/v/x.ts", "192.168.3.20"))
    }
}
