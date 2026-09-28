package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode

/** Cap on the merged channel search: a query like "a" must not paint thousands of cards. */
const val SEARCH_ACROSS_LIMIT = 300

/**
 * The En vivo channel search across EVERY provider (the phone search field and the TV guide's
 * "Buscar canal por nombre o número…"), not only the active chip's: one merged list over the
 * channels each provider already has at hand ([channelsByProvider], one list per provider, in the
 * module's order -- Xuper first, then registry order). Nothing here asks a provider for more: a
 * paged plugin's categories never loaded are simply not searched (see [notLoadedYetNote]).
 *
 * Matched with [filterChannels]' rules (name without accents or case, or the exact number),
 * ordered by match quality (exact name or number, name starting with the query, a word starting
 * with it, anywhere), then provider order, then each provider's own order. Deduplicated only by
 * live code: the same name in two providers shows twice, once per provider. A blank query is no
 * search at all (the screen shows its normal per-provider view): empty.
 */
fun searchAcrossProviders(
    query: String,
    channelsByProvider: List<List<LiveChannel>>,
    limit: Int = SEARCH_ACROSS_LIMIT,
): List<LiveChannel> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val normalized = q.normalized()
    val seen = HashSet<String>()
    val hits = ArrayList<Pair<Int, LiveChannel>>()
    for (list in channelsByProvider) {
        for (c in list) {
            val rank = matchRank(c, q, normalized) ?: continue
            if (seen.add(c.liveCode)) hits += rank to c
        }
    }
    // sortedBy is stable: within one rank, provider order and each provider's order survive.
    return hits.sortedBy { it.first }.take(limit).map { it.second }
}

/** 0 exact name or number, 1 the name starts with it, 2 a word does, 3 anywhere; null = no match. */
private fun matchRank(c: LiveChannel, raw: String, q: String): Int? {
    val name = c.name.normalized()
    return when {
        c.number.toString() == raw || name == q -> 0
        name.startsWith(q) -> 1
        name.split(' ', '-', '.', '/').any { it.startsWith(q) } -> 2
        name.contains(q) -> 3
        else -> null
    }
}

/** The line under the merged search results when [providerName] has categories it never loaded. */
fun notLoadedYetNote(providerName: String): String = "Algunos canales de $providerName aún no se han cargado"
