package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRequestBuilderTest {

    @Test
    fun `archive prefers castUrl over mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "Doc", subtitle = "", artworkUrl = "https://p.jpg",
            mediaUrl = "https://archive.org/x.mkv", castUrl = "https://archive.org/x.mp4",
            lanUrl = null, startPositionMs = 5000,
        )!!
        assertEquals("https://archive.org/x.mp4", r.uri)
        assertEquals("video/mp4", r.mimeType)
        assertEquals("Doc", r.title)
        assertEquals(5000, r.startPositionMs)
    }

    @Test
    fun `archive without castUrl uses mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.mkv", castUrl = null,
            lanUrl = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.mkv", r.uri)
        assertEquals("video/x-matroska", r.mimeType)
    }

    @Test
    fun `without any url nothing can be cast`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
                mediaUrl = "", castUrl = null,
                lanUrl = null, startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `a negative position gets clamped to zero`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            lanUrl = null, startPositionMs = -500,
        )!!
        assertEquals(0, r.startPositionMs)
    }

    @Test
    fun `a blank but non-null castUrl is ignored`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.webm", castUrl = "",
            lanUrl = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.webm", r.uri)
        assertEquals("video/webm", r.mimeType)
    }

    /**
     * The MIME-by-URL table used to cover four extensions (mp4/m4v/mkv/webm) and sent EVERYTHING
     * else to `video/mp4`. A magis `.ts` or an archive `.avi` got announced to the receiver as
     * mp4, which is exactly the string it uses to decide whether to open the stream. See
     * `VideoContainer`.
     */
    @Test
    fun `mime by URL covers the containers we actually serve, not just four`() {
        fun mimeFor(url: String) = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = url, castUrl = null,
            lanUrl = null, startPositionMs = 0,
        )!!.mimeType

        assertEquals("video/mp2t", mimeFor("https://cdn/vod/ABC_media.ts"))
        assertEquals("video/x-msvideo", mimeFor("https://archive.org/peli.avi"))
        assertEquals("video/mpeg", mimeFor("https://archive.org/peli.mpg"))
        // And what already worked keeps working.
        assertEquals("video/mp4", mimeFor("https://archive.org/peli.mp4"))
        assertEquals("video/x-matroska", mimeFor("https://archive.org/peli.mkv"))
    }

    @Test
    fun `live uses the proxy's LAN url and the HLS mime`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            startPositionMs = 45_000, isLive = true,
        )!!
        assertEquals("http://192.168.3.20:1/live.m3u8", r.uri)
        assertEquals("application/vnd.apple.mpegurl", r.mimeType)
    }

    @Test
    fun `live forces startPositionMs to zero no matter what's asked`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            startPositionMs = 999_999, isLive = true,
        )!!
        assertEquals(0, r.startPositionMs)
    }

    @Test
    fun `live without a LAN url cannot be cast`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "live:espn", title = "ESPN", subtitle = "", artworkUrl = "",
                mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
                lanUrl = null,
                startPositionMs = 0, isLive = true,
            ),
        )
    }

    @Test
    fun `live ignores castUrl and mediaUrl even when set`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = "https://should-not-be-used.mp4",
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            startPositionMs = 0, isLive = true,
        )!!
        assertEquals("http://192.168.3.20:1/live.m3u8", r.uri)
    }

    /**
     * Magis: the receiver can ONLY be given our proxy's LAN url. Its VOD sits behind
     * `Content-Auth`/`Content-License` and the Default Media Receiver cannot send custom headers,
     * so `castUrl` -- the raw CDN url -- answers 401. `mediaUrl` is the loopback the phone plays
     * from and is unreachable from the TV. Both must be ignored, exactly like live does.
     */
    @Test
    fun `magis uses the LAN url and ignores the CDN and the loopback`() {
        val r = CastRequestBuilder.build(
            episodeId = "magis:7B66::0", title = "Peli", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:41234/s?h=AAA&d=1&u=https%3A%2F%2Fcdn%2Fx.ts",
            castUrl = "https://cdn.magis.tv/vod/x_media.ts",
            lanUrl = "http://192.168.3.20:41234/s?h=AAA&d=1&u=https%3A%2F%2Fcdn%2Fx.ts",
            startPositionMs = 90_000, requiresLanUrl = true, mimeOverride = "video/mp2t",
        )!!
        assertEquals("http://192.168.3.20:41234/s?h=AAA&d=1&u=https%3A%2F%2Fcdn%2Fx.ts", r.uri)
        assertEquals("video/mp2t", r.mimeType)
        // Not live: Magis VOD does have a "where you were", so the position must survive.
        assertEquals(90_000, r.startPositionMs)
    }

    /**
     * No LAN ip (no WiFi, or the proxy never started) means there is NO url the receiver can use.
     * Falling back to `castUrl` here would cast a 401 and look like a mystery on the TV; null is
     * what makes the caller show "la TV no puede alcanzar este stream".
     */
    @Test
    fun `magis without a LAN url cannot be cast, does not fall back to the CDN`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "magis:7B66::0", title = "Peli", subtitle = "", artworkUrl = "",
                mediaUrl = "http://127.0.0.1:41234/s?u=x",
                castUrl = "https://cdn.magis.tv/vod/x_media.ts",
                lanUrl = null, startPositionMs = 0, requiresLanUrl = true,
            ),
        )
    }

    /**
     * The proxy url's path is `/s` and its query is stripped before guessing, so the extension
     * says nothing -- [CastRequestBuilder.mimeForUrl] would answer mp4 for a `.ts` stream, which
     * is the "announce one container, deliver another" mistake this file warns about. The real
     * container comes from the CDN url's extension (see `MagisResolve`, which builds it as
     * `_media.ts` / `_media.mp4`), passed in as `mimeOverride`.
     */
    @Test
    fun `magis without mimeOverride would guess mp4 for a ts`() {
        val r = CastRequestBuilder.build(
            episodeId = "magis:7B66::0", title = "Peli", subtitle = "", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:41234/s?u=x",
            castUrl = null,
            lanUrl = "http://192.168.3.20:41234/s?h=AAA&u=https%3A%2F%2Fcdn%2Fx.ts",
            startPositionMs = 0, requiresLanUrl = true,
        )!!
        assertEquals("video/mp4", r.mimeType)
    }

    /** `requiresLanUrl` must not disturb the sources that legitimately cast a remote url. */
    @Test
    fun `without requiresLanUrl, castUrl is still preferred`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "file:///data/x.ts", castUrl = "http://192.168.3.20:9/file",
            lanUrl = null, startPositionMs = 0,
        )!!
        assertEquals("http://192.168.3.20:9/file", r.uri)
    }

    @Test
    fun `episodeId subtitle artworkUrl pass through unchanged`() {
        val r = CastRequestBuilder.build(
            episodeId = "episode-42-custom", title = "Title", subtitle = "Season 2 Episode 5",
            artworkUrl = "https://example.com/poster.jpg",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            lanUrl = null, startPositionMs = 0,
        )!!
        assertEquals("episode-42-custom", r.episodeId)
        assertEquals("Season 2 Episode 5", r.subtitle)
        assertEquals("https://example.com/poster.jpg", r.artworkUrl)
    }
}
