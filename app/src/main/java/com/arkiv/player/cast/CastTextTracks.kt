package com.arkiv.player.cast

/**
 * One external subtitle the source gave for the title (a Xuper portal file, a plugin's `subtitles`),
 * as the phone's player loads it: [format] `srt`/`vtt` when the source said, "" = guess.
 */
data class CastSubtitleSource(val lang: String, val url: String, val format: String = "")

/**
 * One subtitle as a Cast receiver is told about it. [index] is its place in the source's list,
 * which is also what its URLs and the phone's track id ([CastTextTracks.PHONE_ID_PREFIX]) carry.
 */
data class CastTextTrack(val id: Long, val index: Int, val language: String, val name: String, val url: String)

/**
 * How the subtitles reach the receiver.
 *
 * - [SIDECAR]: each is a `MediaTrack` (TYPE_TEXT, text/vtt) in the load's `MediaInfo`, the way the
 *   Cast docs show for a progressive MP4. The receiver fetches the WebVTT itself.
 * - [MANIFEST]: each is an `#EXT-X-MEDIA:TYPE=SUBTITLES` rendition in the remux's own master playlist
 *   (`RemuxHls.masterPlaylist`), with a subtitle playlist that mirrors the video's segments.
 *
 * The remux is an HLS EVENT playlist while it is written, and Shaka (the receiver's HLS player) treats
 * EVENT as live; Shaka refuses an out-of-band text track on a live presentation
 * (`CANNOT_ADD_EXTERNAL_TEXT_TO_LIVE_STREAM`), so a sidecar track is the risky one there and the
 * in-manifest rendition, which Shaka loads like any other stream, the reliable one. Everything else
 * (an mp4 through a proxy, a plugin's own HLS) gets [SIDECAR]: its manifest is not ours to change.
 * `adb shell setprop debug.kino.cast_subs sidecar|manifest|off` overrides it for a device test.
 */
enum class CastSubtitleDelivery { SIDECAR, MANIFEST, OFF }

/** What the phone's subtitle menu has on, in the terms the cast can map back to a source. */
sealed interface CastTextSelection {
    /** The phone's player has not reported its text tracks yet: say nothing, keep what was chosen. */
    data object Unknown : CastTextSelection

    /** "Desactivar", or a subtitle the cast cannot carry (one embedded in the file). */
    data object Off : CastTextSelection

    /** A text track: its `Format.id` and language, as the phone's player reports them. */
    data class On(val formatId: String?, val language: String?) : CastTextSelection
}

/** What the receiver reports about one of its tracks (`MediaStatus.mediaInfo.mediaTracks`). */
data class ReceiverTrack(
    val id: Long,
    val isText: Boolean,
    val language: String?,
    val name: String?,
    val contentId: String?,
)

/** Pure decisions about the cast's text tracks. */
object CastTextTracks {

    /**
     * The receiver's id of subtitle `i` is `ID_BASE + i`. Far from the small ids the receiver gives
     * the tracks it finds in the media itself, so a sidecar never collides with them.
     */
    const val ID_BASE = 1000L

    /** At most this many subtitles go to a TV: a source listing 40 languages would bloat the load. */
    const val MAX_TRACKS = 12

    /**
     * The `Format.id` the phone's player gives external subtitle `i` (set by `toExoSubtitleConfigs`):
     * what maps the phone's menu choice back to the source's list without guessing by language.
     */
    const val PHONE_ID_PREFIX = "kino-sub:"

    fun idOf(index: Int): Long = ID_BASE + index

    fun phoneIdOf(index: Int): String = PHONE_ID_PREFIX + index

    /** The delivery for a load, [override] being the debug property's value (null/blank = none). */
    fun deliveryFor(hlsFmp4: Boolean, override: String?): CastSubtitleDelivery = when (override?.trim()?.lowercase()) {
        "off" -> CastSubtitleDelivery.OFF
        "sidecar" -> CastSubtitleDelivery.SIDECAR
        "manifest" -> if (hlsFmp4) CastSubtitleDelivery.MANIFEST else CastSubtitleDelivery.SIDECAR
        else -> if (hlsFmp4) CastSubtitleDelivery.MANIFEST else CastSubtitleDelivery.SIDECAR
    }

    /**
     * The tracks for [sources] (at most [cap]), [urlFor] giving each one's URL on the LAN (null = it
     * can't be served, and it is left out). Names are what the receiver's own menu shows, in Spanish,
     * and never repeat: two "Español" become "Español" and "Español (2)".
     */
    fun build(sources: List<CastSubtitleSource>, urlFor: (Int) -> String?, cap: Int = MAX_TRACKS): List<CastTextTrack> {
        val seen = HashMap<String, Int>()
        return sources.take(cap).mapIndexedNotNull { i, s ->
            val url = urlFor(i) ?: return@mapIndexedNotNull null
            val base = displayName(s.lang, i)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            CastTextTrack(idOf(i), i, languageTag(s.lang), if (n == 1) base else "$base ($n)", url)
        }
    }

    /**
     * Which source the phone's [selection] is, or null for none. The phone id first (exact); then
     * the first source in the same language, for a player that dropped the id.
     */
    fun indexOf(selection: CastTextSelection, sources: List<CastSubtitleSource>): Int? {
        val on = selection as? CastTextSelection.On ?: return null
        // `contains`, not `startsWith`: a merging source may prefix what it reports.
        on.formatId?.takeIf { it.contains(PHONE_ID_PREFIX) }?.substringAfterLast(PHONE_ID_PREFIX)?.toIntOrNull()
            ?.takeIf { it in sources.indices }?.let { return it }
        val lang = on.language?.takeIf { it.isNotBlank() } ?: return null
        val wanted = baseLanguage(languageTag(lang))
        if (wanted == "und") return null
        return sources.indexOfFirst { baseLanguage(languageTag(it.lang)) == wanted }.takeIf { it >= 0 }
    }

    /**
     * Which of the receiver's [tracks] is subtitle [index] (named [name], in [language]), or null.
     * By our own id (sidecar), then by the URL it was loaded from, then by name, then by language
     * when only one text track has it (an in-manifest rendition gets an id the receiver picks).
     */
    fun receiverIdOf(tracks: List<ReceiverTrack>, index: Int, name: String, language: String): Long? {
        val text = tracks.filter { it.isText }
        text.firstOrNull { it.id == idOf(index) }?.let { return it.id }
        text.firstOrNull { it.contentId?.let { c -> urlIndex(c) == index } == true }?.let { return it.id }
        text.firstOrNull { it.name == name }?.let { return it.id }
        val sameLang = text.filter { it.language != null && baseLanguage(it.language) == baseLanguage(language) }
        return sameLang.singleOrNull()?.id
    }

    /**
     * The receiver's new active track ids so exactly [textId] (null = none) is the text track on,
     * every other active track (the audio, the video) kept as it is; null when that is already so.
     */
    fun activeIdsFor(active: LongArray, tracks: List<ReceiverTrack>, textId: Long?): LongArray? {
        val textIds = tracks.filter { it.isText }.map { it.id }.toSet()
        val kept = active.filter { it !in textIds }
        val target = if (textId == null) kept else kept + textId
        if (target.toSet() == active.toSet()) return null
        return target.toLongArray()
    }

    /**
     * The subtitle index a URL of ours names: `…/<index>.vtt`, `…/<index>.srt`, `…/subs<index>.m3u8`
     * or a segment `…/<index>/<from>-<to>.vtt`. Null for anything else.
     */
    internal fun urlIndex(url: String): Int? {
        val path = url.substringBefore('?')
        val last = path.substringAfterLast('/')
        Regex("^subs(\\d+)\\.m3u8$").find(last)?.let { return it.groupValues[1].toIntOrNull() }
        if (!path.contains("/s/")) return null
        Regex("^(\\d+)\\.(vtt|srt)$").find(last)?.let { return it.groupValues[1].toIntOrNull() }
        if (Regex("^\\d+-\\d+\\.vtt$").matches(last)) {
            return path.substringBeforeLast('/').substringAfterLast('/').toIntOrNull()
        }
        return null
    }

    /**
     * A BCP-47 tag for what a source calls its language: an ISO code as it is (`es`, `es-419`), the
     * three-letter codes and the usual names mapped (`spa`, `Español`, `Spanish` → `es`), `und` when
     * there is nothing to go on.
     */
    fun languageTag(lang: String): String {
        val t = lang.trim()
        if (t.isEmpty()) return "und"
        val lower = t.lowercase().replace('_', '-')
        if (Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$").matches(lower)) {
            val primary = lower.substringBefore('-')
            val mapped = ISO3_TO_ISO1[primary] ?: primary
            val rest = lower.substringAfter('-', "")
            return if (rest.isEmpty()) mapped else "$mapped-${rest.uppercase().takeIf { it.length == 2 } ?: rest}"
        }
        val word = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        NAMES.entries.firstOrNull { (k, _) -> word.startsWith(k) }?.let { return it.value }
        return "und"
    }

    private fun baseLanguage(tag: String): String = tag.lowercase().substringBefore('-')

    /**
     * What the receiver's menu calls subtitle [index]: the language in Spanish ("Español
     * (Latinoamérica)", "Inglés") for a code, the source's own words for anything else.
     */
    fun displayName(lang: String, index: Int): String {
        val t = lang.trim()
        if (t.isEmpty()) return "Subtítulos ${index + 1}"
        val tag = languageTag(t)
        if (tag == "und") return t.replaceFirstChar { it.uppercase() }
        val isCode = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$").matches(t)
        if (!isCode) return t.replaceFirstChar { it.uppercase() }
        val locale = java.util.Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(SPANISH)
        return name.takeIf { it.isNotBlank() && !it.equals(tag, true) }
            ?.replaceFirstChar { it.uppercase() }
            ?: t
    }

    private val SPANISH = java.util.Locale("es")

    private val ISO3_TO_ISO1 = mapOf(
        "spa" to "es", "eng" to "en", "por" to "pt", "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de", "ita" to "it", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh", "rus" to "ru", "ara" to "ar", "dut" to "nl", "nld" to "nl",
    )

    /** Language names (accents stripped, lowercase) a source writes instead of a code. */
    private val NAMES = linkedMapOf(
        "espanol" to "es", "castellano" to "es", "spanish" to "es", "latino" to "es-419",
        "ingles" to "en", "english" to "en", "portugues" to "pt", "portuguese" to "pt",
        "frances" to "fr", "french" to "fr", "aleman" to "de", "german" to "de",
        "italiano" to "it", "italian" to "it", "japones" to "ja", "japanese" to "ja",
        "coreano" to "ko", "korean" to "ko",
    )
}
