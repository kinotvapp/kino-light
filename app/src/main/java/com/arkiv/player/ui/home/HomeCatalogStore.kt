package com.arkiv.player.ui.home

import com.arkiv.player.data.db.HomeCatalogCacheDao
import com.arkiv.player.data.db.HomeCatalogCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The persistent home-catalog cache: reads the last snapshot the portal gave and writes a new one.
 * Bridges Room ([HomeCatalogCacheDao]) and the home's domain rows via [HomeCatalogCodec].
 *
 * An empty snapshot is treated as "nothing cached" -- a portal fetch that came back empty is never
 * worth serving instead of trying again, and never overwrites a good snapshot ([write] ignores it).
 *
 * Total and defensive: a cache MUST NOT be able to break the home. Every Room call is wrapped, so a
 * disk-full write (`SQLITE_FULL`, seen in the wild) or an unreadable row degrades to "no cache" --
 * the app just fetches fresh -- instead of throwing out of the fetch path.
 */
class HomeCatalogStore(private val dao: HomeCatalogCacheDao) {

    // The JSON encode/decode is CPU-bound and the catalog blob is large, so it runs on
    // Dispatchers.Default -- the home flow is collected on the main thread (viewModelScope), and
    // parsing the snapshot there would jank/ANR a weak device on every cold start.
    suspend fun read(): CachedRows? = withContext(Dispatchers.Default) {
        runCatching {
            val e = dao.get() ?: return@runCatching null
            val rows = HomeCatalogCodec.decode(e.rowsJson)
            if (rows.isEmpty()) null else CachedRows(rows, e.fetchedAt)
        }.getOrNull()
    }

    suspend fun write(rows: List<MagisHomeRow>, at: Long) {
        if (rows.isEmpty()) return
        withContext(Dispatchers.Default) {
            runCatching {
                dao.save(HomeCatalogCacheEntity(rowsJson = HomeCatalogCodec.encode(rows), fetchedAt = at))
            }
        }
    }

    suspend fun clear() {
        runCatching { dao.clear() }
    }
}
