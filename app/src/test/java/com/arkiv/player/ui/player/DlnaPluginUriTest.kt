package com.arkiv.player.ui.player

import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a DLNA renderer is handed for a plugin title ([dlnaPluginUri]): never a remote https URL.
 * 2026-10-01, LG OLED55C1: an Internet Archive mp4 sent as it is (`https://archive.org/download/…`,
 * which 302s to a `dnNNN.us.archive.org` mirror) got `SetAVTransportURI` 716 "Resource not found".
 */
class DlnaPluginUriTest {

    private fun item(url: String, mime: String) = PlayerData(
        episodeId = "plugin:archive-org:sex_madness::0",
        itemId = "plugin:archive-org:sex_madness",
        title = "Sex Madness",
        subtitle = "",
        mediaUrl = url,
        castUrl = null,
        artworkUrl = "",
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = SourceKind.PLUGIN,
        mime = mime,
    )

    private val ia = "https://archive.org/download/sex_madness/sex_madness.mp4"

    @Test fun `a direct https file goes through the LAN proxy as plain http`() {
        val registered = mutableListOf<String>()
        val url = dlnaPluginUri(item(ia, "video/mp4"), "192.168.2.10") {
            registered += it.mediaUrl
            "http://127.0.0.1:4100/t/tok/media.mp4"
        }
        assertEquals("http://192.168.2.10:4100/t/tok/media.mp4", url)
        assertEquals(listOf(ia), registered)
    }

    @Test fun `a direct https HLS goes through the LAN proxy too`() {
        val url = dlnaPluginUri(item("https://cdn.example/a/playlist.m3u8", MIME_HLS), "192.168.2.10") {
            "http://127.0.0.1:4100/t/tok/index.m3u8"
        }
        assertEquals("http://192.168.2.10:4100/t/tok/index.m3u8", url)
    }

    @Test fun `a proxied token url is only respelled for the LAN`() {
        val url = dlnaPluginUri(item("http://127.0.0.1:4100/t/tok/index.m3u8", MIME_HLS), "192.168.2.10") {
            error("already behind the proxy")
        }
        assertEquals("http://192.168.2.10:4100/t/tok/index.m3u8", url)
    }

    @Test fun `no LAN address, or a proxy that can't take it, sends nothing (never the raw url)`() {
        assertNull(dlnaPluginUri(item(ia, "video/mp4"), null) { "http://127.0.0.1:4100/t/tok/media.mp4" })
        assertNull(dlnaPluginUri(item(ia, "video/mp4"), "192.168.2.10") { null })
        assertNull(dlnaPluginUri(item("file:///sdcard/a.mp4", "video/mp4"), "192.168.2.10") { "x" })
    }
}
