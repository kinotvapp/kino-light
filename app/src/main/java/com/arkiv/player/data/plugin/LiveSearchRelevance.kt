package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.text.Normalizer

/**
 * Drops the live channels of a plugin's search answer whose name has nothing to do with what was
 * asked. Some channel plugins fall back to their whole channel list when no name matches (seen:
 * "FAST TV" answered "Breaking Bad" with 40 Pluto TV channels), which buries the real results.
 *
 * Only channels are judged: a movie or series a scraper returns may rightly carry another title
 * (another language, a translated name), so those are always kept. The rule is the one plugins get
 * as `kino.rank.filterRelevant` (prelude.js): a channel stays only when its name carries most
 * ([MIN_RELEVANCE]) of the 3+ letter words of some form of the query (what was typed, TMDB's
 * original title, the other known titles). A query with no such word identifies nothing, so
 * nothing is dropped.
 */
internal object LiveSearchRelevance {
    private const val MIN_RELEVANCE = 0.6
    private val WORD = Regex("[a-z0-9]+")
    private val MARKS = Regex("\\p{Mn}+")

    fun filter(items: List<PluginItem>, queryForms: List<String>): List<PluginItem> {
        val forms = queryForms.map(::titleTokens).filter { it.isNotEmpty() }
        if (forms.isEmpty()) return items
        return items.filter { item ->
            if (item.kind != PluginOutput.KIND_LIVE) return@filter true
            val name = titleTokens(item.title)
            forms.any { form -> form.count { it in name }.toDouble() / form.size >= MIN_RELEVANCE }
        }
    }

    /** The query forms a plugin's `search` received, read back from its argument JSON. */
    fun queryForms(queryJson: String): List<String> {
        val o = runCatching { JSONObject(queryJson) }.getOrNull() ?: return emptyList()
        val alt = o.optJSONArray("altTitles")
        return listOf(o.optString("q"), o.optString("originalTitle")) +
            (0 until (alt?.length() ?: 0)).map { alt!!.optString(it) }
    }

    internal fun titleTokens(text: String): Set<String> {
        val plain = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD), "")
        return WORD.findAll(plain).map { it.value }.filter { it.length > 2 }.toSet()
    }
}
