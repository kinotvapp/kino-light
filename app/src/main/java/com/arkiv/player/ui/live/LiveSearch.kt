package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode

/** Cap on the merged channel search: a query like "a" must not paint thousands of cards. */
const val SEARCH_ACROSS_LIMIT = 300

private val WORD_SEPARATORS = Regex("[-./]")

/**
 * One channel ready to be searched: its name normalised once ([normalized]), not on every
 * keystroke. [spaced] is the same name with a leading space and separators turned into spaces, so
 * "a word starts with the query" is one `contains(" " + q)`.
 */
class SearchEntry(val channel: LiveChannel) {
    val name: String = channel.name.normalized()
    val spaced: String = " " + name.replace(WORD_SEPARATORS, " ")
}

/** [channels] as a search index, in their order. */
fun searchIndexOf(channels: List<LiveChannel>): List<SearchEntry> = channels.map(::SearchEntry)

/**
 * The En vivo channel search across EVERY provider (the phone search field and the TV guide's
 * "Buscar canal por nombre o número…"), not only the active chip's: one merged list over the
 * channels each provider already has at hand ([index], one list per provider, in the module's
 * order -- Xuper first, then registry order). Nothing here asks a provider for more: a paged
 * plugin's categories never loaded are simply not searched (see [notLoadedYetNote]).
 *
 * Matched with [filterChannels]' rules (name without accents or case, or the exact number of a
 * numbered channel), ordered by match quality (exact name or number, name starting with the
 * query, a word starting with it, anywhere), then provider order, then each provider's own order.
 * Deduplicated only by live code: the same name in two providers shows twice, once per provider.
 * A blank query is no search at all (the screen shows its normal per-provider view): empty.
 */
fun searchAcrossProviders(
    query: String,
    index: List<List<SearchEntry>>,
    limit: Int = SEARCH_ACROSS_LIMIT,
): List<LiveChannel> {
    val raw = query.trim()
    if (raw.isEmpty()) return emptyList()
    val q = raw.normalized()
    val wordStart = " $q"
    // Parsed once: the loop compares ints instead of formatting every channel's number per query.
    // A query with a leading zero ("0502") never matched a number before, and still doesn't.
    val rawNumber = raw.toIntOrNull()?.takeIf { it > 0 && it.toString() == raw } ?: -1
    val seen = HashSet<String>()
    // One bucket per rank keeps provider order and each provider's order without sorting.
    val buckets = Array(4) { ArrayList<LiveChannel>() }
    for (list in index) {
        for (e in list) {
            val c = e.channel
            val rank = when {
                c.number == rawNumber || e.name == q -> 0
                e.name.startsWith(q) -> 1
                e.spaced.contains(wordStart) -> 2
                e.name.contains(q) -> 3
                else -> continue
            }
            if (seen.add(c.liveCode)) buckets[rank] += c
        }
    }
    val out = ArrayList<LiveChannel>(minOf(limit, buckets.sumOf { it.size }))
    for (b in buckets) {
        for (c in b) { if (out.size == limit) return out; out += c }
    }
    return out
}

/** The line under the merged search results when [providerName] has categories it never loaded. */
fun notLoadedYetNote(providerName: String): String = "Algunos canales de $providerName aún no se han cargado"

/** What the phone En vivo screen draws for its search field (Amendment A1); see [liveSearchView]. */
sealed interface LiveSearchView {
    /** Blank query: the normal per-provider view (chips, categories, favourites, recents). */
    data object Off : LiveSearchView
    /** A query with no answer yet. */
    data object Searching : LiveSearchView
    /** Matches from every provider, each drawn with its provider badge; [note] = providers not fully searched. */
    data class Results(val channels: List<LiveChannel>, val note: String?) : LiveSearchView
    data class NoResults(val note: String?) : LiveSearchView
}

/**
 * The search view for [query] given the model's latest [cross] answer. While the answer for the
 * current query is still being computed (debounced), the previous non-empty one stays on screen
 * instead of flashing a spinner on every keystroke. Pure.
 */
fun liveSearchView(query: String, cross: CrossSearch?): LiveSearchView {
    val q = query.trim()
    if (q.isEmpty()) return LiveSearchView.Off
    if (cross == null) return LiveSearchView.Searching
    val note = cross.notLoaded.joinToString("\n") { notLoadedYetNote(it) }.ifEmpty { null }
    if (cross.query != q) return if (cross.results.isEmpty()) LiveSearchView.Searching else LiveSearchView.Results(cross.results, note)
    return if (cross.results.isEmpty()) LiveSearchView.NoResults(note) else LiveSearchView.Results(cross.results, note)
}

/** The provider chip to light: the active provider while one of its own categories is on screen; none on Favoritos or Recientes. Pure. */
fun selectedProviderChip(state: LiveUiState, recentView: Boolean): String? =
    if (recentView || state.activeCategory == CATEGORY_FAVORITES) null else state.activeProvider
