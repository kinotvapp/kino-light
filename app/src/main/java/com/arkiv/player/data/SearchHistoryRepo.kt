package com.arkiv.player.data

import com.arkiv.player.data.db.RecentTitleDao
import com.arkiv.player.data.db.RecentTitleEntity
import com.arkiv.player.data.db.SearchHistoryDao
import com.arkiv.player.data.db.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Unified search history, on Room.
 *
 * Replaces a SharedPreferences store that duplicated the `search_history` table, which already
 * existed and was already used by the catalog, anime, and the TV's search.
 *
 * The order, the cap, and the dedupe are done by the query; this only normalizes what comes in
 * ([SearchHistoryPolicy]) and translates the entity to the model the UI sees.
 */
class SearchHistoryRepo(
    private val searchHistoryDao: SearchHistoryDao,
    private val recentTitleDao: RecentTitleDao,
) {

    val queries: Flow<List<String>> =
        searchHistoryDao.observeRecent(KIND, SearchHistoryPolicy.MAX_QUERIES)
            .map { rows -> rows.map { it.query } }

    val titles: Flow<List<RecentTitle>> =
        recentTitleDao.observeRecent(SearchHistoryPolicy.MAX_TITLES)
            .map { rows -> rows.map { it.toRecentTitle() } }

    suspend fun addQuery(q: String) {
        val clean = SearchHistoryPolicy.normalizeQuery(q) ?: return
        // Delete the case variants first: search_history's PK distinguishes "Dune" from "dune",
        // so without this there would be two chips that are the same search.
        searchHistoryDao.deleteOne(KIND, clean)
        searchHistoryDao.upsert(SearchHistoryEntity(clean, KIND, System.currentTimeMillis()))
    }

    suspend fun addTitle(t: RecentTitle) {
        recentTitleDao.upsert(
            RecentTitleEntity(
                id = SearchHistoryPolicy.titleId(t),
                kind = t.kind,
                tmdbId = t.tmdbId,
                anilistId = t.anilistId,
                title = t.title,
                posterUrl = t.posterUrl,
                year = t.year,
                atMs = System.currentTimeMillis(),
            ),
        )
        recentTitleDao.trim(SearchHistoryPolicy.MAX_TITLES)
    }

    suspend fun removeQuery(q: String) = searchHistoryDao.deleteOne(KIND, q)

    suspend fun removeTitle(t: RecentTitle) = recentTitleDao.deleteOne(SearchHistoryPolicy.titleId(t))

    /** Clears the whole history (both lists): what a button that says "clear" is expected to do. */
    suspend fun clear() {
        searchHistoryDao.clearKind(KIND)
        recentTitleDao.clear()
    }

    private fun RecentTitleEntity.toRecentTitle() =
        RecentTitle(kind, tmdbId, anilistId, title, posterUrl, year)

    companion object {
        /**
         * The unified search's own bucket. NOT reusing "tv": TvSearchScreen saves with that kind
         * and CineCatalogScreen saves series searches there too, so those two lists already mix
         * with each other. Not adding a third one on top.
         */
        const val KIND = "buscar"
    }
}
