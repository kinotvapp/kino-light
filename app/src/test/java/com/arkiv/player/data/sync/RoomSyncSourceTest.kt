package com.arkiv.player.data.sync

import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.HistoryRow
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LastPlayedRow
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.ProgressWithNextRow
import com.arkiv.player.data.db.SeriesWithProgressRow
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.db.WatchedRow
import com.arkiv.player.data.markers.FakeSkipMarkerDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomSyncSource.changedSince]'s `when(table)` dispatch and JSON mapping, over fake DAOs. Every
 * fake's `getXSince` is behaviorally real (filters by cursor, sorts by `updatedAt`), same
 * convention as `SyncApplyTest`'s fakes (this file's own fakes are named with an `Rss` prefix --
 * Kotlin's top-level `private` only hides a class from other files, it doesn't let two files in the
 * same package declare the same class name).
 *
 * [RoomSyncSource.changes] (the `InvalidationTracker` callbackFlow) is NOT covered here: it needs a
 * real, Room-generated [com.arkiv.player.data.db.ArkivDatabase], which nothing in this module's
 * unit tests can stand up (no Robolectric/instrumentation in this repo -- see `SyncTriggersTest`'s
 * KDoc for the same limitation elsewhere). `RoomSyncSource`'s no-db constructor exists for exactly
 * this: it lets this suite exercise the DAO-dispatch/mapping half without touching the DB half.
 */
class RoomSyncSourceTest {

    @Test fun `playback dispatch maps fake rows to JSON, filtered by cursor`() = runTest {
        val playbackDao = RssFakePlaybackDao().apply {
            rows["old"] = playback("old", positionMs = 111, updatedAt = 3) // <= cursor, excluded
            rows["e1"] = playback("e1", positionMs = 4321, updatedAt = 10)
        }
        val source = RoomSyncSource(RssFakeItemDao(), playbackDao, FakeSkipMarkerDao(), RssFakeLiveFavoriteDao(), RssFakeLiveRecentDao())

        val result = source.changedSince("playback", 5)

        assertEquals(listOf("e1"), result.map { it.getString("episodeId") })
        assertEquals(4321L, result.single().getLong("positionMs")) // spot-check field
    }

    @Test fun `unknown table returns empty`() = runTest {
        val source = RoomSyncSource(RssFakeItemDao(), RssFakePlaybackDao(), FakeSkipMarkerDao(), RssFakeLiveFavoriteDao(), RssFakeLiveRecentDao())

        assertTrue(source.changedSince("not_a_real_table", 0).isEmpty())
    }

    @Test fun `items and episodes dispatch to ItemDao's two Since queries`() = runTest {
        val itemDao = RssFakeItemDao().apply {
            items["it1"] = item("it1", updatedAt = 10)
            episodes["ep1"] = episode("ep1", "it1", updatedAt = 10)
        }
        val source = RoomSyncSource(itemDao, RssFakePlaybackDao(), FakeSkipMarkerDao(), RssFakeLiveFavoriteDao(), RssFakeLiveRecentDao())

        assertEquals(listOf("it1"), source.changedSince("items", 0).map { it.getString("identifier") })
        assertEquals(listOf("ep1"), source.changedSince("episodes", 0).map { it.getString("epId") })
    }

    @Test fun `skip_markers dispatch`() = runTest {
        val markerDao = FakeSkipMarkerDao().apply {
            upsert(SkipMarkerEntity(id = "m1", itemId = "it1", episodeId = "ep1", openingStartMs = 1, openingEndMs = 2, endingStartMs = 3, updatedAt = 10))
        }
        val source = RoomSyncSource(RssFakeItemDao(), RssFakePlaybackDao(), markerDao, RssFakeLiveFavoriteDao(), RssFakeLiveRecentDao())

        assertEquals(listOf("m1"), source.changedSince("skip_markers", 0).map { it.getString("markerId") })
    }

    @Test fun `live_favorites and live_recents dispatch`() = runTest {
        val favoriteDao = RssFakeLiveFavoriteDao().apply {
            rows["c1"] = LiveFavoriteEntity(code = "c1", nombre = "Canal 1", numero = 1, logo = null, updatedAt = 10, deleted = false)
        }
        val recentDao = RssFakeLiveRecentDao().apply {
            rows["c2"] = LiveRecentEntity(code = "c2", nombre = "Canal 2", vistoAt = 5, updatedAt = 10)
        }
        val source = RoomSyncSource(RssFakeItemDao(), RssFakePlaybackDao(), FakeSkipMarkerDao(), favoriteDao, recentDao)

        assertEquals(listOf("c1"), source.changedSince("live_favorites", 0).map { it.getString("code") })
        assertEquals(listOf("c2"), source.changedSince("live_recents", 0).map { it.getString("code") })
    }
}

// ---- fixtures ----

private fun playback(episodeId: String, positionMs: Long, updatedAt: Long) = PlaybackEntity(
    episodeId = episodeId, positionMs = positionMs, durationMs = 9000, watched = false,
    lastPlayedAt = 0, updatedAt = updatedAt, deleted = false,
)

private fun item(identifier: String, updatedAt: Long) = ItemEntity(
    identifier = identifier, title = "Title", description = null, thumbnailUrl = "http://x",
    addedAt = 100, updatedAt = updatedAt, deleted = false,
)

private fun episode(id: String, itemId: String, updatedAt: Long) = EpisodeEntity(
    id = id, itemId = itemId, section = "temporada-1", displayName = "Ep", orderIndex = 0,
    durationSeconds = 100.0, thumbPath = null, originalPath = null, originalFormat = null,
    originalSize = 0L, derivativePath = null, derivativeFormat = null, derivativeSize = 0L,
    torrentFileIndex = null, torrentData = null, updatedAt = updatedAt, deleted = false,
)

// ---- fakes (getXSince behaviorally real; everything else stubbed to satisfy the interface, same
// convention as CompanionSyncEngineTest's/SyncApplyTest's duplicated fakes) ----

private class RssFakeItemDao : ItemDao {
    val items = mutableMapOf<String, ItemEntity>()
    val episodes = mutableMapOf<String, EpisodeEntity>()

    override suspend fun upsertItem(item: ItemEntity) { items[item.identifier] = item }
    override suspend fun markEpisodesSeen(itemId: String, count: Int) {}
    override suspend fun upsertEpisodes(episodes: List<EpisodeEntity>) { episodes.forEach { this.episodes[it.id] = it } }
    override suspend fun deleteEpisodesOf(itemId: String) {}
    override suspend fun getItem(itemId: String): ItemEntity? = items[itemId]
    override suspend fun updateCategoryOverride(itemId: String, value: String?) {}
    override suspend fun updateTitle(itemId: String, title: String, updatedAt: Long) {}
    override fun observeLibrary(): Flow<List<LibraryRow>> = MutableStateFlow(emptyList())
    override suspend fun seriesWithProgress(): List<SeriesWithProgressRow> = emptyList()
    override fun observeItem(itemId: String): Flow<ItemEntity?> = MutableStateFlow(items[itemId])
    override fun observeEpisodes(itemId: String): Flow<List<EpisodeEntity>> = MutableStateFlow(emptyList())
    override suspend fun getEpisode(episodeId: String): EpisodeEntity? = episodes[episodeId]
    override suspend fun getEpisodesOf(itemId: String): List<EpisodeEntity> = emptyList()
    override suspend fun getAllItems(): List<ItemEntity> = items.values.toList()
    override suspend fun softDeleteItem(itemId: String) {}
    override suspend fun softDeleteEpisodesOf(itemId: String) {}
    override suspend fun softDeleteEpisode(episodeId: String) {}
    override suspend fun getItemsSince(cursor: Long): List<ItemEntity> =
        items.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun getEpisodesSince(cursor: Long): List<EpisodeEntity> =
        episodes.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}

private class RssFakePlaybackDao : PlaybackDao {
    val rows = mutableMapOf<String, PlaybackEntity>()

    override suspend fun upsert(playback: PlaybackEntity) { rows[playback.episodeId] = playback }
    override suspend fun get(episodeId: String): PlaybackEntity? = rows[episodeId]
    override fun observe(episodeId: String): Flow<PlaybackEntity?> = MutableStateFlow(rows[episodeId])
    override fun observeProgressWithNext(): Flow<List<ProgressWithNextRow>> = MutableStateFlow(emptyList())
    override suspend fun continueWatchingRows(episodeIds: List<String>): List<ContinueRow> = emptyList()
    override fun observeWatched(): Flow<List<WatchedRow>> = MutableStateFlow(emptyList())
    override fun observeLastPlayed(): Flow<List<LastPlayedRow>> = MutableStateFlow(emptyList())
    override fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>> = MutableStateFlow(emptyList())
    override suspend fun recentHistory(limit: Int): List<HistoryRow> = emptyList()
    override suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}

private class RssFakeLiveFavoriteDao : LiveFavoriteDao {
    val rows = mutableMapOf<String, LiveFavoriteEntity>()

    override fun flowAll(): Flow<List<LiveFavoriteEntity>> = MutableStateFlow(emptyList())
    override suspend fun save(f: LiveFavoriteEntity) { rows[f.code] = f }
    override suspend fun delete(code: String) {}
    override suspend fun isFavorite(code: String): Boolean = false
    override suspend fun getAll(): List<LiveFavoriteEntity> = rows.values.toList()
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun get(code: String): LiveFavoriteEntity? = rows[code]
}

private class RssFakeLiveRecentDao : LiveRecentDao {
    val rows = mutableMapOf<String, LiveRecentEntity>()

    override fun flowRecent(limit: Int): Flow<List<LiveRecentEntity>> = MutableStateFlow(emptyList())
    override suspend fun record(r: LiveRecentEntity) { rows[r.code] = r }
    override suspend fun getAll(): List<LiveRecentEntity> = rows.values.toList()
    override suspend fun getLiveRecentsSince(cursor: Long): List<LiveRecentEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun get(code: String): LiveRecentEntity? = rows[code]
}
