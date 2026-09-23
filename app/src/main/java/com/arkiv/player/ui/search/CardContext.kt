package com.arkiv.player.ui.search

import com.arkiv.player.data.RecentTitle
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbItem

enum class SearchPhase { QUERY, REFINE, RESULTS }

/** Phase 1 card. kind: "movie" | "series" (TMDB) | "anime" (AniList). */
data class TitleCard(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val overview: String?,
    /** Landscape (16:9) image for the TV rows; empty if the source doesn't bring one. */
    val backdropUrl: String = "",
)

/** TMDB → home/search card. TMDB's `type` is "movie"|"tv"; in the UI "movie"|"series" is used. */
fun TmdbItem.toTitleCard(): TitleCard = TitleCard(
    kind = if (type == "tv") "series" else "movie",
    tmdbId = id,
    anilistId = null,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = overview,
    backdropUrl = backdropUrl,
)

/** AniList → card. `year` can come as 0 when unknown: empty is better than "0". */
fun AnimeShow.toTitleCard(): TitleCard = TitleCard(
    kind = "anime",
    tmdbId = null,
    anilistId = id,
    title = title,
    posterUrl = posterUrl,
    year = if (year > 0) year.toString() else "",
    overview = description,
    // AniList already brings its own landscape image (banner); if missing, the TV falls back to the poster.
    backdropUrl = bannerUrl,
)

/**
 * Search text → card, to search sources by what's typed rather than a catalog card (the TV
 * search's "Buscar" button). Returns null if there's nothing to search.
 *
 * Goes with no `tmdbId`/`anilistId` on purpose: in the gateway the tmdb_id is only the tiebreak
 * among titles matching the text, so without it all four sources search by `q` -- which is
 * exactly what's wanted here. `kind` is "movie" because with season/episode at 0 no source filters
 * by type (magis returns movies and series just the same), and because `SearchViewModel.back()`
 * sends movies back to QUERY: without that, going back from sources would land on the season
 * selector of a card that doesn't exist.
 */
fun freeTextCard(text: String): TitleCard? {
    val q = text.trim().takeIf { it.isNotBlank() } ?: return null
    return TitleCard(
        kind = "movie",
        tmdbId = null,
        anilistId = null,
        title = q,
        posterUrl = "",
        year = "",
        overview = null,
    )
}

/**
 * Removes duplicates from the title grid, which merges TMDB with AniList: anything that's anime
 * and also on TMDB used to show up twice with the same name. The first one in the list wins, so
 * the order already shown doesn't change.
 *
 * The key carries the YEAR along with the name: two movies with the same title and a different
 * year are two different movies (remakes), and collapsing them would hide one. A blank title has
 * nothing to compare against, so it always passes -- merging those would be merging things that
 * aren't known to be the same.
 */
fun withoutDuplicates(cards: List<TitleCard>): List<TitleCard> {
    val seen = HashSet<String>()
    return cards.filter { card ->
        val key = normalizeTitle(card.title)
        key.isEmpty() || seen.add("$key|${card.year}")
    }
}

/** Comparable name: no capitals, no accents, no punctuation, and a single space between words.
 *  "¡El  PADRINO!" and "el padrino" are the same title. */
private fun normalizeTitle(title: String): String =
    java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/** Card → history entry. `overview`/`backdrop` are dropped: the hero requests them again anyway. */
fun TitleCard.toRecent(): RecentTitle = RecentTitle(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
)

/** History → card, to be able to tap a recent poster and land straight on the sources. */
fun RecentTitle.toTitleCard(): TitleCard = TitleCard(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = null,
)
