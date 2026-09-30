package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.text.Normalizer

/**
 * Drops the live channels of a plugin's search answer whose name has nothing to do with what was
 * asked. Some channel plugins fall back to their whole channel list when no name matches (seen:
 * "FAST TV" answered "Breaking Bad" with 40 Pluto TV channels), which buries the real results.
 *
 * Only channels are judged: a movie or series a scraper returns may rightly carry another title
 * (another language, a translated name), so those are always kept. The rule follows
 * `kino.rank.filterRelevant` (prelude.js), loosened for half-typed names (see [titleTokens]): a
 * channel stays only when its name carries most ([MIN_RELEVANCE]) of the 3+ character words of
 * some form of the query (what was typed, TMDB's original title, the other known titles). A query
 * with no such word identifies nothing, so nothing is dropped.
 */
internal object LiveSearchRelevance {
    private const val MIN_RELEVANCE = 0.6
    private val MARKS = Regex("\\p{Mn}+")

    fun filter(items: List<PluginItem>, queryForms: List<String>): List<PluginItem> {
        val forms = queryForms.map(::titleTokens).filter { it.isNotEmpty() }
        if (forms.isEmpty()) return items
        val joinedForms = queryForms.map { words(it).joinToString("") }.filter { it.length > 2 }
        return items.filter { item ->
            if (item.kind != PluginOutput.KIND_LIVE) return@filter true
            val name = titleTokens(item.title)
            forms.any { form -> form.count { word -> name.any { it.startsWith(word) } }.toDouble() / form.size >= MIN_RELEVANCE } ||
                matchesJoined(words(item.title), joinedForms)
        }
    }

    /**
     * Whether a query written with or without spaces matches a name written the other way: "nat geo" finds
     * "NatGeo", "canal rcn" finds "CanalRCN", "natgeo" finds "Nat Geo HD". The query's words, glued together
     * ([joinedForms]), must start the name glued together from one of its word boundaries on, so "geo" alone
     * still doesn't find "NatGeo" (no word of it starts there).
     */
    private fun matchesJoined(nameWords: List<String>, joinedForms: List<String>): Boolean {
        if (joinedForms.isEmpty()) return false
        return nameWords.indices.any { i ->
            val tail = nameWords.subList(i, nameWords.size).joinToString("")
            joinedForms.any { tail.startsWith(it) }
        }
    }

    /** The query forms a plugin's `search` received, read back from its argument JSON. */
    fun queryForms(queryJson: String): List<String> {
        val o = runCatching { JSONObject(queryJson) }.getOrNull() ?: return emptyList()
        val alt = o.optJSONArray("altTitles")
        return listOf(o.optString("q"), o.optString("originalTitle")) +
            (0 until (alt?.length() ?: 0)).map { alt!!.optString(it) }
    }

    // Letters and digits split apart ("ESPN2" and "ESPN 2" both give "espn"), and a query word
    // counts when a name word starts with it (typing "discov" finds "Discovery"): people search
    // channels while still typing, so this is looser than kino.rank on purpose.
    internal fun titleTokens(text: String): Set<String> = words(text).filter { it.length > 2 }.toSet()

    /** Every word of [text] in order, short ones included: lower case, accents off, letters and digits apart. */
    private fun words(text: String): List<String> {
        val plain = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD), "")
        return WORD.findAll(plain).map { it.value }.toList()
    }

    private val WORD = Regex("[a-z]+|[0-9]+")
}
