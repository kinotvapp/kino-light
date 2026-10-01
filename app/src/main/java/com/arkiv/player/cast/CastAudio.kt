package com.arkiv.player.cast

/**
 * One audio track as a player sees it: the three things a remux can find it again by.
 *
 * [id] is the `Format.id`, which for an MPEG-TS is `<program>/<PID>` -- the same on the phone's
 * player and inside the remux, because both parse the same bytes with the same `TsExtractor`.
 */
data class AudioTrackRef(val id: String?, val language: String?, val label: String?)

/**
 * The audio the person has on the phone, to be the ONE audio track the cast carries.
 *
 * Why it has to be carried at all: the Chromecast never gets the title's own audio tracks. An
 * MPEG-TS goes out remuxed into an MP4 by media3's Transformer (see `TsRemuxer`), and Transformer
 * writes exactly one audio track -- whichever its own track selector picks, which knew nothing
 * about the phone's menu. So the TV played that one (usually the first in the file) whatever the
 * phone showed, and picking another audio on the phone changed only the phone.
 *
 * [ordinal] is its index among the container's audio tracks as the phone's player lists them; it
 * is also what the remux is keyed by (`RemuxPolicy.keyFrom`), so each audio gets its own file.
 */
data class CastAudioChoice(
    val ordinal: Int,
    val id: String?,
    val language: String?,
    val label: String?,
)

/** How a cast gets its audio, which decides whether the phone's audio menu can change it. */
enum class CastAudioRoute {
    /**
     * Remuxed on this phone with the chosen track (a Magis or downloaded MPEG-TS): another audio
     * means another remux and reloading the receiver.
     */
    REMUX,

    /**
     * Anything the receiver fetches as it is (an mp4, the HLS-segment fallback, a live channel):
     * the Default Media Receiver plays the file's default audio and offers no way to switch it
     * for a progressive file or a transport stream, so the phone's menu cannot reach the TV.
     */
    FIXED,
}

/** What to do when the phone's audio choice changes while casting. */
enum class CastAudioSwitch {
    /** Nothing to do: not casting, or the TV already has this audio. */
    NONE,

    /** Remux again with the new track and reload the receiver once it is ready. */
    REMUX_AND_RELOAD,

    /** The TV cannot follow; tell the person it only changed on the phone. */
    PHONE_ONLY,
}

object CastAudio {

    /**
     * Which of [candidates] (the remux input's audio tracks, in the order its extractor reports
     * them) is [choice], or null to leave the selection to the default.
     *
     * In order of trust:
     * 1. the same `Format.id` -- exact for a transport stream, where it names the PID;
     * 2. the same position, when the track there does not contradict the choice's language (the
     *    phone and the remux read the same file, so the order matches unless something upstream
     *    merged tracks in);
     * 3. the first track in the same language.
     */
    fun indexIn(candidates: List<AudioTrackRef>, choice: CastAudioChoice?): Int? {
        if (choice == null || candidates.isEmpty()) return null
        choice.id?.takeIf { it.isNotBlank() }?.let { id ->
            val i = candidates.indexOfFirst { it.id == id }
            if (i >= 0) return i
        }
        candidates.getOrNull(choice.ordinal)?.let { atOrdinal ->
            if (languagesAgree(atOrdinal.language, choice.language)) return choice.ordinal
        }
        val lang = choice.language?.takeIf { it.isNotBlank() } ?: return null
        return candidates.indexOfFirst { sameLanguage(it.language, lang) }.takeIf { it >= 0 }
    }

    /**
     * The decision when the phone's audio moves from [onTv] to [wanted] (ordinals, null = the
     * default track) during a cast that gets its audio by [route].
     */
    fun onChoiceChanged(casting: Boolean, route: CastAudioRoute, onTv: Int?, wanted: Int?): CastAudioSwitch = when {
        !casting -> CastAudioSwitch.NONE
        onTv == wanted -> CastAudioSwitch.NONE
        route == CastAudioRoute.REMUX -> CastAudioSwitch.REMUX_AND_RELOAD
        else -> CastAudioSwitch.PHONE_ONLY
    }

    /**
     * Where the receiver resumes after a [CastAudioSwitch.REMUX_AND_RELOAD], given the person was
     * at [positionMs] in the title.
     *
     * Where the person was, for both routes. A remux used to restart from 0: cast as one
     * progressive fragmented MP4 it had no index to seek by (asked to start at 4:52 the receiver
     * went hunting and never played a frame, 2026-09-12). It is served as HLS now
     * (`RemuxHlsServer`), where a start point is just a segment, so the new audio's remux is loaded
     * at the position once it has got there -- the same wait as a first cast from a resume point.
     */
    fun reloadStartMs(route: CastAudioRoute, positionMs: Long): Long = when (route) {
        CastAudioRoute.REMUX, CastAudioRoute.FIXED -> positionMs.coerceAtLeast(0L)
    }

    /** Null or blank on either side agrees with anything: a track with no language can be any. */
    private fun languagesAgree(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        return sameLanguage(a, b)
    }

    /** `es` and `es-419`, or `spa` and `es`, are the same language for this purpose. */
    private fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return base(a) == base(b)
    }

    private fun base(code: String): String {
        val primary = code.trim().lowercase().substringBefore('-').substringBefore('_')
        return ISO3_TO_ISO1[primary] ?: primary
    }

    /** The three-letter codes a transport stream's PMT carries, for the languages Magis serves. */
    private val ISO3_TO_ISO1 = mapOf(
        "spa" to "es", "eng" to "en", "por" to "pt", "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de", "ita" to "it", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh",
    )
}
