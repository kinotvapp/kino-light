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
 * Widevine is asked for at security level **L3** (software), on purpose, and plays ONLY if the CDM
 * confirms it. `StreamExoPlayer` paints on a `TextureView` -- its aspect fitting, zoom and frame
 * capture all hang off it -- and an L1 session demands a secure decoder that cannot output to one:
 * `main` measured on the Fire Stick that a secure buffer on a `TextureView` aborts the whole process
 * (see `DituExoPlayer`, which moved to a `SurfaceView` for exactly that). An L3 session never asks
 * for a secure decoder, so a protected plugin stream takes the same decoder and surface path a
 * clear one does. The level is read back after being set ([widevineAllowed]): a device that stays
 * at L1, or won't say, gets NO session on this player -- the playback fails with [ERROR_MESSAGE]
 * (tagged [L3_UNAVAILABLE_TAG] once) rather than risk the abort. The price: a license server that
 * grants L3 nothing, or only SD, plays nothing or SD -- the guide says so. Caracol keeps its own L1
 * player, untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PluginWidevine {
    /** What the person reads when the DRM session fails: a refused or unreachable license, a device without Widevine or without L3, an expired key. */
    const val ERROR_MESSAGE = "No se pudo abrir este video protegido"

    /** The stable Sentry tag suffix of "this device would not run Widevine at L3, so nothing was opened"; see [crashTag]. */
    const val L3_UNAVAILABLE_TAG = "l3-unavailable"

    /** Widevine's own MediaDrm property and the software level; see this object's KDoc. */
    private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
    private const val SOFTWARE_LEVEL = "L3"

    /**
     * Whether a Widevine session may be opened on this player's `TextureView`: only when the CDM
     * reports exactly [SOFTWARE_LEVEL] after being asked for it. Anything else -- "L1", a blank, a
     * differently-cased or padded value, or null when the property could not be set or read -- fails
     * closed: no session, no secure decoder, no abort.
     */
    fun widevineAllowed(securityLevelAfterSet: String?): Boolean = securityLevelAfterSet == SOFTWARE_LEVEL

    /**
     * The Sentry tag of a DRM failure: `<prefix>-drm-l3-unavailable` when the device refused L3
     * (stable, whatever error code media3 then produced), else `<prefix>-drm-<media3 code name>`.
     */
    fun crashTag(prefix: String, errorCode: Int, softwareLevelRefused: Boolean): String =
        if (softwareLevelRefused) "$prefix-drm-$L3_UNAVAILABLE_TAG" else "$prefix-drm-${PlaybackException.getErrorCodeName(errorCode)}"

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
     * through [licenseHttp]: for a plugin, `PluginHttpFactories.license`, a bare factory over the
     * SAME host-gated OkHttp client the manifest and segments use, so a license request can no more
     * reach an undeclared host, plain http or the home network than a segment can, yet carries only
     * the item's `licenseHeaders` and none of the Stream's `headers`. media3's own `DefaultDrmSessionManagerProvider` would open a plain, ungated
     * `DefaultHttpDataSource` for it, and gives no way to ask for L3.
     *
     * [onSoftwareLevelRefused] fires (on the playback thread, at most once per prepare) when the
     * device would not confirm L3: the session then fails without ever opening (see
     * [softwareWidevine]) and the player's error handler tags the report [L3_UNAVAILABLE_TAG].
     */
    fun sessionManagerProvider(licenseHttp: DataSource.Factory, onSoftwareLevelRefused: () -> Unit = {}): DrmSessionManagerProvider =
        DrmSessionManagerProvider { item ->
            val config = item.localConfiguration?.drmConfiguration
                ?: return@DrmSessionManagerProvider DrmSessionManager.DRM_UNSUPPORTED
            val callback = HttpMediaDrmCallback(config.licenseUri?.toString(), config.forceDefaultLicenseUri, licenseHttp)
            config.licenseRequestHeaders.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(config.scheme) { uuid -> softwareWidevine(uuid, onSoftwareLevelRefused) }
                .setMultiSession(config.multiSession)
                // As Caracol's player: the session is released the moment it is unused, instead of media3's
                // 5-minute keepalive (the Fire Stick has one secure path; see DituExoPlayer for the measurement).
                .setSessionKeepaliveMs(C.TIME_UNSET)
                .build(callback)
        }

    /**
     * A fresh [FrameworkMediaDrm] asked for [SOFTWARE_LEVEL] and READ BACK: it is handed over only
     * when [widevineAllowed] says so, and it is the very instance the session will open on (no
     * probe-then-use gap). Anything else -- the property refused or unreadable, the level still L1,
     * or no Widevine at all -- yields the [DummyExoMediaDrm] media3 itself uses for an unsupported
     * scheme: its `openSession` throws, so no MediaDrm session and no secure decoder ever exist on
     * this player, and the playback fails as a DRM error (`ERROR_CODE_DRM_SYSTEM_ERROR`) that the
     * caller words in Spanish. Fresh on every call: a manager prepared again after a release must
     * never get back an instance already released.
     */
    private fun softwareWidevine(uuid: UUID, onSoftwareLevelRefused: () -> Unit): ExoMediaDrm {
        val drm = try {
            FrameworkMediaDrm.newInstance(uuid)
        } catch (e: UnsupportedDrmException) {
            Log.w(TAG, "Widevine is not available on this device: no session", e)
            onSoftwareLevelRefused()
            return DummyExoMediaDrm()
        }
        val level = runCatching {
            drm.setPropertyString(SECURITY_LEVEL_PROPERTY, SOFTWARE_LEVEL)
            drm.getPropertyString(SECURITY_LEVEL_PROPERTY)
        }.onFailure { Log.w(TAG, "could not set or read Widevine's $SECURITY_LEVEL_PROPERTY", it) }.getOrNull()
        if (widevineAllowed(level)) return drm
        Log.w(TAG, "Widevine would run at ${level ?: "an unknown level"}, not $SOFTWARE_LEVEL: no session on this player")
        drm.release()
        onSoftwareLevelRefused()
        return DummyExoMediaDrm()
    }
}
