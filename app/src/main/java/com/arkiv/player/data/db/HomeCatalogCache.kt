package com.arkiv.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The classified home catalog (hero + Estrenos + genre rows), persisted so a cold start paints the
 * last-known home INSTANTLY instead of a blank screen while the portal answers. Local, derivable
 * cache -- NOT synced (no sync triggers), same rationale as [ArtworkEntity]/[LiveChannelCacheEntity].
 *
 * One single row: `id` is always [SINGLETON]. [rowsJson] is the hand-rolled JSON of
 * `List<MagisHomeRow>` (see `HomeCatalogCodec`), [fetchedAt] the epoch-ms of the portal fetch it
 * came from, weighed against the 6 h TTL to decide a background refresh. It feeds BOTH the home and
 * the Categorías screen, which classify from the same catalog.
 */
@Entity(tableName = "home_catalog_cache")
data class HomeCatalogCacheEntity(
    @PrimaryKey val id: String = SINGLETON,
    val rowsJson: String,
    val fetchedAt: Long,
) {
    companion object {
        const val SINGLETON = "home"
    }
}

@Dao
interface HomeCatalogCacheDao {
    @Query("SELECT * FROM home_catalog_cache WHERE id = :id LIMIT 1")
    suspend fun get(id: String = HomeCatalogCacheEntity.SINGLETON): HomeCatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: HomeCatalogCacheEntity)

    @Query("DELETE FROM home_catalog_cache")
    suspend fun clear()
}
