package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cast decision for a plugin title ([pluginCastModeFor]): the whole matrix, one row per case. */
class PluginCastModeTest {

    private fun plugin(
        url: String,
        mime: String = "",
        headers: Map<String, String> = emptyMap(),
        drm: Boolean = false,
        xuper: Boolean = false,
        episodeId: String = "plugin:someone:m1::0",
        hosts: List<String> = listOf("cdn.example"),
    ) = PlayerData(
        episodeId = episodeId, itemId = episodeId.substringBefore("::"), title = "T", subtitle = "",
        mediaUrl = url, castUrl = null, artworkUrl = "",
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = SourceKind.PLUGIN, requestHeaders = headers, pluginHosts = EffectiveHosts(hosts),
        pluginXuper = xuper, mime = mime, drm = drm,
    )

    private val referer = mapOf("Referer" to "https://site.example/")

    @Test fun `official Xuper VOD keeps its own path, its live channel keeps no cast`() {
        assertEquals(PluginCastMode.Xuper, pluginCastModeFor(plugin("https://cdn.example/a_media.ts", xuper = true)))
        val live = plugin("https://cdn.example/live.m3u8", xuper = true, episodeId = PluginIds.liveEpisodeId("xuper", "c"))
        assertTrue(pluginCastModeFor(live) is PluginCastMode.None)
    }

    @Test fun `DRM never casts, whatever the format`() {
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/a.mp4", drm = true)) is PluginCastMode.None)
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/a.m3u8", drm = true)) is PluginCastMode.None)
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/a.mpd", drm = true)) is PluginCastMode.None)
    }

    @Test fun `a header-free mp4 or webm on an allowed host goes straight to the receiver`() {
        // Internet Archive: mp4/webm, no headers. The phone moves no bytes.
        assertEquals(PluginCastMode.Direct("video/mp4"), pluginCastModeFor(plugin("https://cdn.example/1/items/x/x.mp4")))
        assertEquals(PluginCastMode.Direct("video/webm"), pluginCastModeFor(plugin("https://cdn.example/x.webm")))
        // An extension-less URL whose plugin declared the container.
        assertEquals(PluginCastMode.Direct("video/mp4"), pluginCastModeFor(plugin("https://cdn.example/get?id=7", mime = "video/mp4")))
    }

    @Test fun `a progressive file that needs headers or another host goes through the proxy`() {
        assertEquals(PluginCastMode.ViaProxy("video/mp4"), pluginCastModeFor(plugin("https://cdn.example/x.mp4", headers = referer)))
        assertEquals(PluginCastMode.ViaProxy("video/mp4"), pluginCastModeFor(plugin("https://ia800.us.archive.org/1/items/x/x.mp4")))
        assertEquals(PluginCastMode.ViaProxy("video/mp4"), pluginCastModeFor(plugin("http://cdn.example/x.mp4")))
        assertEquals(PluginCastMode.ViaProxy("video/x-matroska"), pluginCastModeFor(plugin("https://cdn.example/x.mkv", headers = referer)))
    }

    @Test fun `a progressive TS is never handed to the receiver`() {
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/x.ts?tok=1", headers = referer)) is PluginCastMode.None)
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/x.ts")) is PluginCastMode.None)
    }

    @Test fun `a URL nothing describes casts by what a probe of its first bytes found`() {
        val unknown = plugin("https://cdn.example/stream/abc")
        assertTrue(pluginCastModeFor(unknown) is PluginCastMode.None)
        assertEquals(PluginCastMode.Direct(MIME_HLS), pluginCastModeFor(unknown.copy(probedMime = MIME_HLS)))
        assertEquals(PluginCastMode.Direct("video/mp4"), pluginCastModeFor(unknown.copy(probedMime = "video/mp4")))
        // The declared MIME wins over the probe.
        assertEquals(PluginCastMode.Direct("video/webm"), pluginCastModeFor(unknown.copy(mime = "video/webm", probedMime = "video/mp4")))
    }

    @Test fun `a header-free HLS on a host the plugin's rules allow goes straight to the receiver`() {
        assertEquals(PluginCastMode.Direct(MIME_HLS), pluginCastModeFor(plugin("https://cdn.example/hls/playlist.m3u8")))
        assertEquals(PluginCastMode.Direct(MIME_HLS), pluginCastModeFor(plugin("https://cdn.example/play?id=1", mime = "application/x-mpegURL")))
    }

    @Test fun `an HLS the receiver may not be pointed at directly goes through the proxy, playlists rewritten`() {
        val proxied = PluginCastMode.ViaProxy(MIME_HLS)
        // Needs headers (most Nuvio hosters: Referer/Origin).
        assertEquals(proxied, pluginCastModeFor(plugin("https://cdn.example/a.m3u8", headers = referer)))
        // Undeclared host, plain http, a private IP literal: the TV is never pointed there; the
        // proxy's gated client decides (and refuses what the phone's player would refuse).
        assertEquals(proxied, pluginCastModeFor(plugin("https://other.example/a.m3u8")))
        assertEquals(proxied, pluginCastModeFor(plugin("http://cdn.example/a.m3u8")))
        assertEquals(proxied, pluginCastModeFor(plugin("https://192.168.1.1/a.m3u8", hosts = listOf("192.168.1.1"))))
        // A plugin live channel's HLS too.
        val live = plugin("https://cdn.example/live.m3u8", headers = referer, episodeId = PluginIds.liveEpisodeId("someone", "c"))
        assertEquals(proxied, pluginCastModeFor(live))
    }

    @Test fun `DASH and a format nothing tells apart have no cast`() {
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/a.mpd")) is PluginCastMode.None)
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/play", mime = "application/dash+xml")) is PluginCastMode.None)
        assertTrue(pluginCastModeFor(plugin("https://cdn.example/stream/abc")) is PluginCastMode.None)
    }

    @Test fun `a non-plugin item is never decided here`() {
        val magis = plugin("https://cdn.example/a.mp4").copy(kind = SourceKind.MAGIS)
        assertTrue(pluginCastModeFor(magis) is PluginCastMode.None)
    }

    @Test fun `the direct-cast host rule is the plugin's own gate`() {
        assertTrue(directCastAllowed(plugin("https://cdn.example/a.m3u8")))
        assertTrue(directCastAllowed(plugin("https://sub.cdn.example/a.m3u8", hosts = listOf("*.cdn.example"))))
        assertFalse(directCastAllowed(plugin("https://localhost/a.m3u8", hosts = listOf("localhost"))))
        assertFalse(directCastAllowed(plugin("not a url")))
    }

    @Test fun `the person is told why when a session is up`() {
        assertEquals("Este título está protegido y no se puede enviar a la TV", pluginNoCastMessage(plugin("https://x/a.mp4", drm = true)))
        assertEquals("Este título no se puede enviar a la TV", pluginNoCastMessage(plugin("https://x/a.mpd")))
    }

    @Test
    fun `a title's stream is probed for the cast, a live channel's only while casting`() {
        assertTrue(pluginCastProbe(live = false, casting = false))
        assertTrue(pluginCastProbe(live = false, casting = true))
        assertFalse("every zap would pay one more connection", pluginCastProbe(live = true, casting = false))
        assertTrue(pluginCastProbe(live = true, casting = true))
    }
}
