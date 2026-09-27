package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.GatewayPlayable

/**
 * Whether a plugin's resolved Stream can be saved to the device by [HttpRangeDownloader], which
 * writes ONE file: a progressive video (mp4, mkv, ts, webm…) downloads; an HLS/DASH/Smooth manifest
 * does not (saving the manifest text would "complete" and then play a black screen), nor does a
 * DRM-protected stream (the license never lives on disk) or a live channel (it has no end).
 *
 * Pure so the later SDK tasks only feed it: Widevine (`drm`, read from [GatewayPlayable.drmLicenseUrl]
 * once plugin streams carry one) and live channels (`live`) refuse here with the same sentence.
 */
object PluginDownloadEligibility {
    /** The one sentence the person reads for every refusal. */
    const val NOT_DOWNLOADABLE = "Este video no se puede descargar"

    /** Null when the stream can be saved as one file; otherwise [NOT_DOWNLOADABLE]. */
    fun refusal(url: String, mime: String = "", drm: Boolean = false, live: Boolean = false): String? = when {
        url.isBlank() -> NOT_DOWNLOADABLE
        drm || live -> NOT_DOWNLOADABLE
        isManifest(url, mime) -> NOT_DOWNLOADABLE
        else -> null
    }

    /** [refusal] over what the plugin's `resolve()` answered; [live] is the item's kind, not the stream's. */
    fun refusal(playable: GatewayPlayable, live: Boolean = false): String? =
        refusal(playable.url, playable.mime, drm = playable.drmLicenseUrl.isNotBlank(), live = live)

    /**
     * HLS, DASH or Smooth Streaming, by the URL's path or by the declared mime. What neither shows
     * (an extensionless URL, no mime) is caught later from the response by [ManifestSniff].
     */
    private fun isManifest(url: String, mime: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (MANIFEST_EXTENSIONS.any { path.endsWith(it) } || path.contains(".ism/")) return true
        return ManifestSniff.isManifestMime(mime)
    }

    private val MANIFEST_EXTENSIONS = listOf(".m3u8", ".m3u", ".mpd", ".ism", ".isml")
}
