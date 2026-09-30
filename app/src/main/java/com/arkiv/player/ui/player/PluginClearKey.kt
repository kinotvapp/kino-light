package com.arkiv.player.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import java.util.Base64

/**
 * How [StreamExoPlayer] plays an own M3U channel's ClearKey-protected stream ([ResolvedClearKey],
 * from a `#KODIPROP:inputstream.adaptive.license_key`, see
 * [com.arkiv.player.data.live.M3uEntry]): the key never leaves the device. There is no license
 * server -- [LocalMediaDrmCallback] answers the CDM's key request locally with the W3C Clear Key
 * JSON built from the hex kid/key pair the list carried. Unlike [PluginWidevine] this has no
 * security-level gate: ClearKey has no notion of a secure decoder, so it plays on the same
 * `TextureView` path as any clear stream.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PluginClearKey {
    /**
     * The W3C Clear Key license response media3's ClearKey CDM expects, from a hex kid/key pair.
     * Null for anything that isn't valid hex of an even length: a malformed `#KODIPROP` line plays
     * unprotected (fails to decode) rather than crashing the player.
     */
    fun responseJson(keyId: String, key: String): String? {
        val k = hexToBase64Url(key) ?: return null
        val kid = hexToBase64Url(keyId) ?: return null
        return """{"keys":[{"kty":"oct","k":"$k","kid":"$kid"}],"type":"temporary"}"""
    }

    private fun hexToBase64Url(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The media item's DRM block: ClearKey, no license URI -- the key travels in
     *  [sessionManagerProvider]'s callback, never fetched per item. */
    fun drmConfiguration(): MediaItem.DrmConfiguration = MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()

    /**
     * A session manager built fresh per media item from [keyId]/[key], same shape as
     * [PluginWidevine.sessionManagerProvider]: an item with no DRM block (the merged audio track
     * [StreamExoPlayer] adds alongside a video's own DRM) gets [DrmSessionManager.DRM_UNSUPPORTED].
     * Null when [keyId]/[key] aren't valid hex -- no session opens, ClearKey playback fails as a
     * DRM error instead of getting a broken callback.
     */
    fun sessionManagerProvider(keyId: String, key: String): DrmSessionManagerProvider? {
        val json = responseJson(keyId, key) ?: return null
        return DrmSessionManagerProvider { item ->
            if (item.localConfiguration?.drmConfiguration == null) return@DrmSessionManagerProvider DrmSessionManager.DRM_UNSUPPORTED
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setSessionKeepaliveMs(C.TIME_UNSET)
                .build(LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8)))
        }
    }
}

/** An own M3U channel's ClearKey pair (hex), see [com.arkiv.player.data.live.M3uEntry]. */
data class ResolvedClearKey(val keyId: String, val key: String)

/** An own M3U channel's ClearKey, or null for a clear stream (every other provider). */
internal fun pluginClearKey(playable: com.arkiv.player.data.gateway.GatewayPlayable): ResolvedClearKey? =
    playable.drmClearKey.takeIf { it.isNotEmpty() }?.let { ResolvedClearKey(playable.drmClearKeyId, it) }
