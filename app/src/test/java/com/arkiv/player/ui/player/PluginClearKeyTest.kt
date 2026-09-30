package com.arkiv.player.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DrmSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * An own M3U channel's ClearKey key on its way to the player: the W3C Clear Key JSON built from
 * the hex kid/key pair `M3uParser` captured, and the media item's DRM block. No license server is
 * involved -- `PluginClearKeyTest` never opens a socket.
 */
class PluginClearKeyTest {

    @Test fun `a hex kid-key pair becomes the W3C Clear Key response, base64url without padding`() {
        // "ab" -> bytes [0xAB] -> base64url "qw" (no padding).
        assertEquals(
            """{"keys":[{"kty":"oct","k":"qw","kid":"qw"}],"type":"temporary"}""",
            PluginClearKey.responseJson("ab", "ab"),
        )
    }

    @Test fun `odd-length or non-hex input is rejected, not half-encoded`() {
        assertNull(PluginClearKey.responseJson("abc", "ab"))
        assertNull(PluginClearKey.responseJson("ab", "zz"))
        assertNull(PluginClearKey.responseJson("", "ab"))
    }

    @Test fun `the media item's drm block is ClearKey`() {
        assertEquals(C.CLEARKEY_UUID, PluginClearKey.drmConfiguration().scheme)
    }

    @Test fun `no session manager for an invalid key, so ClearKey fails closed instead of crashing`() {
        assertNull(PluginClearKey.sessionManagerProvider("zz", "ab"))
    }

    @Test fun `a media item with no drm config gets DRM_UNSUPPORTED, same as PluginWidevine`() {
        val provider = PluginClearKey.sessionManagerProvider("ab", "ab")
        assertNotNull(provider)
        val clearItem = androidx.media3.common.MediaItem.Builder().setUri("https://example.com/v.m3u8").build()
        assertSame(DrmSessionManager.DRM_UNSUPPORTED, provider!!.get(clearItem))
    }
}
