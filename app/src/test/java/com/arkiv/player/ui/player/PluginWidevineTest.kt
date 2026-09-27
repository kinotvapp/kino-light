package com.arkiv.player.ui.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.arkiv.player.data.gateway.GatewayPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's Widevine stream on its way to the player: what a resolved playable becomes, what the
 * media item's DRM block carries, and which player errors are the DRM session's. (The license
 * request itself goes through the plugin's host-gated client: `PluginStreamHttpTest` covers that
 * gate; the wiring is `StreamExoPlayer`'s, verified on a device.)
 */
class PluginWidevineTest {

    private val protected = GatewayPlayable(
        kind = "plugin", url = "https://cdn.example/v.mpd",
        drmLicenseUrl = "https://lic.example/wv", drmLicenseHeaders = mapOf("Authorization" to "Bearer t"),
    )

    @Test fun `a resolved playable with a license url becomes the player's drm, a clear one none`() {
        assertEquals(ResolvedDrm("https://lic.example/wv", mapOf("Authorization" to "Bearer t")), pluginDrm(protected))
        assertNull(pluginDrm(protected.copy(drmLicenseUrl = "")))
        assertNull(pluginDrm(GatewayPlayable(kind = "plugin", url = "https://cdn.example/v.mp4")))
    }

    @Test fun `the media item's drm block is Widevine with the license request headers, one session`() {
        val config = PluginWidevine.drmConfiguration(ResolvedDrm("https://lic.example/wv", mapOf("Authorization" to "Bearer t", "X-Custom" to "1")))
        assertEquals(C.WIDEVINE_UUID, config.scheme)
        assertEquals(mapOf("Authorization" to "Bearer t", "X-Custom" to "1"), config.licenseRequestHeaders)
        assertFalse(config.multiSession)
        assertFalse(config.forceDefaultLicenseUri)
    }

    @Test fun `a DRM session error is the one Spanish message, any other error is not`() {
        listOf(
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        ).forEach { code -> assertTrue("$code", PluginWidevine.isDrmError(code)) }
        listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        ).forEach { code -> assertFalse("$code", PluginWidevine.isDrmError(code)) }
        assertEquals("No se pudo abrir este video protegido", PluginWidevine.ERROR_MESSAGE)
    }
}
