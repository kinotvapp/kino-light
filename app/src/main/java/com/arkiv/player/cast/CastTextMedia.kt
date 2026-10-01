package com.arkiv.player.cast

import android.os.Bundle
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle

/**
 * The Cast SDK side of the cast's subtitles: how a [CastTextLoad] rides inside a media3 `MediaItem`
 * to [DurationAwareMediaItemConverter] (the request metadata's extras, the one bag that survives the
 * conversion), the `MediaTrack`s and style the receiver is given, and what it reports back.
 */
internal object CastTextMedia {

    private const val KEY_IDS = "arkiv.textIds"
    private const val KEY_URLS = "arkiv.textUrls"
    private const val KEY_LANGS = "arkiv.textLangs"
    private const val KEY_NAMES = "arkiv.textNames"
    private const val KEY_ACTIVE = "arkiv.textActive"

    /** Puts [load]'s sidecar tracks in [extras]; nothing for any other delivery. */
    fun put(extras: Bundle, load: CastTextLoad?) {
        if (load == null || load.delivery != CastSubtitleDelivery.SIDECAR || load.tracks.isEmpty()) return
        extras.putLongArray(KEY_IDS, load.tracks.map { it.id }.toLongArray())
        extras.putStringArray(KEY_URLS, load.tracks.map { it.url }.toTypedArray())
        extras.putStringArray(KEY_LANGS, load.tracks.map { it.language }.toTypedArray())
        extras.putStringArray(KEY_NAMES, load.tracks.map { it.name }.toTypedArray())
        load.activeId?.let { extras.putLongArray(KEY_ACTIVE, longArrayOf(it)) }
    }

    /** The sidecar `MediaTrack`s [extras] carries, or null when it carries none. */
    fun tracks(extras: Bundle?): List<MediaTrack>? {
        val ids = extras?.getLongArray(KEY_IDS) ?: return null
        val urls = extras.getStringArray(KEY_URLS) ?: return null
        val langs = extras.getStringArray(KEY_LANGS) ?: return null
        val names = extras.getStringArray(KEY_NAMES) ?: return null
        if (ids.isEmpty() || urls.size != ids.size || langs.size != ids.size || names.size != ids.size) return null
        return ids.indices.map { i ->
            MediaTrack.Builder(ids[i], MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(urls[i])
                .setContentType("text/vtt")
                .setName(names[i])
                .apply { if (langs[i] != "und") setLanguage(langs[i]) }
                .build()
        }
    }

    /** The text track to start with, as the queue item's active ids; null = none on. */
    fun active(extras: Bundle?): LongArray? = extras?.getLongArray(KEY_ACTIVE)?.takeIf { it.isNotEmpty() }

    /**
     * Readable on any TV: white text on a semi-transparent black box, normal size, sans-serif. The
     * receiver's default can be small text with no background, unreadable over a bright scene.
     */
    fun style(): TextTrackStyle = TextTrackStyle().apply {
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x99000000.toInt()
        fontScale = 1.0f
        fontGenericFamily = TextTrackStyle.FONT_FAMILY_SANS_SERIF
        edgeType = TextTrackStyle.EDGE_TYPE_NONE
        windowType = TextTrackStyle.WINDOW_TYPE_NONE
    }

    /**
     * A system property through `getprop` (`debug.*` ones are settable from `adb shell setprop`), or
     * null when unset. Spawns a process: never on the main thread.
     */
    fun systemProperty(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val value = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        value.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** What the receiver reports about its tracks, in [CastTextTracks]' terms. */
    fun receiverTracks(tracks: List<MediaTrack>?): List<ReceiverTrack> =
        tracks.orEmpty().map { ReceiverTrack(it.id, it.type == MediaTrack.TYPE_TEXT, it.language, it.name, it.contentId) }
}
