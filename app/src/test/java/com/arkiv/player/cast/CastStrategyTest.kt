package com.arkiv.player.cast

import com.arkiv.player.cast.CastStrategy.Format
import com.arkiv.player.cast.CastStrategy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lightest cast route per stream: the whole matrix. */
class CastStrategyTest {

    private fun route(
        url: String,
        mime: String = "",
        headers: Boolean = false,
        direct: Boolean = true,
        remux: Boolean = false,
        tsPlaylist: Boolean = false,
    ) = CastStrategy.choose(CastStrategy.formatOf(url, mime), headers, direct, remux, tsPlaylist)

    @Test
    fun `formats from the declared mime first, then the extension`() {
        assertEquals(Format.HLS, CastStrategy.formatOf("https://h/x", "application/x-mpegURL"))
        assertEquals(Format.MP4, CastStrategy.formatOf("https://h/x.ts", "video/mp4"))
        assertEquals(Format.MPEG_TS, CastStrategy.formatOf("http://cdn/vod/B9EF_media.ts", ""))
        assertEquals(Format.MP4, CastStrategy.formatOf("http://cdn/vod/B9EF_media.mp4?sig=1", ""))
        assertEquals(Format.HLS, CastStrategy.formatOf("https://h/a/index.m3u8?t=1", ""))
        assertEquals(Format.DASH, CastStrategy.formatOf("https://h/a.mpd", ""))
        assertEquals(Format.MATROSKA, CastStrategy.formatOf("https://h/a.mkv", ""))
        assertEquals(Format.WEBM, CastStrategy.formatOf("https://h/a.webm", ""))
        assertEquals(Format.OTHER_FILE, CastStrategy.formatOf("https://h/a.avi", ""))
        assertEquals(Format.UNKNOWN, CastStrategy.formatOf("https://h/stream/abc", ""))
    }

    @Test
    fun `supported containers with no headers go straight to the receiver`() {
        assertEquals(Route.DIRECT, route("https://ia800.us.archive.org/1/items/x/x.mp4"))
        assertEquals(Route.DIRECT, route("https://cdn.example/x.webm"))
        assertEquals(Route.DIRECT, route("https://cdn.example/hls/playlist.m3u8"))
    }

    @Test
    fun `supported containers that need headers, or a host the TV must not be given, go through the proxy`() {
        // Xuper's _media.mp4 behind Content-Auth: proxy only, never the remux.
        assertEquals(Route.PROXY, route("http://cdn/vod/B9EF_media.mp4", headers = true, direct = false, remux = true))
        // Any HLS that needs headers: the rewritten playlist through the proxy, never the remux.
        assertEquals(Route.PROXY, route("https://cdn.example/a.m3u8", headers = true, remux = true))
        assertEquals(Route.PROXY, route("https://other.example/a.m3u8", direct = false))
    }

    @Test
    fun `a progressive TS is never sent as it is`() {
        // Xuper/Magis `.ts` (HEVC or H.264): the fMP4 remux.
        assertEquals(Route.REMUX, route("http://cdn/vod/B9EF_media.ts", headers = true, direct = false, remux = true))
        // Remux not available (failed): the HLS-of-TS playlist when it can be built...
        assertEquals(Route.TS_PLAYLIST, route("http://cdn/vod/B9EF_media.ts", headers = true, direct = false, tsPlaylist = true))
        // ...else nothing, rather than a URL the receiver refuses.
        assertEquals(Route.NONE, route("https://cdn.example/x.ts", headers = true))
        assertEquals(Route.NONE, route("https://cdn.example/x.ts"))
    }

    @Test
    fun `other files pass as they are, DASH and the unknown have no route`() {
        assertEquals(Route.PROXY, route("https://cdn.example/x.mkv", headers = true))
        assertEquals(Route.DIRECT, route("https://cdn.example/x.mkv"))
        assertEquals(Route.NONE, route("https://cdn.example/a.mpd"))
        assertEquals(Route.NONE, route("https://cdn.example/stream/abc"))
    }

    @Test
    fun `a probe of the first bytes names manifests and containers`() {
        assertEquals(CastStrategy.MIME_HLS, CastStrategy.mimeFromSignature("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()))
        assertEquals(CastStrategy.MIME_DASH, CastStrategy.mimeFromSignature("<?xml version=\"1.0\"?>\n<MPD xmlns=\"x\">".toByteArray()))
        val mp4 = ByteArray(64).also {
            byteArrayOf(0, 0, 0, 0x20).copyInto(it, 0)
            "ftypisom".toByteArray().copyInto(it, 4)
        }
        assertEquals("video/mp4", CastStrategy.mimeFromSignature(mp4))
        val ts = ByteArray(188 * 3).also { for (i in 0 until 3) it[i * 188] = 0x47 }
        assertEquals("video/mp2t", CastStrategy.mimeFromSignature(ts))
        assertNull(CastStrategy.mimeFromSignature("<html><body>nope".toByteArray()))
    }
}
