package com.arkiv.player.data.local

import com.arkiv.player.playback.LangTokens
import com.arkiv.player.playback.TrackLang

/** What the container says about one audio track (container order): its menu label, language code, default flag. */
data class AudioTrackInfo(val label: String?, val language: String?, val isDefault: Boolean)

/**
 * Which audio tracks a downloaded MP4 keeps: at most [MAX_TRACKS] (the owner's rule for 0.9.46:
 * "que se descarguen máximo 3 lenguajes y si no el original"). Pure.
 *
 * - [MAX_TRACKS] tracks or fewer: all of them, untouched.
 * - The slots go first to what must stay: the track the person picked by hand for this title
 *   ([pickedLabel]) and the title's original audio (the one flagged default, else the first).
 * - The rest goes to the person's preferred languages ([preferred], in their order), every distinct
 *   track of a language counting (Latino and Castellano are two). Tracks of a preferred language
 *   come in file order within it.
 * - When no preferred language exists in the file, the original (plus the pick) is followed by up
 *   to two more in file order.
 *
 * The result is the kept tracks' indexes, ordered by the person's preference (the first preference
 * first, so it becomes the MP4's default audio), then what is not preferred (the original, a pick)
 * in file order: the original stays even when it lands last.
 */
object Mp4AudioKeep {
    const val MAX_TRACKS = 3

    fun select(tracks: List<AudioTrackInfo>, preferred: List<TrackLang>, pickedLabel: String? = null): List<Int> {
        if (tracks.size <= MAX_TRACKS) return tracks.indices.toList()
        val classes = tracks.map { classOf(it) }
        val rank = classes.map { rankOf(it, preferred) }

        val original = tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        val picked = pickedLabel?.let { pickedIndex(tracks, classes, it) }
        val chosen = linkedSetOf<Int>()
        picked?.let { chosen += it }
        chosen += original

        val matching = tracks.indices.filter { rank[it] != NONE }.sortedWith(compareBy({ rank[it] }, { it }))
        val fill = if (matching.isNotEmpty()) matching else tracks.indices.toList()
        for (i in fill) {
            if (chosen.size >= MAX_TRACKS) break
            chosen += i
        }
        return chosen.sortedWith(compareBy({ rank[it] }, { it }))
    }

    private const val NONE = Int.MAX_VALUE

    /** Position of the first preference [lang] satisfies, [NONE] when it matches none. */
    private fun rankOf(lang: TrackLang, preferred: List<TrackLang>): Int {
        val i = preferred.indexOfFirst { matches(it, lang) }
        return if (i < 0) NONE else i
    }

    /** A preference matches its own language; a generic Spanish track also stands for Latino and Castellano, and the other way round. */
    private fun matches(pref: TrackLang, lang: TrackLang): Boolean = when {
        pref == TrackLang.UNKNOWN || lang == TrackLang.UNKNOWN -> false
        pref == lang -> true
        pref == TrackLang.LATINO || pref == TrackLang.CASTELLANO -> lang == TrackLang.SPANISH
        pref == TrackLang.SPANISH -> lang == TrackLang.LATINO || lang == TrackLang.CASTELLANO
        else -> false
    }

    private fun classOf(t: AudioTrackInfo): TrackLang {
        t.label?.takeIf { it.isNotBlank() }?.let { l -> LangTokens.classify(l).takeIf { it != TrackLang.UNKNOWN }?.let { return it } }
        return t.language?.takeIf { it.isNotBlank() }?.let { LangTokens.classifyCode(it) } ?: TrackLang.UNKNOWN
    }

    /** The track with exactly the picked label, else the first one in the picked label's language. */
    private fun pickedIndex(tracks: List<AudioTrackInfo>, classes: List<TrackLang>, label: String): Int? {
        tracks.indexOfFirst { it.label == label }.takeIf { it >= 0 }?.let { return it }
        val lang = LangTokens.classify(label).takeIf { it != TrackLang.UNKNOWN } ?: return null
        return classes.indexOfFirst { it == lang }.takeIf { it >= 0 }
    }
}
