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
 * In-memory fake of [ItemDao]. Only `getItem`/`upsertItem`/`getEpisode`/`upsertEpisodes` are
 * behaviorally exercised by [SyncApplyTest]; the rest exist only to satisfy the interface (same
 * convention as [FakeSkipMarkerDao] and the repo's other fakes).
 */
private class FakeItemDao : ItemDao {
    val items = mutableMapOf<String, ItemEntity>()
    val episodes = mutableMapOf<String, EpisodeEntity>()

    override suspend fun upsertItem(item: ItemEntity) { items[item.identifier] = item }

    override suspend fun markEpisodesSeen(itemId: String, count: Int) {
        items[itemId]?.let { items[itemId] = it.copy(episodiosVistosEnLista = count) }
    }

    override suspend fun upsertEpisodes(episodes: List<EpisodeEntity>) {
        episodes.forEach { this.episodes[it.id] = it }
    }

    override suspend fun deleteEpisodesOf(itemId: String) {
        episodes.entries.removeAll { it.value.itemId == itemId }
    }

    override suspend fun getItem(itemId: String): ItemEntity? = items[itemId]

    override suspend fun updateCategoryOverride(itemId: String, value: String?) {
        items[itemId]?.let { items[itemId] = it.copy(categoryOverride = value) }
    }

    override suspend fun updateTitle(itemId: String, title: String, updatedAt: Long) {
        items[itemId]?.let { items[itemId] = it.copy(title = title, tituloCanonico = null, updatedAt = updatedAt) }
    }

    override fun observeLibrary(): Flow<List<LibraryRow>> = MutableStateFlow(emptyList())
    override suspend fun seriesWithProgress(): List<SeriesWithProgressRow> = emptyList()
    override fun observeItem(itemId: String): Flow<ItemEntity?> = MutableStateFlow(items[itemId])
    override fun observeEpisodes(itemId: String): Flow<List<EpisodeEntity>> = MutableStateFlow(emptyList())
    override suspend fun getEpisode(episodeId: String): EpisodeEntity? = episodes[episodeId]
    override suspend fun getEpisodesOf(itemId: String): List<EpisodeEntity> =
        episodes.values.filter { it.itemId == itemId }
    override suspend fun getAllItems(): List<ItemEntity> = items.values.toList()

    override suspend fun softDeleteItem(itemId: String) {
        items[itemId]?.let { items[itemId] = it.copy(deleted = true) }
    }

    override suspend fun softDeleteEpisodesOf(itemId: String) {
        episodes.keys.filter { episodes[it]?.itemId == itemId }.forEach { id ->
            episodes[id] = episodes.getValue(id).copy(deleted = true)
        }
    }

    override suspend fun softDeleteEpisode(episodeId: String) {
        episodes[episodeId]?.let { episodes[episodeId] = it.copy(deleted = true) }
    }

    override suspend fun getItemsSince(cursor: Long): List<ItemEntity> = emptyList()
    override suspend fun getEpisodesSince(cursor: Long): List<EpisodeEntity> = emptyList()
}

/** In-memory fake of [PlaybackDao]. Only `get`/`upsert` are behaviorally exercised. */
private class FakePlaybackDao : PlaybackDao {
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
    override suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity> = emptyList()
}

/** In-memory fake of [LiveFavoriteDao]. Not exercised by any test case below; exists only to
 * satisfy [SyncApply]'s constructor. */
private class FakeLiveFavoriteDao : LiveFavoriteDao {
    val rows = mutableMapOf<String, LiveFavoriteEntity>()

    override fun flowAll(): Flow<List<LiveFavoriteEntity>> =
        MutableStateFlow(rows.values.filter { !it.deleted }.sortedBy { it.numero })
    override suspend fun save(f: LiveFavoriteEntity) { rows[f.code] = f }
    override suspend fun delete(code: String) { rows[code]?.let { rows[code] = it.copy(deleted = true) } }
    override suspend fun isFavorite(code: String): Boolean = rows[code]?.let { !it.deleted } ?: false
    override suspend fun getAll(): List<LiveFavoriteEntity> = rows.values.toList()
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun get(code: String): LiveFavoriteEntity? = rows[code]
}

/** In-memory fake of [LiveRecentDao]. Not exercised by any test case below; exists only to
 * satisfy [SyncApply]'s constructor. */
private class FakeLiveRecentDao : LiveRecentDao {
    val rows = mutableMapOf<String, LiveRecentEntity>()

    override fun flowRecent(limit: Int): Flow<List<LiveRecentEntity>> =
        MutableStateFlow(rows.values.sortedByDescending { it.vistoAt }.take(limit))
    override suspend fun record(r: LiveRecentEntity) { rows[r.code] = r }
    override suspend fun getAll(): List<LiveRecentEntity> = rows.values.toList()
    override suspend fun getLiveRecentsSince(cursor: Long): List<LiveRecentEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun get(code: String): LiveRecentEntity? = rows[code]
}

private fun playbackJson(
    episodeId: String,
    positionMs: Long,
    updatedAt: Long,
    durationMs: Long = 9000,
    watched: Boolean = false,
    lastPlayedAt: Long = 0,
    deleted: Boolean = false,
) = JSONObject().apply {
    put("episodeId", episodeId)
    put("positionMs", positionMs)
    put("durationMs", durationMs)
    put("watched", watched)
    put("lastPlayedAt", lastPlayedAt)
    put("updatedAt", updatedAt)
    put("deleted", deleted)
}

private fun episodeJson(
    epId: String,
    itemId: String,
    updatedAt: Long,
    displayName: String = "Episode",
) = JSONObject().apply {
    put("epId", epId)
    put("itemId", itemId)
    put("section", "temporada-1")
    put("displayName", displayName)
    put("orderIndex", 0)
    put("durationSeconds", 1320.0)
    put("season", 1)
    put("episode", 1)
    put("updatedAt", updatedAt)
    put("deleted", false)
}

private fun itemJson(
    identifier: String,
    updatedAt: Long,
    title: String = "Title",
) = JSONObject().apply {
    put("identifier", identifier)
    put("title", title)
    put("thumbnailUrl", "http://x/thumb.jpg")
    put("addedAt", 100L)
    put("source", "archive")
    put("updatedAt", updatedAt)
    put("deleted", false)
}

class SyncApplyTest {
    @Test fun `newer remote playback adopts with its own updatedAt`() = runTest {
        val playbackDao = FakePlaybackDao().apply {
            rows["e1"] = PlaybackEntity(
                episodeId = "e1", positionMs = 1000, durationMs = 9000, watched = false,
                lastPlayedAt = 5, updatedAt = 10, deleted = false,
            )
        }
        val sync = SyncApply(FakeItemDao(), playbackDao, FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())

        sync.apply("playback", playbackJson(episodeId = "e1", positionMs = 5000, updatedAt = 20))

        val stored = playbackDao.get("e1")
        assertEquals(5000L, stored?.positionMs)
        assertEquals(20L, stored?.updatedAt)
    }

    @Test fun `older remote is ignored`() = runTest {
        val playbackDao = FakePlaybackDao().apply {
            rows["e1"] = PlaybackEntity(
                episodeId = "e1", positionMs = 1000, durationMs = 9000, watched = false,
                lastPlayedAt = 5, updatedAt = 30, deleted = false,
            )
        }
        val sync = SyncApply(FakeItemDao(), playbackDao, FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())

        sync.apply("playback", playbackJson(episodeId = "e1", positionMs = 9999, updatedAt = 20))

        val stored = playbackDao.get("e1")
        assertEquals(1000L, stored?.positionMs)
        assertEquals(30L, stored?.updatedAt)
    }

    @Test fun `tombstone wins when newer`() = runTest {
        val playbackDao = FakePlaybackDao().apply {
            rows["e1"] = PlaybackEntity(
                episodeId = "e1", positionMs = 1000, durationMs = 9000, watched = false,
                lastPlayedAt = 5, updatedAt = 10, deleted = false,
            )
        }
        val sync = SyncApply(FakeItemDao(), playbackDao, FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())

        sync.apply("playback", playbackJson(episodeId = "e1", positionMs = 1000, updatedAt = 40, deleted = true))

        val stored = playbackDao.get("e1")
        assertTrue(stored!!.deleted)
        assertEquals(40L, stored.updatedAt)
    }

    @Test fun `episode adopt preserves local download path`() = runTest {
        val itemDao = FakeItemDao().apply {
            episodes["ep1"] = EpisodeEntity(
                id = "ep1", itemId = "it1", section = "temporada-1", displayName = "Old name",
                orderIndex = 0, durationSeconds = 1000.0,
                thumbPath = "/data/t.jpg", originalPath = "/data/x.mp4", originalFormat = "mp4", originalSize = 111L,
                derivativePath = "/data/d.mp4", derivativeFormat = "mp4", derivativeSize = 222L,
                torrentFileIndex = 3, torrentData = null,
                updatedAt = 10, deleted = false,
            )
        }
        val sync = SyncApply(itemDao, FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())

        sync.apply("episodes", episodeJson(epId = "ep1", itemId = "it1", updatedAt = 20, displayName = "New name"))

        val stored = itemDao.getEpisode("ep1")
        assertEquals("New name", stored?.displayName)
        assertEquals(20L, stored?.updatedAt)
        assertEquals("/data/x.mp4", stored?.originalPath)
        assertEquals("/data/t.jpg", stored?.thumbPath)
        assertEquals("mp4", stored?.originalFormat)
        assertEquals(111L, stored?.originalSize)
        assertEquals("/data/d.mp4", stored?.derivativePath)
        assertEquals("mp4", stored?.derivativeFormat)
        assertEquals(222L, stored?.derivativeSize)
        assertEquals(3, stored?.torrentFileIndex)
    }

    @Test fun `item adopt preserves local episodiosVistosEnLista`() = runTest {
        val itemDao = FakeItemDao().apply {
            items["it1"] = ItemEntity(
                identifier = "it1", title = "Old title", description = null, thumbnailUrl = "http://x",
                addedAt = 100, updatedAt = 10, deleted = false, episodiosVistosEnLista = 7,
            )
        }
        val sync = SyncApply(itemDao, FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())

        sync.apply("items", itemJson(identifier = "it1", updatedAt = 20, title = "New title"))

        val stored = itemDao.getItem("it1")
        assertEquals("New title", stored?.title)
        assertEquals(20L, stored?.updatedAt)
        assertEquals(7, stored?.episodiosVistosEnLista)
    }
}
