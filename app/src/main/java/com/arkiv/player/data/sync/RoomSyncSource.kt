package com.arkiv.player.data.sync

import androidx.room.InvalidationTracker
import com.arkiv.player.companion.SyncSource
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

/**
 * [SyncSource] over Room: dispatches `changedSince(table, cursor)` to the matching Task 2
 * `getXSince` DAO query (already `ORDER BY updatedAt ASC`, load-bearing for the engine's cursor
 * math -- not re-sorted here) and maps each entity through Task 1's `xToJson` (`SyncMappers.kt`).
 * An unknown table returns an empty list rather than throwing -- unlike [SyncApply], which is only
 * ever called with a table this device itself just sent in a `sync_rows`/`sync_hello`, this is the
 * READ side and a stray/future table name shouldn't crash the push loop.
 *
 * [db] is only needed for [changes] (the DB-invalidation signal); the DAOs alone are enough for
 * [changedSince]. Kept nullable/defaulted so a unit test can build this over fake DAOs without a
 * real [ArkivDatabase] -- see [changes]' KDoc and `RoomSyncSourceTest`.
 */
class RoomSyncSource(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val liveFavoriteDao: LiveFavoriteDao,
    private val liveRecentDao: LiveRecentDao,
    private val db: ArkivDatabase? = null,
) : SyncSource {

    /** Production convenience: pulls the DAOs, and the database itself (for [changes]), out of Room. */
    constructor(db: ArkivDatabase) : this(
        db.itemDao(), db.playbackDao(), db.skipMarkerDao(), db.liveFavoriteDao(), db.liveRecentDao(), db,
    )

    override suspend fun changedSince(table: String, cursor: Long): List<JSONObject> = when (table) {
        "items" -> itemDao.getItemsSince(cursor).map(::itemToJson)
        "episodes" -> itemDao.getEpisodesSince(cursor).map(::episodeToJson)
        "playback" -> playbackDao.getPlaybackSince(cursor).map(::playbackToJson)
        "skip_markers" -> skipMarkerDao.getMarkersSince(cursor).map(::markerToJson)
        "live_favorites" -> liveFavoriteDao.getLiveFavoritesSince(cursor).map(::liveFavoriteToJson)
        "live_recents" -> liveRecentDao.getLiveRecentsSince(cursor).map(::liveRecentToJson)
        else -> emptyList()
    }

    /**
     * Fires (with the changed table names dropped -- `CompanionSyncEngine` just debounces and
     * re-pushes everything) whenever a local write touches any of the six synced tables. Room's
     * [InvalidationTracker] already knows to fire this off the write transaction, batched, so a
     * flurry of playback ticks collapses into one tick here before the engine's own debounce even
     * gets involved.
     *
     * Needs a real [db] -- requires Room's generated `_Impl`, which nothing in this module's unit
     * tests can stand up (no Robolectric/instrumentation here). `RoomSyncSourceTest` only exercises
     * [changedSince] over fake DAOs and never collects this flow, so [db] being null there is fine:
     * the `checkNotNull` only runs if something actually collects.
     */
    val changes: Flow<Unit> = callbackFlow {
        val database = checkNotNull(db) { "RoomSyncSource.changes requires a real ArkivDatabase" }
        val obs = object : InvalidationTracker.Observer(
            "items", "episodes", "playback", "skip_markers", "live_favorites", "live_recents",
        ) {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        database.invalidationTracker.addObserver(obs)
        awaitClose { database.invalidationTracker.removeObserver(obs) }
    }
}
