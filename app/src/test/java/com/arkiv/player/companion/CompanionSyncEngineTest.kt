package com.arkiv.player.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import com.arkiv.player.data.sync.SyncApply
import com.arkiv.player.data.sync.SyncCursorStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD note on the "spy SyncApply" question the brief raised: [SyncApply] is a concrete, non-open
 * class, so it can't be subclassed or mocked cleanly. The least-invasive testable path -- approach
 * (a) from the brief -- is to construct a REAL [SyncApply] over fakes of its five DAOs (the exact
 * fakes [com.arkiv.player.data.sync.SyncApplyTest] already uses, duplicated here since those are
 * `private` to that file) and assert on the fake DAO's stored state after the engine calls
 * `apply.apply(...)`. Only `playback` needs to be behaviorally real for this suite; the other four
 * DAOs exist solely to satisfy [SyncApply]'s constructor, same convention as `SyncApplyTest`.
 */
class CompanionSyncEngineTest {

    @Test fun `sync_hello triggers a per-table push filtered by since, then sync_done`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val source = FakeSyncSource().apply {
            addRow("playback", playbackRow(episodeId = "e-old", updatedAt = 3))
            addRow("playback", playbackRow(episodeId = "e-eq", updatedAt = 5)) // == cursor, excluded
            addRow("playback", playbackRow(episodeId = "e1", updatedAt = 10))
            addRow("playback", playbackRow(episodeId = "e2", updatedAt = 20))
        }
        val (apply, _) = fakeSyncApply()
        val engine = CompanionSyncEngine(
            this, incoming, { sent += it }, source, apply, SyncCursorStore(FakeContext()), changes,
        )

        engine.start(MutableStateFlow<String?>(null)) // no peer yet -- no initial hello, but the collector still runs
        testScheduler.advanceUntilIdle()

        incoming.emit(newEnvelope(TYPE_SYNC_HELLO, SyncHello(mapOf("playback" to 5L)).toPayload()))
        testScheduler.advanceUntilIdle()

        val rowsSent = sent.filter { it.type == TYPE_SYNC_ROWS }.map { SyncRows.fromPayload(it.payload) }
        // Every table with no rows in the fake source (items, episodes, skip_markers,
        // live_favorites, live_recents) produced nothing to push.
        assertEquals(listOf("playback"), rowsSent.map { it.table })
        assertEquals(listOf("e1", "e2"), rowsSent.single().rows.map { it.getString("episodeId") })
        assertEquals(20L, rowsSent.single().hwm)
        assertTrue(!rowsSent.single().more)

        val done = sent.filter { it.type == TYPE_SYNC_DONE }.map { SyncDone.fromPayload(it.payload) }
        assertEquals(1, done.size)
        assertEquals(20L, done.single().hwm)

        engine.stop()
        testScheduler.advanceUntilIdle()
    }

    /**
     * Regression test for the production bug: `CompanionManager.startSync` calls `engine.start(...)`
     * SYNCHRONOUSLY from the `ON_START` lifecycle observer, while the companion link is typically
     * still coming up asynchronously -- so a one-shot check of the peer AT [start] TIME (the old
     * `peerId: () -> String?` lambda) almost always saw null and skipped the hello for good. The
     * previous version of this suite always started the engine with a constant non-null peer (e.g.
     * `engine.start { "peerA" }`), which fired the hello but never exercised -- and so never caught
     * -- that startup-ordering gap. This test starts disconnected, on purpose, THEN connects.
     */
    @Test fun `sync_hello is withheld while disconnected and fires on each transition to a connected peer`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val (apply, _) = fakeSyncApply()
        val cursors = SyncCursorStore(FakeContext())
        val engine = CompanionSyncEngine(
            this, incoming, { sent += it }, FakeSyncSource(), apply, cursors, changes,
        )
        val peer = MutableStateFlow<String?>(null)

        // Mirrors CompanionManager.startSync: start() runs before the link has actually connected.
        engine.start(peer)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "no peer yet -- must not send a hello on a null (disconnected) signal",
            0,
            sent.count { it.type == TYPE_SYNC_HELLO },
        )

        cursors.setPulled("peerA", "playback", 42L)

        // The real Connected edge, arriving asynchronously (as in production) some time after start().
        peer.value = "peerA"
        testScheduler.advanceUntilIdle()

        var hellos = sent.filter { it.type == TYPE_SYNC_HELLO }.map { SyncHello.fromPayload(it.payload) }
        assertEquals("exactly one hello on the Connected transition", 1, hellos.size)
        assertEquals(42L, hellos.single().since["playback"])
        assertEquals("untouched table keeps the default (0) cursor", 0L, hellos.single().since["items"])

        // A second, distinct peer connecting (e.g. after a reconnect to a different device) is its
        // own transition and gets its own hello, carrying ITS OWN persisted cursors.
        cursors.setPulled("peerB", "playback", 7L)
        peer.value = "peerB"
        testScheduler.advanceUntilIdle()

        hellos = sent.filter { it.type == TYPE_SYNC_HELLO }.map { SyncHello.fromPayload(it.payload) }
        assertEquals("a later, distinct Connected transition sends its own hello too", 2, hellos.size)
        assertEquals(7L, hellos.last().since["playback"])

        engine.stop()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `incoming sync_rows are applied per row and the pull cursor advances only on the final page`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val source = FakeSyncSource()
        val (apply, playbackDao) = fakeSyncApply()
        val cursors = SyncCursorStore(FakeContext())
        val engine = CompanionSyncEngine(this, incoming, { sent += it }, source, apply, cursors, changes)
        val peer = "peerA"

        engine.start(MutableStateFlow(peer)) // peer known -> sends its own initial hello, irrelevant to this test
        testScheduler.advanceUntilIdle()

        // First page: more=true -- rows are applied, but the cursor must NOT advance yet.
        incoming.emit(
            newEnvelope(
                TYPE_SYNC_ROWS,
                SyncRows("playback", listOf(playbackRow("e1", updatedAt = 10)), hwm = 10, more = true).toPayload(),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(10L, playbackDao.get("e1")?.updatedAt)
        assertEquals("cursor must not move on a page that has more pages coming", 0L, cursors.pulled(peer, "playback"))

        // Final page: more=false -- the cursor advances to THIS page's hwm.
        incoming.emit(
            newEnvelope(
                TYPE_SYNC_ROWS,
                SyncRows("playback", listOf(playbackRow("e2", updatedAt = 20)), hwm = 20, more = false).toPayload(),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(20L, playbackDao.get("e2")?.updatedAt)
        assertEquals(20L, cursors.pulled(peer, "playback"))

        // A sync_done carries no cursor work of its own (R2) -- the cursor stays exactly where the
        // final sync_rows page left it.
        incoming.emit(newEnvelope(TYPE_SYNC_DONE, SyncDone(hwm = 20).toPayload()))
        testScheduler.advanceUntilIdle()
        assertEquals(20L, cursors.pulled(peer, "playback"))

        engine.stop()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `a changes tick pushes only rows newer than the last push watermark`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val source = FakeSyncSource().apply {
            addRow("playback", playbackRow("e1", updatedAt = 10))
        }
        val (apply, _) = fakeSyncApply()
        val engine = CompanionSyncEngine(
            this, incoming, { sent += it }, source, apply, SyncCursorStore(FakeContext()), changes,
        )

        engine.start(MutableStateFlow<String?>(null))
        testScheduler.advanceUntilIdle()

        changes.emit(Unit)
        testScheduler.advanceUntilIdle() // the debounce timer is virtual time -- idling past it fires the push

        val firstPush = sent.filter { it.type == TYPE_SYNC_ROWS }.map { SyncRows.fromPayload(it.payload) }
        assertEquals(1, firstPush.size)
        assertEquals(listOf("e1"), firstPush.single().rows.map { it.getString("episodeId") })

        sent.clear()
        source.addRow("playback", playbackRow("e2", updatedAt = 20))
        changes.emit(Unit)
        testScheduler.advanceUntilIdle()

        val secondPush = sent.filter { it.type == TYPE_SYNC_ROWS }.map { SyncRows.fromPayload(it.payload) }
        assertEquals(1, secondPush.size)
        assertEquals(
            "already-pushed rows (e1, updatedAt=10) must not be re-sent",
            listOf("e2"),
            secondPush.single().rows.map { it.getString("episodeId") },
        )

        engine.stop()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `start is idempotent -- a second call does not send a second initial hello`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val (apply, _) = fakeSyncApply()
        val engine = CompanionSyncEngine(
            this, incoming, { sent += it }, FakeSyncSource(), apply, SyncCursorStore(FakeContext()), changes,
        )

        val peer = MutableStateFlow<String?>("peerA")
        engine.start(peer)
        engine.start(peer) // must no-op, same guard as CompanionPlayReceiver.start
        testScheduler.advanceUntilIdle()

        assertEquals(1, sent.count { it.type == TYPE_SYNC_HELLO })

        engine.stop()
        testScheduler.advanceUntilIdle()
    }
}

// ---- fakes ----

private fun playbackRow(
    episodeId: String,
    updatedAt: Long,
    positionMs: Long = 1000,
    deleted: Boolean = false,
) = JSONObject().apply {
    put("episodeId", episodeId)
    put("positionMs", positionMs)
    put("durationMs", 9000L)
    put("watched", false)
    put("lastPlayedAt", 0L)
    put("updatedAt", updatedAt)
    put("deleted", deleted)
}

private class FakeSyncSource : SyncSource {
    private val rows = mutableMapOf<String, MutableList<JSONObject>>()

    fun addRow(table: String, row: JSONObject) {
        rows.getOrPut(table) { mutableListOf() }.add(row)
    }

    override suspend fun changedSince(table: String, cursor: Long): List<JSONObject> =
        (rows[table] ?: emptyList())
            .filter { it.optLong("updatedAt") > cursor }
            .sortedBy { it.optLong("updatedAt") }
}

/** Builds a real [SyncApply] over fake DAOs (see the class KDoc for why). Returns it paired with
 *  the [FakePlaybackDao] so tests can assert on stored playback rows. */
private fun fakeSyncApply(): Pair<SyncApply, FakePlaybackDao> {
    val playbackDao = FakePlaybackDao()
    val apply = SyncApply(FakeItemDao(), playbackDao, FakeSkipMarkerDao(), FakeLiveFavoriteDao(), FakeLiveRecentDao())
    return apply to playbackDao
}

/** In-memory fake of [ItemDao]. Exists only to satisfy [SyncApply]'s constructor -- this suite
 *  never applies an `items`/`episodes` row. Same convention as `SyncApplyTest`'s fake. */
private class FakeItemDao : ItemDao {
    val items = mutableMapOf<String, ItemEntity>()
    val episodes = mutableMapOf<String, EpisodeEntity>()

    override suspend fun upsertItem(item: ItemEntity) { items[item.identifier] = item }
    override suspend fun markEpisodesSeen(itemId: String, count: Int) {}
    override suspend fun upsertEpisodes(episodes: List<EpisodeEntity>) {
        episodes.forEach { this.episodes[it.id] = it }
    }
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

/** In-memory fake of [LiveFavoriteDao]. Exists only to satisfy [SyncApply]'s constructor. */
private class FakeLiveFavoriteDao : LiveFavoriteDao {
    val rows = mutableMapOf<String, LiveFavoriteEntity>()

    override fun flowAll(): Flow<List<LiveFavoriteEntity>> = MutableStateFlow(emptyList())
    override suspend fun save(f: LiveFavoriteEntity) { rows[f.code] = f }
    override suspend fun delete(code: String) {}
    override suspend fun isFavorite(code: String): Boolean = false
    override suspend fun getAll(): List<LiveFavoriteEntity> = rows.values.toList()
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> = emptyList()
    override suspend fun get(code: String): LiveFavoriteEntity? = rows[code]
}

/** In-memory fake of [LiveRecentDao]. Exists only to satisfy [SyncApply]'s constructor. */
private class FakeLiveRecentDao : LiveRecentDao {
    val rows = mutableMapOf<String, LiveRecentEntity>()

    override fun flowRecent(limit: Int): Flow<List<LiveRecentEntity>> = MutableStateFlow(emptyList())
    override suspend fun record(r: LiveRecentEntity) { rows[r.code] = r }
    override suspend fun getAll(): List<LiveRecentEntity> = rows.values.toList()
    override suspend fun getLiveRecentsSince(cursor: Long): List<LiveRecentEntity> = emptyList()
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun get(code: String): LiveRecentEntity? = rows[code]
}

/**
 * A minimal in-memory Context/SharedPreferences fake for [SyncCursorStore]. This repo has no
 * Robolectric (see `SyncCursorStoreTest`'s note), so a real read-after-write on `SharedPreferences`
 * needs a stateful fake. Duplicated from `SyncCursorStoreTest` (that one is `private` to its file).
 */
private class FakeContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, MutableMap<String, Long>>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val backing = stores.getOrPut(name ?: "") { mutableMapOf() }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLong" -> backing[args[0] as String] ?: (args[1] as Long)
                "contains" -> backing.containsKey(args[0] as String)
                "edit" -> newEditor(backing)
                else -> null
            }
        } as SharedPreferences
    }

    private fun newEditor(backing: MutableMap<String, Long>): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putLong" -> { backing[args[0] as String] = args[1] as Long; proxy }
                "remove" -> { backing.remove(args[0] as String); proxy }
                "clear" -> { backing.clear(); proxy }
                "commit" -> true
                "apply" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor
}
