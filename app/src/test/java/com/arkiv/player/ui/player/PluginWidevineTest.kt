package com.arkiv.player.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import com.arkiv.player.data.gateway.GatewayPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's Widevine stream on its way to the player: what a resolved playable becomes, what the
 * media item's DRM block carries, and which player errors are the DRM session's. (The license
 * request itself goes through the plugin's host-gated client: `PluginStreamHttpTest` covers that
 * gate, and `PluginLicenseHttpTest` what the license request carries on the wire.)
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

    @Test fun `Widevine is allowed only when the device confirms L3 after being asked for it`() {
        assertTrue(PluginWidevine.widevineAllowed("L3"))
        // L1 would demand a secure decoder on the TextureView (measured process abort): closed.
        listOf("L1", "L2", "", "l3", " L3", "L3 ", "L30", null).forEach { level ->
            assertFalse("$level", PluginWidevine.widevineAllowed(level))
        }
    }

    /** A MediaDrm stand-in answering [level] for the security level; its release() does [onRelease]. */
    private fun fakeDrm(level: String?, onRelease: () -> Unit = {}): androidx.media3.exoplayer.drm.ExoMediaDrm =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.media3.exoplayer.drm.ExoMediaDrm::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPropertyString" -> level ?: throw IllegalStateException("no such property")
                "release" -> { onRelease(); null }
                else -> null
            }
        } as androidx.media3.exoplayer.drm.ExoMediaDrm

    @Test fun `a device confirming L3 keeps its own instance, nothing is refused`() {
        var refused = false
        val drm = fakeDrm("L3")
        assertSame(drm, PluginWidevine.confirmSoftwareLevel(drm) { refused = true })
        assertFalse(refused)
    }

    /** An OEM CDM throwing from release() must still end as the Spanish DRM message, not escape the provider. */
    @Test fun `a refused level fails closed even when releasing the instance throws`() {
        var refused = false
        val out = PluginWidevine.confirmSoftwareLevel(fakeDrm("L1") { throw IllegalStateException("CDM") }) { refused = true }
        assertTrue(out is androidx.media3.exoplayer.drm.DummyExoMediaDrm)
        assertTrue(refused)
    }

    @Test fun `the crash tag names the refused L3 stably, else media3's code name`() {
        assertEquals("plugin-drm-l3-unavailable", PluginWidevine.crashTag("plugin", PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR, softwareLevelRefused = true))
        assertEquals("plugin-drm-ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED", PluginWidevine.crashTag("plugin", PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, softwareLevelRefused = false))
        assertEquals("plugin-drm-ERROR_CODE_DRM_SYSTEM_ERROR", PluginWidevine.crashTag("plugin", PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR, softwareLevelRefused = false))
    }

    @Test fun `an item without a drm block gets no session manager at all, so a side audio file next to a protected video stays clear`() {
        val neverOpened = DataSource.Factory { throw AssertionError("a clear item must not open a license connection") }
        val provider = PluginWidevine.sessionManagerProvider(neverOpened)
        assertSame(DrmSessionManager.DRM_UNSUPPORTED, provider.get(MediaItem.EMPTY))
        assertSame(DrmSessionManager.DRM_UNSUPPORTED, provider.get(MediaItem.Builder().build()))
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
