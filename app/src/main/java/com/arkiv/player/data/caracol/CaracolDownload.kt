package com.arkiv.player.data.caracol

import org.json.JSONArray
import org.json.JSONObject

/**
 * What has to be remembered about a Caracol episode downloaded to the device.
 *
 * "It's on disk" isn't enough. A Caracol download isn't a file: it's the ENCRYPTED segments
 * inside media3's cache, and reopening them needs two pieces of data that can't be guessed later:
 *
 *  - [mpd]: the URL the cache was filled with. A cache is indexed by the URI it was written with,
 *    so playing with a different one —even pointing at the same video— fails EVERY byte and goes
 *    to the network silently. Resolving again doesn't guarantee the same URL.
 *  - [keys]: which quality was downloaded. The manifest keeps announcing all six; without this
 *    filter the selector picks by bandwidth and asks for one that isn't there. See [CaracolQuality].
 *
 * What it does NOT save is the video's key: it isn't had and can't be had. The bytes on disk are
 * encrypted just like on the CDN (`encv`/`sinf`/`tenc`), and what decrypts them is the device's
 * CDM, on play, requesting a fresh streaming license. That's why this "download" still needs a
 * few KB of network to open: Caracol's license server doesn't grant persistent licenses (measured,
 * see `CaracolOfflineProbe` in `src/debug`).
 */
data class CaracolDownload(
    val mpd: String,
    /** `period.group.track` of each downloaded track, in the order they were downloaded. */
    val keys: List<TrackKey>,
    /** Height in pixels of the downloaded video; 0 if unknown. It's for DISPLAY, not for deciding. */
    val height: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put(FIELD_MPD, mpd)
        put(FIELD_HEIGHT, height)
        put(FIELD_KEYS, JSONArray().apply { keys.forEach { put(it.text()) } })
    }.toString()

    companion object {
        private const val FIELD_MPD = "mpd"
        private const val FIELD_HEIGHT = "alto"
        private const val FIELD_KEYS = "claves"

        /** Extension of the little file that accompanies the download. See `DituDownloadStrategy`. */
        const val EXTENSION = "ditu.json"

        /** `null` if the text isn't an understandable record: without it the download can't be opened. */
        fun fromJson(text: String): CaracolDownload? {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val mpd = json.optString(FIELD_MPD).takeIf { it.isNotBlank() } ?: return null
            val arr = json.optJSONArray(FIELD_KEYS) ?: return null
            val keys = (0 until arr.length()).mapNotNull { TrackKey.fromText(arr.optString(it)) }
            if (keys.isEmpty()) return null
            return CaracolDownload(mpd = mpd, keys = keys, height = json.optInt(FIELD_HEIGHT, 0))
        }
    }
}

/** A DASH `StreamKey` with no dependency on media3, so it can be saved and tested. */
data class TrackKey(val period: Int, val group: Int, val track: Int) {
    fun text(): String = "$period.$group.$track"

    companion object {
        fun fromText(text: String): TrackKey? {
            val parts = text.trim().split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            return TrackKey(nums[0], nums[1], nums[2])
        }
    }
}
