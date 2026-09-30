package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.GatewayPlayable

/**
 * Whether a plugin's resolved Stream can be saved to the device: a progressive video (mp4, mkv,
 * ts, webm…) is written as is by [HttpRangeDownloader]; an HLS VOD stream is saved as one file by
 * [HlsDownloader] (which refuses on its own what only the playlist shows: live, SAMPLE-AES, a
 * separate-audio-only stream). A DASH or Smooth manifest is not (nothing here turns one into a
 * file), nor a DRM-protected stream (the license never lives on disk) or a live channel (it has
 * no end).
 *
 * Pure so the later SDK tasks only feed it: Widevine (`drm`, read from [GatewayPlayable.drmLicenseUrl])
 * and live channels (`live`) refuse here with the same sentence.
 */
object PluginDownloadEligibility {
    /** The one sentence the person reads for every refusal. */
    const val NOT_DOWNLOADABLE = "Este video no se puede descargar"

    /** Null when the stream can be saved (as a file or as HLS); otherwise [NOT_DOWNLOADABLE]. */
    fun refusal(url: String, mime: String = "", drm: Boolean = false, live: Boolean = false): String? = when {
        url.isBlank() -> NOT_DOWNLOADABLE
        drm || live -> NOT_DOWNLOADABLE
        isHls(url, mime) -> null
        isManifest(url, mime) -> NOT_DOWNLOADABLE
        else -> null
    }

    /** [refusal] over what the plugin's `resolve()` answered; [live] is the item's kind, not the stream's. */
    fun refusal(playable: GatewayPlayable, live: Boolean = false): String? =
        refusal(playable.url, playable.mime, drm = playable.drmLicenseUrl.isNotBlank(), live = live)

    /**
     * An HLS playlist by the URL's path (`.m3u8`, `.m3u`) or the declared mime (any `mpegurl`): saved
     * by [HlsDownloader]. What neither shows is caught from the response ([ManifestSniff]).
     */
    fun isHls(url: String, mime: String = ""): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return HLS_EXTENSIONS.any { path.endsWith(it) } || ManifestSniff.isHlsMime(mime)
    }

    /** DASH or Smooth Streaming (and HLS, checked first by [refusal]), by the URL's path or mime. */
    private fun isManifest(url: String, mime: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (MANIFEST_EXTENSIONS.any { path.endsWith(it) } || path.contains(".ism/")) return true
        return ManifestSniff.isManifestMime(mime)
    }

    private val HLS_EXTENSIONS = listOf(".m3u8", ".m3u")
    private val MANIFEST_EXTENSIONS = listOf(".mpd", ".ism", ".isml")
}
