package com.arkiv.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persisted VOD search results, so reopening the app and searching the same title answers instantly
 * instead of re-hitting the rate-limited portal. Local, derivable cache -- NOT synced.
 *
 * [query] is the lowercased PORTAL query (the title head Xuper is actually asked for, see
 * `portalQuery`), the same key `MagisSource`'s in-memory cache uses. [itemsJson] is the raw portal
 * item list serialized verbatim (a JSON array of the portal's own objects, `JSONObject.toString`),
 * so nothing the ranking/mapping later reads is lost. [fetchedAt] is weighed against the 6 h TTL.
 */
@Entity(tableName = "vod_search_cache")
data class VodSearchCacheEntity(
    @PrimaryKey val query: String,
    val itemsJson: String,
    val fetchedAt: Long,
)

@Dao
interface VodSearchCacheDao {
    @Query("SELECT * FROM vod_search_cache WHERE query = :query LIMIT 1")
    suspend fun get(query: String): VodSearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: VodSearchCacheEntity)

    /** Bounds the table: drops entries past the TTL so distinct one-off searches don't pile up. */
    @Query("DELETE FROM vod_search_cache WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM vod_search_cache")
    suspend fun clear()
}
