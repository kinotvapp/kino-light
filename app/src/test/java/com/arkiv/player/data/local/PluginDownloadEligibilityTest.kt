package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.GatewayPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure "can this resolved plugin stream be saved as a file" decision. Later SDK tasks (DRM,
 * live channels) only feed it; the sentence the person reads never changes.
 */
class PluginDownloadEligibilityTest {

    private val refused = PluginDownloadEligibility.NOT_DOWNLOADABLE

    @Test fun `a progressive file downloads`() {
        assertNull(PluginDownloadEligibility.refusal("https://cdn.example/v.mp4"))
        assertNull(PluginDownloadEligibility.refusal("https://cdn.example/v?token=1", mime = "video/mp4"))
        assertNull(PluginDownloadEligibility.refusal("https://cdn.example/v.mkv", mime = "video/x-matroska"))
        // An unknown extension with no mime is still one file: the downloader saves it as mp4.
        assertNull(PluginDownloadEligibility.refusal("https://cdn.example/stream/42"))
    }

    @Test fun `an HLS manifest is refused, by url or by mime`() {
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/master.m3u8"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/MASTER.M3U8?token=1#x"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/list.m3u"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v", mime = "application/vnd.apple.mpegurl"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v", mime = "application/x-mpegURL"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v", mime = "audio/mpegurl"))
    }

    @Test fun `a DASH or Smooth Streaming manifest is refused`() {
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v.mpd"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v", mime = "application/dash+xml"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v.ism/manifest"))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v", mime = "application/vnd.ms-sstr+xml"))
    }

    @Test fun `a DRM or live stream is refused whatever its url`() {
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v.mp4", drm = true))
        assertEquals(refused, PluginDownloadEligibility.refusal("https://cdn.example/v.mp4", live = true))
    }

    @Test fun `a blank url is refused`() {
        assertEquals(refused, PluginDownloadEligibility.refusal(""))
        assertEquals(refused, PluginDownloadEligibility.refusal("   "))
    }

    @Test fun `the refusal is the one sentence the person reads`() {
        assertEquals("Este video no se puede descargar", PluginDownloadEligibility.NOT_DOWNLOADABLE)
    }

    @Test fun `a resolved playable feeds the same decision`() {
        val file = GatewayPlayable(kind = "plugin", url = "https://cdn.example/v.mp4", mime = "video/mp4")
        assertNull(PluginDownloadEligibility.refusal(file))
        assertEquals(refused, PluginDownloadEligibility.refusal(file.copy(url = "https://cdn.example/v.m3u8")))
        // A Widevine license URL on the playable is DRM (what Task 7 will fill for plugin streams).
        assertEquals(refused, PluginDownloadEligibility.refusal(file.copy(drmLicenseUrl = "https://lic.example/wv")))
        assertEquals(refused, PluginDownloadEligibility.refusal(file, live = true))
    }
}
