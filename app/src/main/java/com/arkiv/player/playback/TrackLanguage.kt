package com.arkiv.player.playback

/**
 * Normalized language buckets. Dictionary lifted from Alfa's `set_lang()` (unify.py:504): collapses
 * the dozens of variants that show up in track names ("Track 1 - [Spanish]", "Español
 * (Latinoamérica)", "Castellano", "Dual", "es-419"…) into a few buckets. Serves both audio AND
 * subtitles, hence the generic name.
 */
enum class TrackLang { LATINO, CASTELLANO, SPANISH, DUAL, ENGLISH, JAPANESE, UNKNOWN }

/** Free-text language classifier. Ported from Alfa/Balandro's dictionary. */
object LangTokens {
    // HEADS UP: Java/Kotlin's `\w` does NOT match `ñ`/accents → explicit classes [nñ] are used and
    // unaccented forms are accepted too (releases usually drop them: "Espanol", "Castellano").
    private val LATINO = Regex("latinoameric|\\blatino\\b|\\blat\\b|es-?419|espa[nñ]ol\\s*lat|audio\\s*lat|m[eé]xic")
    private val CASTELLANO = Regex("castellan|castilian|espa[nñ]a\\b|\\bspain\\b|es-?es\\b|\\bcast\\b")
    private val DUAL = Regex("\\bdual\\b|multi[- ]?audio|\\bmulti\\b")
    private val ENGLISH = Regex("\\benglish\\b|\\bingl[eé]s\\b|\\beng\\b|en-?us\\b|en-?gb\\b|\\[en\\]")
    private val JAPANESE = Regex("\\bjapanese\\b|\\bjapon[eé]s\\b|\\bjap\\b|\\bjpn?\\b|\\bvose\\b|\\bvo\\b")
    private val SPANISH = Regex("espa[nñ]ol|\\bspanish\\b|\\bspa\\b|\\besp\\b|castellano|latino|\\[es\\]|\\bes-?\\d*\\b")

    /** Variants a Spanish speaker understands the same way: if one is on the list, all of them count. */
    private val SPANISH_FAMILY = setOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH)

    /**
     * Language suffix right before the subtitle extension (`movie.es.srt` → `es`). Searched with
     * the extension glued on purpose: it isolates a token that is UNAMBIGUOUSLY a language code,
     * and that's why two-letter codes like `en` can be accepted here — looking for them in free
     * text would hit any "Audio EN español".
     */
    // 2..4 letters: covers `es`, `spa`, `jpn` and also `cast`, which is in CODES.
    private val FILE_SUFFIX = Regex("\\.([a-z]{2,4}(?:-[a-z0-9]{2,4})?)\\.(?:srt|ass|ssa|sub|vtt)\\b")

    private val CODES = mapOf(
        "es" to TrackLang.SPANISH, "spa" to TrackLang.SPANISH, "esp" to TrackLang.SPANISH,
        "lat" to TrackLang.LATINO, "es-419" to TrackLang.LATINO, "es-mx" to TrackLang.LATINO,
        "cast" to TrackLang.CASTELLANO, "es-es" to TrackLang.CASTELLANO,
        "en" to TrackLang.ENGLISH, "eng" to TrackLang.ENGLISH,
        "ja" to TrackLang.JAPANESE, "jp" to TrackLang.JAPANESE, "jpn" to TrackLang.JAPANESE,
    )

    /** Classifies a track / release name into the most specific bucket possible. */
    fun classify(raw: String): TrackLang {
        val s = raw.lowercase()
        return when {
            LATINO.containsMatchIn(s) -> TrackLang.LATINO
            CASTELLANO.containsMatchIn(s) -> TrackLang.CASTELLANO
            DUAL.containsMatchIn(s) -> TrackLang.DUAL
            SPANISH.containsMatchIn(s) -> TrackLang.SPANISH
            JAPANESE.containsMatchIn(s) -> TrackLang.JAPANESE
            ENGLISH.containsMatchIn(s) -> TrackLang.ENGLISH
            else -> TrackLang.UNKNOWN
        }
    }

    /**
     * Classifies an EXTERNAL track by the language suffix of its file name. libVLC used to
     * name `addSlave` tracks by their path, and this still works for any standalone `.srt` that
     * arrives that way (the torrent source that motivated this was removed in this branch's
     * pruning). If there's no recognizable suffix, it falls back to [classify] on the whole name.
     */
    fun classifyFileName(raw: String): TrackLang {
        val s = raw.lowercase()
        FILE_SUFFIX.find(s)?.groupValues?.get(1)?.let { code -> CODES[code]?.let { return it } }
        return classify(s)
    }

    /**
     * Classifies a lone language CODE (`es`, `es-419`, `jpn`), like the ones web sources declare
     * next to the subtitle URL. Same as in [classifyFileName], here the token comes isolated, so a
     * two-letter `en` is accepted too. If it's not a known code it falls back to [classify], in
     * case the source sent the written-out name ("Español"). Empty → [TrackLang.UNKNOWN].
     */
    fun classifyCode(raw: String): TrackLang {
        val c = raw.trim().lowercase()
        if (c.isEmpty()) return TrackLang.UNKNOWN
        return CODES[c] ?: classify(c)
    }

    /**
     * Does [lang] count as "a language I understand", given my [order] list? The Spanish variants
     * are interchangeable: with `Latino > Castellano` configured, a track labeled just "Spanish"
     * has to count as one's own — otherwise subtitles would turn on over audio that's understood.
     */
    fun satisfies(lang: TrackLang, order: List<TrackLang>): Boolean =
        lang in order || (lang in SPANISH_FAMILY && order.any { it in SPANISH_FAMILY })
}

/**
 * Track selection by preferred language — Arkiv's EXCLUSIVE edge over Alfa/Balandro (which only
 * pick a language at the SOURCE level, not per track inside the container). Works the same for
 * audio and subtitles; the only thing that changes is the list passed in.
 */
object TrackSelector {
    /** Default order for an es-LatAm audience (matches animeLangPriority). */
    val DEFAULT_AUDIO = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.DUAL)

    /**
     * Track id to select per [order], or null if nothing matches (→ leave the player's default
     * track). Ignores the "Disable" pseudo-track (id<0).
     *
     * [requireChoice] = true (audio): with only one real track there's nothing to choose, so it
     * returns null rather than fighting the player over a decision that doesn't exist. false
     * (subtitles): a single Japanese subtitle DOES need to be turned on.
     *
     * [classifier] lets [LangTokens.classifyFileName] be passed in, for external tracks.
     */
    fun select(
        tracks: List<Pair<Int, String>>,
        order: List<TrackLang>,
        requireChoice: Boolean = true,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): Int? {
        val real = tracks.filter { it.first >= 0 }
        if (real.isEmpty()) return null
        if (requireChoice && real.size <= 1) return null
        val classed = real.map { it.first to classifier(it.second) }
        for (pref in order) {
            classed.firstOrNull { it.second == pref }?.let { return it.first }
            // Generic Spanish is a wildcard for the Hispanic preferences.
            if (pref == TrackLang.LATINO || pref == TrackLang.CASTELLANO) {
                classed.firstOrNull { it.second == TrackLang.SPANISH }?.let { return it.first }
            }
        }
        return null
    }
}
