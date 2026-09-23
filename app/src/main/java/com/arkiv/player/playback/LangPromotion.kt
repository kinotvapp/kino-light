package com.arkiv.player.playback

/**
 * Manually picking a track in the player bumps that language to the top of the preference -- but
 * ONLY if there was a real choice. Without this safeguard, a film that only came in English would
 * leave English on top forever, and the next dual-language film would start in English without
 * anyone asking for it.
 */
object LangPromotion {

    /** New order with the picked language on top, or null if promotion doesn't apply. */
    fun promote(
        order: List<TrackLang>,
        pickedName: String,
        allNames: List<String>,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): List<TrackLang>? {
        val picked = classifier(pickedName)
        if (picked == TrackLang.UNKNOWN) return null
        // Was there an alternative? With a single language in the file, picking it isn't a preference.
        if (allNames.map(classifier).distinct().size < 2) return null
        if (order.firstOrNull() == picked) return null
        return listOf(picked) + order.filter { it != picked }
    }
}

/**
 * Edits to the ordered language list. Lives here, pure and tested, because the phone
 * (`material3`) and the TV (`androidx.tv.material3`) can't share composables but do have to behave
 * the same way.
 */
object LangOrderEdits {

    /** Appends it at the end if it's not there; removes it if it is. Never leaves the list empty. */
    fun toggle(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = when {
        lang !in order -> order + lang
        order.size <= 1 -> order
        else -> order - lang
    }

    fun moveUp(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, -1)

    fun moveDown(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, +1)

    private fun swap(order: List<TrackLang>, lang: TrackLang, delta: Int): List<TrackLang> {
        val i = order.indexOf(lang)
        val j = i + delta
        if (i < 0 || j !in order.indices) return order
        return order.toMutableList().apply { this[i] = this[j].also { this[j] = this[i] } }
    }
}
