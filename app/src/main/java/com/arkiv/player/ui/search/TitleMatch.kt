package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.plugin.PluginOutput
import java.text.Normalizer

/**
 * Whether a plugin's search result is the title a catalog card named, so a source that answers a
 * loose text search with unrelated titles ("deadpool" finding "Dead Poets Society") doesn't list
 * them as sources of the card. Only for a search driven by a card (its TMDB id known): a typed search
 * shows whatever the sources found.
 *
 * Lenient on purpose, since hiding a real source is worse than listing a near one: a result is out
 * only when a published TMDB id differs, its kind (movie/series) contradicts the card, a movie's year
 * is more than one year off, or none of its titles resembles any of the card's.
 */
object TitleMatch {
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val ARTICLES = setOf("the", "a", "an", "el", "la", "los", "las", "un", "una", "le", "les", "de", "of")

    /** Lowercase, accents and punctuation gone, leading article dropped: "The Dark Knight!" -> "dark knight". */
    fun normalize(text: String): String {
        val plain = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD), "")
        val words = NON_ALNUM.replace(plain, " ").trim().split(' ').filter { it.isNotEmpty() }
        return (if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words).joinToString(" ")
    }

    private fun words(text: String) = normalize(text).split(' ').filter { it.isNotEmpty() && it !in ARTICLES }.toSet()

    /** Same title, or one holding the other whole ("Deadpool" in "Deadpool (2016) 1080p"), or mostly the same words. */
    fun similar(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val (short, long) = if (na.length <= nb.length) na to nb else nb to na
        if (short.length >= 3 && " $long ".contains(" $short ")) return true
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.intersect(wb).size.toDouble() / maxOf(wa.size, wb.size) >= 0.6
    }

    fun matches(result: GatewayResult, query: GatewaySearchQuery): Boolean {
        if (query.tmdbId <= 0) return true
        if (result.kind == PluginOutput.KIND_LIVE) return true
        val tmdb = result.extra["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 }
        if (tmdb != null) return tmdb == query.tmdbId
        val series = result.kind == "series"
        when (query.type) {
            "movie" -> if (series) return false
            "tv" -> if (!series) return false
        }
        if (!series) {
            val year = result.year.take(4).toIntOrNull()
            if (year != null && query.year > 0 && kotlin.math.abs(year - query.year) > 1) return false
        }
        val wanted = (listOf(query.q, query.originalTitle) + query.altTitles).filter { it.isNotBlank() }
        val found = listOfNotNull(result.title, result.extra["originalTitle"]).filter { it.isNotBlank() }
        return wanted.any { w -> found.any { similar(w, it) } }
    }
}
