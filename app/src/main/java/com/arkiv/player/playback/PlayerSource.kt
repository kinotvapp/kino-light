package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { UNKNOWN, MAGIS, LOCAL, LIVE, DITU }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
    val referer: String? = null,      // headers for web streams (some hosts require Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,     // web: proxied fallback URL if the direct one fails (403/geo/anti-leech)
    /**
     * Extra headers the origin requires, beyond Referer and User-Agent.
     *
     * Exists because magis serves its VOD behind `Content-Auth` and `Content-License`, and libVLC
     * only exposed `:http-referrer` and `:http-user-agent` -- there was no way to send it an
     * arbitrary header. These still travel through the local proxy, which can put them on the
     * request to the origin.
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Start by SOFTWARE decoding instead of hardware.
     *
     * Exists for magis's HEVC titles: on device, the hardware decoder often failed to initialise
     * with this content and libVLC used to respond by dropping every track (`pistas=v0/a0`) -- no
     * picture, no sound -- while the demuxer kept draining the file. The same titles play fine by
     * software. Knowing this ahead of time skips the failed attempt and the ~10s of black screen
     * the automatic rescue used to take to kick in.
     */
    val preferSoftware: Boolean = false,
) {
    /** All of the origin's headers in a single map, for whoever can send them all at once. */
    val allHeaders: Map<String, String>
        get() = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            putAll(extraHeaders)
        }
}

object PlayerSource {
    /**
     * Prefix of a live channel (Task 14): `episodeId = "live:<code>"`, the same `code` that
     * [com.arkiv.player.ui.live.LiveController.open] receives. Lives here (instead of repeated as
     * a string literal at each call site) because both whoever builds the navigation route
     * (ArkivRoot/ArkivTvRoot) and whoever reads it (PlayerViewModel) have to agree.
     */
    const val LIVE_PREFIX = "live:"

    /**
     * Is [episodeId] a live channel, from any source? Magis's (`live:`, see [LIVE_PREFIX])
     * or Caracol's ([DituLive]).
     *
     * `PlayerScreen` hangs off this whatever is common to any live stream: no progress bar or
     * seek, no position to save, no "next episode" on finish. What's specific to Magis's live
     * (zapping, drawer and channel sheet, reopening on cuts) still asks for [SourceKind.LIVE].
     */
    fun isLiveChannel(episodeId: String): Boolean =
        kindFor(episodeId) == SourceKind.LIVE || DituLive.isLive(episodeId)

    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        // Caracol (Ditu). Ids with this prefix are built by `DituEntities`, when saving a Caracol
        // title to the library, and `DituLive`, for a live channel: their `PREFIX` has to
        // start with this.
        episodeId.startsWith("ditu:") -> SourceKind.DITU
        episodeId.startsWith(LIVE_PREFIX) -> SourceKind.LIVE
        // UNKNOWN covers ids from sources removed from this branch (torrent, archive.org, web): the
        // player answers them with a "no longer available" error, see PlayerViewModel.loadUnknownSource.
        else -> SourceKind.UNKNOWN
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)

/**
 * Encodes/decodes the [PlayerSourceTag] fields that cross the controller→session IPC boundary as a
 * plain map, free of `android.os.Bundle` so the round trip is testable without Robolectric (this
 * project has none -- same convention as `LocalFilePaths`/`FreeSpacePolicy` and friends). The
 * `android.os.Bundle` is only touched at the very edges, by `PlayerScreen.localMediaItems` (writing,
 * via [PlayerSourceTag.toIpcBundle]) and `PlaybackService.MediaItemResolverCallback` (reading).
 */
internal object PlayerSourceTagIpc {

    fun encode(tag: PlayerSourceTag): Map<String, Any> = buildMap {
        put("kind", tag.kind.name)
        tag.referer?.let { put("referer", it) }
        tag.userAgent?.let { put("userAgent", it) }
        tag.castUrl?.let { put("castUrl", it) }
        tag.proxyUrl?.let { put("proxyUrl", it) }
        tag.openingStartMs?.let { put("openingStartMs", it) }
        tag.openingEndMs?.let { put("openingEndMs", it) }
        tag.endingStartMs?.let { put("endingStartMs", it) }
        if (tag.preferSoftware) put("preferSoftware", true)
    }

    /**
     * Null when [extras] never went through [encode] (no `"kind"` key) -- same guard
     * `MediaItemResolverCallback` used to run directly against the `Bundle`.
     */
    fun decode(extras: Map<String, Any?>): PlayerSourceTag? {
        if ("kind" !in extras) return null
        return PlayerSourceTag(
            kind = runCatching { SourceKind.valueOf(extras["kind"] as String) }.getOrDefault(SourceKind.UNKNOWN),
            openingStartMs = extras["openingStartMs"] as? Long,
            openingEndMs = extras["openingEndMs"] as? Long,
            endingStartMs = extras["endingStartMs"] as? Long,
            castUrl = extras["castUrl"] as? String,
            referer = extras["referer"] as? String,
            userAgent = extras["userAgent"] as? String,
            proxyUrl = extras["proxyUrl"] as? String,
            // If a field gets added to the tag, it has to be wired HERE and in [encode]: the tag
            // doesn't cross the IPC boundary itself, and whatever is missing arrives at its default,
            // in silence. This happened once: preferSoftware stayed false and magis's HEVC kept
            // opening in hardware.
            preferSoftware = extras["preferSoftware"] as? Boolean ?: false,
        )
    }
}

/** The thin `Bundle` adapter around [PlayerSourceTagIpc.encode], for [MediaItem.RequestMetadata]. */
fun PlayerSourceTag.toIpcBundle(): android.os.Bundle = android.os.Bundle().apply {
    PlayerSourceTagIpc.encode(this@toIpcBundle).forEach { (key, value) ->
        when (value) {
            is String -> putString(key, value)
            is Long -> putLong(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }
}

/** The thin `Bundle` adapter around [PlayerSourceTagIpc.decode], for `MediaSession.Callback`. */
internal fun PlayerSourceTagIpc.decodeFromBundle(extras: android.os.Bundle?): PlayerSourceTag? {
    if (extras == null) return null
    return decode(extras.keySet().associateWith { extras.get(it) })
}
