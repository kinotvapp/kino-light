package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard

/** Where a TMDB/AniList row pulls its titles from: a Categories screen row, a row-browse grid, or
 *  one of search's category matches -- Home itself no longer uses these, see [buildRowSpecs]. */
sealed interface RowSource {
    data class Curated(val type: String, val category: TmdbCategory) : RowSource
    data class Discover(val type: String, val genreId: Int) : RowSource
    data class Anime(val sort: String, val genre: String? = null) : RowSource
}

/** A horizontal row spec (Categories screens, row browse, search's category matching). The `id`
 *  is the cache and lazy-load key. */
data class HomeRowSpec(val id: String, val title: String, val source: RowSource)

/**
 * Fixed rows first (the most useful ones) and then one per genre -- movies, then series. There
 * are many genre ones on purpose: they only load when they enter the screen. Feeds the phone and
 * TV Categories screens ([CategoriesViewModel]), row browse and search's category matching
 * (`matchCategoryRow` below) -- not Home, which builds its rows from the Magis catalog instead
 * (see `MagisHomeCatalog`).
 */
fun buildRowSpecs(
    movieGenres: List<TmdbGenre>,
    tvGenres: List<TmdbGenre>,
    animeGenres: List<String>,
): List<HomeRowSpec> = buildList {
    // No curated MOVIE row at all ("En cartelera"/NOW_PLAYING, "Películas populares"/POPULAR,
    // "Tendencias"/TRENDING): all three skew heavily toward movies still in theaters or freshly
    // released, which Xuper's catalog doesn't have yet more often than not -- tapping the card
    // landed on an empty "sin resultados" too often even with an age filter on top (tried on
    // TRENDING specifically, still not reliable enough). "Upcoming" (UPCOMING) never made the list
    // for the same reason, worse (titles not released at all yet). Movie genre rows below are
    // unaffected -- this is about the curated/no-genre-filter categories specifically. Overlap
    // between what's left is handled with dedup (see dedupAgainst), not by dropping rows.
    add(HomeRowSpec("series_populares", "Series populares", RowSource.Curated("tv", TmdbCategory.POPULAR)))
    add(HomeRowSpec("series_top", "Series mejor valoradas", RowSource.Curated("tv", TmdbCategory.TOP_RATED)))
    add(HomeRowSpec("anime", "Anime del momento", RowSource.Anime("TRENDING_DESC")))
    add(HomeRowSpec("anime_populares", "Anime populares", RowSource.Anime("POPULARITY_DESC")))
    add(HomeRowSpec("anime_top", "Anime mejor valorados", RowSource.Anime("SCORE_DESC")))
    movieGenres.forEach { g -> add(HomeRowSpec("g_movie_${g.id}", "${g.name} · Películas", RowSource.Discover("movie", g.id))) }
    tvGenres.forEach { g -> add(HomeRowSpec("g_tv_${g.id}", "${g.name} · Series", RowSource.Discover("tv", g.id))) }
    animeGenres.forEach { g ->
        add(HomeRowSpec("g_anime_${g.lowercase().replace(" ", "_")}", "$g · Anime", RowSource.Anime("POPULARITY_DESC", g)))
    }
}

/**
 * Normalizes text for comparison tolerant of accents and case.
 * "Acción" and "accion" come out equal; "Sci-Fi" and "sci-fi" too.
 */
private fun String.normalizeSearch(): String =
    this.lowercase().map { c ->
        when (c) {
            'á', 'à', 'â', 'ä' -> 'a'; 'é', 'è', 'ê', 'ë' -> 'e'
            'í', 'ì', 'î', 'ï' -> 'i'; 'ó', 'ò', 'ô', 'ö' -> 'o'
            'ú', 'ù', 'û', 'ü' -> 'u'; 'ñ' -> 'n'; else -> c
        }
    }.joinToString("").trim()

/**
 * Returns the first [HomeRowSpec] whose row matches [q].
 * Strategies (in order):
 *  1. The genre name before the " · " is identical: "Acción · Películas" ← "accion" ✓
 *  2. Some loose word in the title matches: "Anime del momento"   ← "anime" ✓
 *                                            "Series populares"   ← "series" ✓
 * Tolerant of accents and case. Returns null if [q] is blank.
 */
fun matchCategoryRow(q: String, rows: List<HomeRowSpec>): HomeRowSpec? {
    val normalized = q.normalizeSearch()
    if (normalized.isBlank()) return null
    return rows.firstOrNull { spec ->
        val titleNorm = spec.title.normalizeSearch()
        val base = spec.title.split(" · ").first().normalizeSearch()
        base == normalized || titleNorm.split(" ").contains(normalized)
    }
}

/** Search route that skips the typing phase and starts already on that title. */
fun searchShortcutRoute(card: TitleCard): String = when (card.kind) {
    "anime" -> "search?kind=anime&anilistId=${card.anilistId}"
    "movie" -> "search?kind=movie&tmdbId=${card.tmdbId}"
    else -> "search?kind=series&tmdbId=${card.tmdbId}"
}

/**
 * A title's identity for deduplicating across rows. TMDB's id repeats between movies and series
 * (they're different spaces), so it goes with the type; AniList's is its own.
 */
fun cardKey(card: TitleCard): String = when {
    card.anilistId != null -> "anilist:${card.anilistId}"
    card.tmdbId != null -> "${card.kind}:${card.tmdbId}"
    else -> "title:${card.title.lowercase()}"
}

/**
 * Removes from [cards] the titles that already showed up in another row, and along the way the
 * ones repeated within the list itself. Without this, "Series populares", "Series mejor
 * valoradas" and the genres show almost the same titles: each one stays in the first row it
 * appears in.
 */
fun dedupAgainst(seen: Set<String>, cards: List<TitleCard>): List<TitleCard> {
    val used = seen.toMutableSet()
    return cards.filter { used.add(cardKey(it)) }
}

/** Keeps a row from requesting the network again on recomposition or on re-entering the screen. */
class LoadGuard {
    private val started = mutableSetOf<String>()
    fun shouldLoad(id: String): Boolean = started.add(id)
}
