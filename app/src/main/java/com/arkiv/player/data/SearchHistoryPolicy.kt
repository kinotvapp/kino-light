package com.arkiv.player.data

/**
 * A title opened from the search, saved so it can be returned to without searching it again.
 *
 * The UI's `TitleCard` isn't persisted: it carries `overview` and `backdropUrl`, which the
 * RESULTS phase's hero asks TMDB/AniList for again anyway. See `TitleCard.toRecent()` in
 * CardContext.kt.
 */
data class RecentTitle(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
)

/**
 * The only thing about the history SQLite doesn't resolve on its own.
 *
 * The order, the cap, and the dedupe used to live here; now the query does them
 * (`ORDER BY atMs DESC LIMIT`) and the PK with REPLACE. See [SearchHistoryRepo].
 */
object SearchHistoryPolicy {

    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12

    /** Trims the searched text. Returns null if nothing's left worth saving. */
    fun normalizeQuery(text: String): String? = text.trim().ifEmpty { null }

    /**
     * A title's identity id, which is `recent_titles`'s PK.
     *
     * By the source's id, not by name: there are different series with the same name, and the
     * same number can be a movie in TMDB and something else in AniList -- that's why `kind` goes
     * first. With no id at all (shouldn't happen, but an old row can carry one) it falls back to
     * the lowercase name, which is better than treating everything as different and filling the
     * list with duplicates.
     */
    fun titleId(kind: String, tmdbId: Int?, anilistId: Long?, title: String): String = when {
        tmdbId != null -> "$kind:tmdb-$tmdbId"
        anilistId != null -> "$kind:anilist-$anilistId"
        else -> "$kind:n-${title.trim().lowercase()}"
    }

    fun titleId(t: RecentTitle): String = titleId(t.kind, t.tmdbId, t.anilistId, t.title)
}
