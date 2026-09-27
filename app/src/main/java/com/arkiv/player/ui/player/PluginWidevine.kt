package com.arkiv.player.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import java.util.UUID

private const val TAG = "PluginWidevine"

/**
 * How [StreamExoPlayer] plays a plugin's Widevine-protected stream ([ResolvedDrm], apiVersion 2's
 * `drm` capability): the media item's DRM block, the session manager whose license request goes
 * through the plugin's own host-gated client, and the one Spanish sentence a DRM failure becomes.
 *
 * Widevine is asked for at security level **L3** (software), on purpose. `StreamExoPlayer` paints on
 * a `TextureView` -- its aspect fitting, zoom and frame capture all hang off it -- and an L1 session
 * demands a secure decoder that cannot output to one: `main` measured on the Fire Stick that a
 * secure buffer on a `TextureView` aborts the whole process (see `DituExoPlayer`, which moved to a
 * `SurfaceView` for exactly that). An L3 session never asks for a secure decoder, so a protected
 * plugin stream takes the same decoder and surface path a clear one does. The price: a license
 * server that grants L3 nothing, or only SD, plays nothing or SD -- the guide says so. Caracol keeps
 * its own L1 player, untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PluginWidevine {
    /** What the person reads when the DRM session fails: a refused or unreachable license, a device without Widevine, an expired key. */
    const val ERROR_MESSAGE = "No se pudo abrir este video protegido"

    /** Widevine's own MediaDrm property and the software level; see this object's KDoc. */
    private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
    private const val SOFTWARE_LEVEL = "L3"

    /** The media item's DRM block: Widevine, the license URL and the headers sent with the license request. One session per stream. */
    fun drmConfiguration(drm: ResolvedDrm): MediaItem.DrmConfiguration =
        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(drm.licenseUrl)
            .setLicenseRequestHeaders(drm.licenseHeaders)
            .setMultiSession(false)
            .build()

    /** Whether a player error is the DRM session's (`ERROR_CODE_DRM_*`, the 6000s), as opposed to the network's, the container's or a decoder's. */
    fun isDrmError(errorCode: Int): Boolean =
        errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

    /**
     * The DRM sessions of every media source a plugin stream's factory builds: read from the item's
     * own [MediaItem.DrmConfiguration] -- none means clear ([DrmSessionManager.DRM_UNSUPPORTED]),
     * which is what the merged audio tracks and the subtitles get -- with the license fetched
     * through [licenseHttp], the SAME host-gated OkHttp factory the manifest and segments use, so a
     * license request can no more reach an undeclared host, plain http or the home network than a
     * segment can. media3's own `DefaultDrmSessionManagerProvider` would open a plain, ungated
     * `DefaultHttpDataSource` for it, and gives no way to ask for L3.
     */
    fun sessionManagerProvider(licenseHttp: DataSource.Factory): DrmSessionManagerProvider = DrmSessionManagerProvider { item ->
        val config = item.localConfiguration?.drmConfiguration
            ?: return@DrmSessionManagerProvider DrmSessionManager.DRM_UNSUPPORTED
        val callback = HttpMediaDrmCallback(config.licenseUri?.toString(), config.forceDefaultLicenseUri, licenseHttp)
        config.licenseRequestHeaders.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(config.scheme) { uuid -> softwareWidevine(uuid) }
            .setMultiSession(config.multiSession)
            // As Caracol's player: the session is released the moment it is unused, instead of media3's
            // 5-minute keepalive (the Fire Stick has one secure path; see DituExoPlayer for the measurement).
            .setSessionKeepaliveMs(C.TIME_UNSET)
            .build(callback)
    }

    /** [FrameworkMediaDrm] asked for [SOFTWARE_LEVEL]; a device that refuses the property keeps its default, one without Widevine gets the dummy (the session then fails as `ERROR_CODE_DRM_SCHEME_UNSUPPORTED`). */
    private fun softwareWidevine(uuid: UUID): ExoMediaDrm = try {
        FrameworkMediaDrm.newInstance(uuid).also { drm ->
            runCatching { drm.setPropertyString(SECURITY_LEVEL_PROPERTY, SOFTWARE_LEVEL) }
                .onFailure { Log.w(TAG, "could not ask Widevine for $SOFTWARE_LEVEL, keeping the device's level", it) }
        }
    } catch (e: UnsupportedDrmException) {
        Log.w(TAG, "Widevine is not available on this device", e)
        DummyExoMediaDrm()
    }
}
