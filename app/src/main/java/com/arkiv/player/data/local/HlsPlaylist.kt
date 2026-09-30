package com.arkiv.player.data.local

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An HLS playlist can never be saved as the offline copy, for a reason that will not change on a
 * retry: live (no `EXT-X-ENDLIST`), SAMPLE-AES or any other DRM key, a separate-audio-only
 * stream, an init section that changes mid-stream, something that is not HLS at all. Not an
 * `IOException` on purpose, like [ManifestResponseException]: [DownloadRetryPolicy.isTransient]
 * must never retry it, and the strategy turns it into the permanent "Este video no se puede
 * descargar". [detail] is for the log only; the person always reads the one sentence.
 */
class HlsRefusedException(val detail: String) : RuntimeException(PluginDownloadEligibility.NOT_DOWNLOADABLE)

/** A byte range of a resource: `EXT-X-BYTERANGE` / a `BYTERANGE` attribute. */
data class HlsByteRange(val length: Long, val offset: Long) {
    /** The HTTP `Range` value for it (inclusive end). */
    fun header(): String = "bytes=$offset-${offset + length - 1}"
}

/** `EXT-X-KEY` in force for a segment: METHOD=AES-128 only ([HlsPlaylistParser] refuses the rest). */
data class HlsKey(val uri: String, val iv: ByteArray?) {
    override fun equals(other: Any?) = other is HlsKey && uri == other.uri && iv.contentEqualsOrNull(other.iv)
    override fun hashCode() = uri.hashCode() * 31 + (iv?.contentHashCode() ?: 0)
    private fun ByteArray?.contentEqualsOrNull(o: ByteArray?) = if (this == null) o == null else o != null && contentEquals(o)
}

/** `EXT-X-MAP`: an fMP4 stream's initialization section. */
data class HlsInit(val uri: String, val range: HlsByteRange?)

data class HlsSegment(
    val uri: String,
    val durationSec: Double,
    /** Media sequence number: the default AES-128 IV. */
    val sequence: Long,
    val range: HlsByteRange? = null,
    val key: HlsKey? = null,
    val init: HlsInit? = null,
    val discontinuity: Boolean = false,
)

data class HlsMediaPlaylist(val segments: List<HlsSegment>, val endList: Boolean) {
    val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
    /** The init section every segment shares, or null (MPEG-TS). */
    val init: HlsInit? get() = segments.firstOrNull()?.init
}

data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val height: Int?,
    val codecs: String?,
    val audioGroup: String?,
)

/** An `EXT-X-MEDIA:TYPE=AUDIO`; [uri] null = the audio travels inside the variant itself. */
data class HlsAudioRendition(val groupId: String, val uri: String?)

data class HlsMasterPlaylist(val variants: List<HlsVariant>, val audio: List<HlsAudioRendition>)

/** Either kind of playlist, parsed. */
sealed interface HlsPlaylist {
    data class Master(val playlist: HlsMasterPlaylist) : HlsPlaylist
    data class Media(val playlist: HlsMediaPlaylist) : HlsPlaylist
}

/**
 * Parses the HLS tags an offline copy needs (RFC 8216): master (`EXT-X-STREAM-INF`, audio
 * `EXT-X-MEDIA`) and media playlists (`EXTINF`, `EXT-X-BYTERANGE`, `EXT-X-MAP`, `EXT-X-KEY`,
 * `EXT-X-MEDIA-SEQUENCE`, `EXT-X-DISCONTINUITY`, `EXT-X-ENDLIST`). Every URI is resolved against
 * [baseUrl] (the playlist's final URL). Pure: tested with strings.
 *
 * Refuses ([HlsRefusedException]) what can never be saved: a key other than NONE/AES-128 or with
 * a non-identity KEYFORMAT (SAMPLE-AES, Widevine/FairPlay…). Live and map changes are the
 * caller's check ([HlsDownloadPlan]), since they are properties of the whole list.
 */
object HlsPlaylistParser {
    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) throw HlsRefusedException("not an HLS playlist")
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) HlsPlaylist.Master(master(lines, baseUrl))
        else HlsPlaylist.Media(media(lines, baseUrl))
    }

    private fun master(lines: List<String>, base: String): HlsMasterPlaylist {
        val variants = mutableListOf<HlsVariant>()
        val audio = mutableListOf<HlsAudioRendition>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["TYPE"] == "AUDIO") audio += HlsAudioRendition(a["GROUP-ID"].orEmpty(), a["URI"]?.let { resolve(base, it) })
                }
                line.startsWith("#") -> Unit
                else -> pending?.let { a ->
                    variants += HlsVariant(
                        uri = resolve(base, line),
                        bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                        codecs = a["CODECS"],
                        audioGroup = a["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return HlsMasterPlaylist(variants, audio)
    }

    private fun media(lines: List<String>, base: String): HlsMediaPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var sequence = 0L
        var duration: Double? = null
        var range: HlsByteRange? = null
        var key: HlsKey? = null
        var init: HlsInit? = null
        var discontinuity = false
        var endList = false
        // The implicit offset of an EXT-X-BYTERANGE without "@o": right after the previous sub-range of the SAME resource.
        val nextOffset = HashMap<String, Long>()
        var pendingRange: Pair<Long, Long?>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = byteRange(line.substringAfter(':'))
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> discontinuity = true
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-KEY:") -> key = key(attributes(line.substringAfter(':')), base)
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val uri = a["URI"] ?: throw HlsRefusedException("EXT-X-MAP without URI")
                    init = HlsInit(resolve(base, uri), a["BYTERANGE"]?.let { br -> byteRange(br).let { (l, o) -> HlsByteRange(l, o ?: 0L) } })
                }
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(base, line)
                    range = pendingRange?.let { (length, offset) ->
                        val start = offset ?: nextOffset[uri] ?: 0L
                        nextOffset[uri] = start + length
                        HlsByteRange(length, start)
                    }
                    segments += HlsSegment(uri, duration ?: 0.0, sequence, range, key, init, discontinuity)
                    sequence++
                    duration = null
                    pendingRange = null
                    discontinuity = false
                }
            }
        }
        return HlsMediaPlaylist(segments, endList)
    }

    private fun key(a: Map<String, String>, base: String): HlsKey? {
        val method = a["METHOD"]?.uppercase() ?: "NONE"
        if (method == "NONE") return null
        val format = a["KEYFORMAT"]
        if (method != "AES-128" || (format != null && format != "identity")) throw HlsRefusedException("key $method/${format ?: "identity"}")
        val uri = a["URI"] ?: throw HlsRefusedException("AES-128 key without URI")
        return HlsKey(resolve(base, uri), a["IV"]?.let(::iv))
    }

    /** `0x…` → 16 bytes (left-padded); not hex, or longer than 128 bits, is a refusal (it never decrypts). */
    private fun iv(hex: String): ByteArray {
        val digits = hex.trim().removePrefix("0x").removePrefix("0X")
        if (digits.isEmpty() || digits.length > 32 || digits.any { Character.digit(it, 16) < 0 }) throw HlsRefusedException("bad IV $hex")
        val padded = digits.padStart(32, '0')
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** `n[@o]`. */
    private fun byteRange(v: String): Pair<Long, Long?> {
        val length = v.substringBefore('@').trim().toLongOrNull() ?: throw HlsRefusedException("bad BYTERANGE $v")
        val offset = if ('@' in v) v.substringAfter('@').trim().toLongOrNull() else null
        return length to offset
    }

    /** `KEY=VALUE,KEY="quoted, value"` → map, quotes removed. */
    internal fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val name = s.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val close = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, comma).trim()
                j = comma
            }
            out[name.uppercase()] = value
            i = if (j < s.length && s[j] == ',') j + 1 else j
        }
        return out
    }

    internal fun resolve(base: String, uri: String): String =
        base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
}

/**
 * Which variant of a master playlist is saved. There is no persisted quality choice in the app,
 * so: the highest height ≤ [MAX_HEIGHT] (then the highest bandwidth), else the lowest above it.
 * Only variants whose audio is inside them: an `AUDIO` group whose every rendition has its own URI
 * means the video variant is silent and the sound is a separate stream, which is not muxed here --
 * if that is all there is, [HlsRefusedException] rather than a silent copy. Audio-only variants
 * (codecs naming no video) are never picked.
 */
object HlsVariantPicker {
    const val MAX_HEIGHT = 1080

    fun pick(master: HlsMasterPlaylist): HlsVariant {
        val video = master.variants.filterNot { isAudioOnly(it) }
        if (video.isEmpty()) throw HlsRefusedException("no video variant")
        val muxed = video.filter { hasMuxedAudio(it, master.audio) }
        if (muxed.isEmpty()) throw HlsRefusedException("audio only as a separate rendition")
        val fitting = muxed.filter { (it.height ?: 0) <= MAX_HEIGHT }
        return fitting.maxWithOrNull(compareBy<HlsVariant>({ it.height ?: 0 }, { it.bandwidth }))
            ?: muxed.minWith(compareBy<HlsVariant>({ it.height ?: Int.MAX_VALUE }, { it.bandwidth }))
    }

    private fun hasMuxedAudio(v: HlsVariant, audio: List<HlsAudioRendition>): Boolean {
        val group = v.audioGroup ?: return true
        val renditions = audio.filter { it.groupId == group }
        return renditions.isEmpty() || renditions.any { it.uri == null }
    }

    private fun isAudioOnly(v: HlsVariant): Boolean {
        val codecs = v.codecs?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return false
        return codecs.isNotEmpty() && codecs.all { c -> AUDIO_CODECS.any { c.startsWith(it) } }
    }

    private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "mp3", "flac", "ac-4", ".mp3")
}

/**
 * What a media playlist must be to be saved as one file, checked before a byte of video is
 * fetched: VOD ([HlsMediaPlaylist.endList]), at least one segment, one init section at most.
 */
object HlsDownloadPlan {
    fun check(media: HlsMediaPlaylist) {
        if (!media.endList) throw HlsRefusedException("live playlist (no EXT-X-ENDLIST)")
        if (media.segments.isEmpty()) throw HlsRefusedException("no segments")
        if (media.segments.map { it.init }.distinct().size > 1) throw HlsRefusedException("EXT-X-MAP changes mid-stream")
    }
}
