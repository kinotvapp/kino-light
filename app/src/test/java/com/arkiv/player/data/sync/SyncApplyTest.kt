package com.arkiv.player.data.sync

import com.arkiv.player.companion.encode
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
    override suspend fun delete(episodeId: String) { rows.remove(episodeId) }
    override fun observe(episodeId: String): Flow<PlaybackEntity?> = MutableStateFlow(rows[episodeId])
    override fun observeProgressWithNext(): Flow<List<ProgressWithNextRow>> = MutableStateFlow(emptyList())
    override suspend fun continueWatchingRows(episodeIds: List<String>): List<ContinueRow> = emptyList()
    override fun observeWatched(): Flow<List<WatchedRow>> = MutableStateFlow(emptyList())
    override fun observeLastPlayed(): Flow<List<LastPlayedRow>> = MutableStateFlow(emptyList())
    override fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>> = MutableStateFlow(emptyList())
    override suspend fun recentHistory(limit: Int): List<HistoryRow> = emptyList()
    override suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity> = emptyList()
}

/** In-memory fake of [LiveFavoriteDao], keyed by `"<provider>|<code>"` like the real `(provider, code)` PK. */
private class FakeLiveFavoriteDao : LiveFavoriteDao {
    val rows = mutableMapOf<String, LiveFavoriteEntity>()

    override fun flowAll(): Flow<List<LiveFavoriteEntity>> =
        MutableStateFlow(rows.values.filter { !it.deleted }.sortedBy { it.numero })
    override suspend fun save(f: LiveFavoriteEntity) { rows["${f.provider}|${f.code}"] = f }
    override suspend fun delete(provider: String, code: String) { rows["$provider|$code"]?.let { rows["$provider|$code"] = it.copy(deleted = true) } }
    override suspend fun isFavorite(provider: String, code: String): Boolean = rows["$provider|$code"]?.let { !it.deleted } ?: false
    override suspend fun getAll(): List<LiveFavoriteEntity> = rows.values.toList()
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun get(provider: String, code: String): LiveFavoriteEntity? = rows["$provider|$code"]
}

/** In-memory fake of [LiveRecentDao], keyed by `"<provider>|<code>"` like the real `(provider, code)` PK. */
private class FakeLiveRecentDao : LiveRecentDao {
    val rows = mutableMapOf<String, LiveRecentEntity>()

    override fun flowRecent(limit: Int): Flow<List<LiveRecentEntity>> =
        MutableStateFlow(rows.values.sortedByDescending { it.vistoAt }.take(limit))
    override suspend fun record(r: LiveRecentEntity) { rows["${r.provider}|${r.code}"] = r }
    override suspend fun getAll(): List<LiveRecentEntity> = rows.values.toList()
    override suspend fun getLiveRecentsSince(cursor: Long): List<LiveRecentEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun get(provider: String, code: String): LiveRecentEntity? = rows["$provider|$code"]
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

    @Test fun `a plugin favourite never overwrites the xuper one with the same code, and a malformed code is ignored`() = runTest {
        val favs = FakeLiveFavoriteDao()
        favs.save(LiveFavoriteEntity("c1", "RCN", 5, null, updatedAt = 10))
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), favs, FakeLiveRecentDao())
        // No provider field: the live code alone says whose row it is.
        sync.apply("live_favorites", JSONObject().put("code", "plugin:own-server:c1").put("nombre", "Canal Uno").put("numero", 1).put("updatedAt", 20).put("deleted", true))
        sync.apply("live_favorites", JSONObject().put("code", "plugin::c9").put("nombre", "X").put("numero", 1).put("updatedAt", 20).put("deleted", false))
        sync.apply("live_favorites", JSONObject().put("code", "").put("nombre", "X").put("numero", 1).put("updatedAt", 20).put("deleted", false))
        assertEquals("RCN", favs.get("xuper", "c1")!!.nombre)
        assertEquals(false, favs.get("xuper", "c1")!!.deleted)
        assertEquals("Canal Uno", favs.get("plugin:own-server", "c1")!!.nombre)
        assertEquals(2, favs.rows.size)
    }

    @Test fun `a recent with a malformed live code is skipped, never stored as xuper's`() = runTest {
        val recents = FakeLiveRecentDao()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), recents)
        sync.apply("live_recents", JSONObject().put("code", "plugin:own-server:a:b").put("nombre", "X").put("vistoAt", 3).put("updatedAt", 20))
        sync.apply("live_recents", JSONObject().put("code", "plugin:own-server:c1").put("nombre", "Canal Uno").put("vistoAt", 3).put("updatedAt", 20))
        sync.apply("live_recents", JSONObject().put("code", "c2").put("nombre", "RCN").put("vistoAt", 3).put("updatedAt", 20))
        assertEquals(setOf("plugin:own-server|c1", "xuper|c2"), recents.rows.keys)
    }

    private fun ownRow(id: String, name: String, updatedAt: Long, deleted: Boolean = false) =
        JSONObject().put("id", id).put("kind", "CHANNEL").put("name", name)
            .put("url", "https://a.example.com/x.m3u8").put("updatedAt", updatedAt).put("deleted", deleted)

    @Test fun `own live sources merge last-write-wins and a newer tombstone deletes`() = runTest {
        val own = FakeOwnLiveSourceDao()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), own)
        sync.apply("own_live_sources", ownRow("s1", "Uno", 10))
        sync.apply("own_live_sources", ownRow("s1", "Viejo", 5))          // older: dropped
        assertEquals("Uno", own.rows.getValue("s1").name)
        sync.apply("own_live_sources", ownRow("s1", "Uno", 20, deleted = true))
        assertTrue(own.rows.getValue("s1").deleted)
        sync.apply("own_live_sources", ownRow("s1", "Resucitado", 15))    // a stale device must not resurrect it
        assertTrue(own.rows.getValue("s1").deleted)
    }

    @Test fun `an unreadable own source row is skipped`() = runTest {
        val own = FakeOwnLiveSourceDao()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), own)
        sync.apply("own_live_sources", ownRow("a:b", "X", 10))
        assertTrue(own.rows.isEmpty())
    }

    // ---- Pasted / file lists: the row plus its text in parts ----

    private fun ownSync(dao: FakeOwnLiveSourceDao) =
        SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), dao)

    private val pastedText = "#EXTM3U\n#EXTINF:-1 group-title=\"Noticias\",Uno\nhttps://tv.example.com/uno.m3u8"

    /** What the phone pushes after saving: the rows its DAO reports since [cursor], as the wire JSON, parts first. */
    private suspend fun pushed(dao: FakeOwnLiveSourceDao, cursor: Long = 0L): List<Pair<String, JSONObject>> =
        dao.partsSince(cursor).map { "own_live_list_parts" to ownListPartToJson(it) } +
            dao.getSince(cursor).map { "own_live_sources" to ownLiveSourceToJson(it) }

    private suspend fun phoneWithPasted(text: String = pastedText, now: Long = 1_000L): Pair<com.arkiv.player.data.live.OwnLiveStore, FakeOwnLiveSourceDao> {
        val dao = FakeOwnLiveSourceDao()
        val store = com.arkiv.player.data.live.OwnLiveStore(dao, { "p1" }, { now })
        store.save(null, com.arkiv.player.data.live.OwnSourceForm(com.arkiv.player.data.live.OwnKind.PLAYLIST, "Pegada", "", pastedText = text))
        return store to dao
    }

    @Test fun `a pasted list round-trips to the other device, in either arrival order, and works there`() = runTest {
        val (_, phone) = phoneWithPasted()
        val rows = pushed(phone)
        for (order in listOf(rows, rows.reversed())) {
            val tv = FakeOwnLiveSourceDao()
            val sync = ownSync(tv)
            order.forEach { (table, row) -> sync.apply(table, row) }
            assertEquals("kino-list:p1", tv.rows.getValue("p1").url)
            assertEquals(pastedText, com.arkiv.player.data.live.OwnLiveStore(tv).contentOf("p1"))
        }
    }

    @Test fun `every synced part fits one companion message`() = runTest {
        val rnd = java.util.Random(3)
        // The 2 MB cap with text gzip barely shrinks: the most parts a list can have.
        val line = { "#EXTINF:-1,${(1..20).map { 'a' + rnd.nextInt(26) }.joinToString("")}\nhttps://tv.example.com/${rnd.nextLong()}.m3u8\n" }
        val text = buildString { append("#EXTM3U\n"); while (length < com.arkiv.player.data.live.OwnPastedList.MAX_BYTES - 200) append(line()) }
        val (_, phone) = phoneWithPasted(text)
        val parts = pushed(phone).filter { it.first == "own_live_list_parts" }
        assertTrue(parts.size > 1)
        val pages = com.arkiv.player.companion.chunkRows(parts.map { it.second })
        for (page in pages) {
            val env = com.arkiv.player.companion.newEnvelope(com.arkiv.player.companion.TYPE_SYNC_ROWS,
                com.arkiv.player.companion.SyncRows("own_live_list_parts", page, 1L, false).toPayload()).encode()
            assertTrue(env.toByteArray().size <= com.arkiv.player.companion.CompanionProtocol.MAX_MESSAGE_BYTES)
        }
    }

    @Test fun `new text replaces the old on the other device, and late parts of the old text are ignored`() = runTest {
        val (store, phone) = phoneWithPasted(now = 1_000L)
        val tv = FakeOwnLiveSourceDao()
        val sync = ownSync(tv)
        val first = pushed(phone)
        first.forEach { (t, r) -> sync.apply(t, r) }
        val newer = "#EXTM3U\n#EXTINF:-1,Dos\nhttps://tv.example.com/dos.m3u8"
        val phone2 = com.arkiv.player.data.live.OwnLiveStore(phone, { "p1" }, { 2_000L })
        phone2.save("p1", com.arkiv.player.data.live.OwnSourceForm(com.arkiv.player.data.live.OwnKind.PLAYLIST, "Pegada", "kino-list:p1", pastedText = newer))
        pushed(phone, cursor = 1_000L).forEach { (t, r) -> sync.apply(t, r) }
        assertEquals(newer, com.arkiv.player.data.live.OwnLiveStore(tv).contentOf("p1"))
        // A stale copy of the first text's parts arrives late: dropped, and the old parts are gone.
        first.filter { it.first == "own_live_list_parts" }.forEach { (t, r) -> sync.apply(t, r) }
        assertEquals(newer, com.arkiv.player.data.live.OwnLiveStore(tv).contentOf("p1"))
        assertTrue(tv.partRows.values.all { it.digest == tv.rows.getValue("p1").contentDigest })
        assertEquals(newer, store.contentOf("p1"))
    }

    @Test fun `deleting a pasted list on one device drops its row and text on the other`() = runTest {
        val (store, phone) = phoneWithPasted()
        val tv = FakeOwnLiveSourceDao()
        val sync = ownSync(tv)
        val earlier = pushed(phone)
        earlier.forEach { (t, r) -> sync.apply(t, r) }
        store.delete("p1")
        assertTrue(phone.partRows.isEmpty())
        // The tombstone, sealed newer by the DB trigger on a real device.
        val tomb = ownLiveSourceToJson(phone.rows.getValue("p1").copy(updatedAt = 5_000L))
        sync.apply("own_live_sources", tomb)
        assertTrue(tv.rows.getValue("p1").deleted)
        assertTrue(tv.partRows.isEmpty())
        // Parts that arrive after the tombstone are not stored.
        earlier.filter { it.first == "own_live_list_parts" }.forEach { (t, r) -> sync.apply(t, r) }
        assertTrue(tv.partRows.isEmpty())
    }

    @Test fun `hostile pasted rows and parts are skipped`() = runTest {
        val (_, phone) = phoneWithPasted()
        val rows = pushed(phone)
        val source = rows.first { it.first == "own_live_sources" }.second
        val tv = FakeOwnLiveSourceDao()
        val sync = ownSync(tv)
        sync.apply("own_live_sources", JSONObject(source.toString()).put("url", "kino-list:otro"))     // another source's text
        sync.apply("own_live_sources", JSONObject(source.toString()).apply { remove("contentDigest") })  // no digest
        sync.apply("own_live_sources", JSONObject(source.toString()).put("kind", "CHANNEL"))
        assertTrue(tv.rows.isEmpty())
        val part = rows.first { it.first == "own_live_list_parts" }.second
        sync.apply("own_live_list_parts", JSONObject(part.toString()).put("data", "no es base64 !"))
        sync.apply("own_live_list_parts", JSONObject(part.toString()).put("parts", 500))
        sync.apply("own_live_list_parts", JSONObject(part.toString()).put("digest", "corto"))
        sync.apply("own_live_list_parts", JSONObject(part.toString()).put("sourceId", "a:b"))
        assertTrue(tv.partRows.isEmpty())
    }

    @Test fun `an older build refuses a pasted row instead of storing a list it cannot read`() = runTest {
        val (_, phone) = phoneWithPasted()
        val source = pushed(phone).first { it.first == "own_live_sources" }.second
        // What an older build checks a row's url with: not an http address, so jsonToOwnLiveSource there returns null.
        assertTrue(com.arkiv.player.data.live.OwnSourceValidator.checkUrl(source.getString("url")) is com.arkiv.player.data.live.OwnUrlCheck.Refused)
        // And a row from an older build (no digest field) still reads as before.
        val legacy = ownRow("s1", "Uno", 10)
        assertTrue(!legacy.has("contentDigest"))
        val tv = FakeOwnLiveSourceDao()
        ownSync(tv).apply("own_live_sources", legacy)
        assertEquals(null, tv.rows.getValue("s1").contentDigest)
    }

    private fun pluginRow(id: String, updatedAt: Long, deleted: Boolean = false, address: String = "kinotvapp/kino-plugin-archive") =
        JSONObject().put("id", id).put("address", address).put("name", "Archive").put("version", "1.0.0")
            .put("enabled", true).put("updatedAt", updatedAt).put("deleted", deleted)

    @Test fun `plugin installs merge last-write-wins and tell the reconciler what changed`() = runTest {
        val dao = com.arkiv.player.data.plugin.sync.FakePluginInstallDao()
        val told = mutableListOf<String>()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), null, dao) { told += it }
        sync.apply("plugin_installs", pluginRow("archive", 10))
        sync.apply("plugin_installs", pluginRow("archive", 5, deleted = true)) // older: dropped, not told
        sync.apply("plugin_installs", pluginRow("archive", 20, deleted = true))
        assertTrue(dao.rows.getValue("archive").deleted)
        assertEquals(listOf("archive", "archive"), told)
    }

    @Test fun `a plugin row this build must not trust is never stored`() = runTest {
        val dao = com.arkiv.player.data.plugin.sync.FakePluginInstallDao()
        val told = mutableListOf<String>()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), null, dao) { told += it }
        sync.apply("plugin_installs", pluginRow("own", 10))
        sync.apply("plugin_installs", pluginRow("xuper", 10))
        sync.apply("plugin_installs", pluginRow("archive", 10, address = "https://evil.example.com/a"))
        assertTrue(dao.rows.isEmpty())
        assertTrue(told.isEmpty())
    }

    @Test fun `nuvio repos merge last-write-wins and a hostile address is never stored`() = runTest {
        val dao = com.arkiv.player.data.plugin.sync.FakeNuvioRepoDao()
        val sync = SyncApply(FakeItemDao(), FakePlaybackDao(), FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao(), null, null, dao)
        fun r(address: String, at: Long, deleted: Boolean = false) = JSONObject().put("address", address).put("updatedAt", at).put("deleted", deleted)
        sync.apply("nuvio_repos", r("a/b", 10))
        sync.apply("nuvio_repos", r("a/b", 5, deleted = true)) // older: dropped
        assertTrue(!dao.rows.getValue("a/b").deleted)
        sync.apply("nuvio_repos", r("a/b", 20, deleted = true))
        assertTrue(dao.rows.getValue("a/b").deleted)
        sync.apply("nuvio_repos", r("https://evil.example.com/x", 30))
        assertEquals(setOf("a/b"), dao.rows.keys)
    }
}

private class FakeOwnLiveSourceDao : com.arkiv.player.data.db.OwnLiveSourceDao {
    val rows = HashMap<String, com.arkiv.player.data.db.OwnLiveSourceEntity>()
    override fun flowAll() = kotlinx.coroutines.flow.flowOf(rows.values.filter { !it.deleted })
    override suspend fun all() = rows.values.filter { !it.deleted }
    override suspend fun get(id: String) = rows[id]
    override suspend fun save(s: com.arkiv.player.data.db.OwnLiveSourceEntity) { rows[s.id] = s }
    override suspend fun delete(id: String) { rows[id]?.let { rows[id] = it.copy(deleted = true) } }
    override suspend fun count() = rows.values.count { !it.deleted }
    override suspend fun getSince(cursor: Long) = rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    val partRows = LinkedHashMap<Pair<String, Int>, com.arkiv.player.data.db.OwnListPartEntity>()
    override suspend fun parts(sourceId: String) = partRows.values.filter { it.sourceId == sourceId }.sortedBy { it.part }
    override suspend fun part(sourceId: String, part: Int) = partRows[sourceId to part]
    override suspend fun saveParts(parts: List<com.arkiv.player.data.db.OwnListPartEntity>) { parts.forEach { partRows[it.sourceId to it.part] = it } }
    override suspend fun deleteParts(sourceId: String) { partRows.keys.removeAll { it.first == sourceId } }
    override suspend fun deleteStaleParts(sourceId: String, digest: String, parts: Int) {
        partRows.values.removeAll { it.sourceId == sourceId && (it.digest != digest || it.part >= parts) }
    }
    override suspend fun partsSince(cursor: Long) = partRows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}
