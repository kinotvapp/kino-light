package com.arkiv.player.thumbnails

import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.HistoryRow
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.LastPlayedRow
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.ProgressWithNextRow
import com.arkiv.player.data.db.WatchedRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A chapter that's ALREADY WATCHED can't be left with a frame.
 *
 * On leaving the player, `onDispose` fires `saveProgress` (which marks watched and destroys the
 * frame past 60%) and the capture right after; since compressing the JPEG costs tens of ms, the
 * capture lands last and left the finished chapter with a LIVE frame nobody was going to delete.
 * Before Task 5 that would also get uploaded to PocketBase and propagated; without cloud sync it's
 * now an orphan local frame, still exactly what this guard exists to stop.
 *
 * Exercises [FrameCapturer.publish] and not `capture`: that one needs a `TextureView`, which
 * doesn't exist outside a device. It's the same spot where the guard lives.
 */
class FrameCapturerWatchedTest {

    @get:Rule val temp = TemporaryFolder()

    /** Minimal fake: [FrameCapturer] only queries [get]. */
    private class FakePlaybackDao : PlaybackDao {
        val rows = mutableMapOf<String, PlaybackEntity>()

        override suspend fun upsert(playback: PlaybackEntity) { rows[playback.episodeId] = playback }
        override suspend fun get(episodeId: String): PlaybackEntity? = rows[episodeId]
        override fun observe(episodeId: String): Flow<PlaybackEntity?> = MutableStateFlow(rows[episodeId])
        override fun observeProgressWithNext(): Flow<List<ProgressWithNextRow>> =
            MutableStateFlow(emptyList())
        override suspend fun continueWatchingRows(episodeIds: List<String>): List<ContinueRow> = emptyList()
        override fun observeWatched(): Flow<List<WatchedRow>> = MutableStateFlow(emptyList())
        override fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>> =
            MutableStateFlow(emptyList())
        // Another branch added this (library ordering by last watched) while this one was open.
        // FrameCapturer doesn't use it; it's here only so the fake keeps implementing the DAO.
        override fun observeLastPlayed(): Flow<List<LastPlayedRow>> =
            MutableStateFlow(emptyList())
        // Task 7 added this (local history for "For you"). FrameCapturer doesn't use it; it's here
        // only so the fake keeps implementing the DAO.
        override suspend fun recentHistory(limit: Int): List<HistoryRow> = emptyList()
        // Task 2 (companion sync push side) added this. FrameCapturer doesn't use it; it's here
        // only so the fake keeps implementing the DAO.
        override suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity> = emptyList()
    }

    private fun progress(episodeId: String, watched: Boolean) = PlaybackEntity(
        episodeId = episodeId, positionMs = 60_000, durationMs = 100_000, watched = watched,
        lastPlayedAt = 1L, updatedAt = 1L,
    )

    private fun capturer(store: FrameStore, frames: FakeEpisodeFrameDao, playback: FakePlaybackDao) =
        FrameCapturer(store, frames, playback) { 777L }

    @Test fun `writes nothing if the chapter is already watched`() = runBlocking {
        val store = FrameStore(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()
        val playback = FakePlaybackDao().apply { rows["ep-1"] = progress("ep-1", watched = true) }

        val saved = capturer(store, frames, playback).publish("ep-1", 90_000, byteArrayOf(1, 2, 3))

        assertFalse("the capture has to report it didn't save", saved)
        assertNull("neither the JPEG", store.pathIfExists("ep-1"))
        assertTrue("nor the row: a finished chapter has no frame", frames.rows.isEmpty())
    }

    @Test fun `a chapter halfway through does capture`() = runBlocking {
        val store = FrameStore(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()
        val playback = FakePlaybackDao().apply { rows["ep-1"] = progress("ep-1", watched = false) }

        val saved = capturer(store, frames, playback).publish("ep-1", 90_000, byteArrayOf(1, 2, 3))

        assertTrue(saved)
        assertNotNull(store.pathIfExists("ep-1"))
        assertEquals(90_000L, frames.rows.getValue("ep-1").positionMs)
        assertEquals(777L, frames.rows.getValue("ep-1").updatedAt)
        assertEquals("a local capture is never born marked as remote", 0, frames.rows.getValue("ep-1").origenRemoto)
    }

    /** With no progress row (the first play) there's nothing to stop capturing either. */
    @Test fun `with no progress row it still captures`() = runBlocking {
        val store = FrameStore(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()

        val saved = capturer(store, frames, FakePlaybackDao()).publish("ep-1", 5_000, byteArrayOf(9))

        assertTrue(saved)
        assertNotNull(store.pathIfExists("ep-1"))
    }
}
