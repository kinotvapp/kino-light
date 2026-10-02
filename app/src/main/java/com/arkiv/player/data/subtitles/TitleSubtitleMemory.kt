package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.LangTokens
import com.arkiv.player.playback.TrackLang

/**
 * The subtitle the person picked by hand on a title, kept per title so "Continuar" brings it back.
 *
 * Found on the Redmi (2026-10-01): a Xuper title reopened with "Continuar" came back with its audio
 * but not its subtitle. The audio "comes back" only because a hand pick promotes its language to
 * the top of the global order; the subtitle's pick promotes too, but [com.arkiv.player.playback.SubtitleDecision]
 * turns subtitles off whenever the audio is a language the person understands -- Spanish audio with
 * Spanish or English subtitles on was always reopened with them off, and a subtitle turned off by
 * hand was never remembered at all.
 *
 * [OFF] or the track's menu label. Pure (encode/decode/restore), stored by [SubtitlePrefs].
 */
object TitleSubtitleMemory {
    /** The value of a title whose subtitles were turned off by hand. */
    const val OFF = "<off>"

    /** Titles kept; the oldest picks are dropped past this. */
    const val MAX_TITLES = 300

    /** [entries] (oldest first) with [title] set to [choice] and moved last, capped at [MAX_TITLES]. */
    fun remember(entries: List<Pair<String, String>>, title: String, choice: String): List<Pair<String, String>> =
        (entries.filter { it.first != title } + (title to clean(choice))).takeLast(MAX_TITLES)

    /**
     * Which of [spuTracks] (id, label) to turn on for a title last left at [choice]: -1 for [OFF],
     * the track with the same label, else the first one in the same language; null when nothing
     * matches (the usual decision then applies).
     */
    fun restore(choice: String?, spuTracks: List<Pair<Int, String>>): Int? {
        if (choice == null) return null
        if (choice == OFF) return -1
        spuTracks.firstOrNull { it.second == choice }?.let { return it.first }
        val lang = LangTokens.classify(choice).takeIf { it != TrackLang.UNKNOWN } ?: return null
        return spuTracks.firstOrNull { LangTokens.classify(it.second) == lang }?.first
    }

    fun encode(entries: List<Pair<String, String>>): String =
        entries.joinToString("\n") { (title, choice) -> clean(title) + "\t" + clean(choice) }

    fun decode(raw: String?): List<Pair<String, String>> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toList()

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ')
}
