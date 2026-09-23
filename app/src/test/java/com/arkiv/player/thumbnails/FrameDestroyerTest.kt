package com.arkiv.player.thumbnails

import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `destroy` leaves a tombstone (not a `DELETE`) -- until Task 5 that was so the deletion would
 * travel through cloud sync; `destroyAll` (logout wipe) is still a physical `DELETE` on purpose
 * -- see the class's own doc.
 */
class FrameDestroyerTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = FrameStore(temp.newFolder("frames"))

    @Test
    fun `destroy leaves a tombstone instead of deleting the row`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        dao.rows["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 90_000, capturedAt = 10, updatedAt = 10, deleted = 0,
        )
        val destroyer = FrameDestroyer(dao = dao, now = { 999L })

        destroyer.destroy("ep-1")

        val row = dao.getIncludingDeleted("ep-1")
        assertTrue("the row must still exist (tombstone, not DELETE)", row != null)
        assertEquals(1, row!!.deleted)
        assertEquals(999L, row.updatedAt)
    }

    @Test
    fun `destroy creates a tombstone even without a prior row`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        val destroyer = FrameDestroyer(dao = dao, now = { 5L })

        destroyer.destroy("ep-without-frame")

        val row = dao.getIncludingDeleted("ep-without-frame")
        assertEquals(1, row?.deleted)
        assertEquals(5L, row?.updatedAt)
    }

    @Test
    fun `destroy deletes the file from the store`() = runBlocking {
        val store = store()
        val dao = FakeEpisodeFrameDao()
        store.save("ep-1", byteArrayOf(1, 2, 3))
        val destroyer = FrameDestroyer(store, dao) { 1L }

        destroyer.destroy("ep-1")

        assertNull(store.pathIfExists("ep-1"))
    }

    /**
     * Regression from a critical review finding: `savePlayback` calls `destroy` on EVERY player
     * tick (~5 s) while the chapter stays watched, with no guard of its own. If every call
     * rewrote `updatedAt` with the clock at that moment, it would needlessly invalidate the
     * `episode_frame`-driven "Continue watching" Flow the rest of the chapter -- and, before
     * Task 5, would also have kept re-queuing the same row for the push to PocketBase.
     */
    @Test
    fun `destroying twice in a row does not rewrite updatedAt the second time`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        var currentTime = 100L
        val destroyer = FrameDestroyer(dao = dao, now = { currentTime })

        destroyer.destroy("ep-1")
        assertEquals(100L, dao.getIncludingDeleted("ep-1")?.updatedAt)

        currentTime = 999L // if destroy() read the clock again, updatedAt would change
        destroyer.destroy("ep-1")

        assertEquals(
            "the tombstone was already sealed: the second call must not touch updatedAt",
            100L,
            dao.getIncludingDeleted("ep-1")?.updatedAt,
        )
    }

    @Test
    fun `destroy deletes an orphan file even if the row is already a tombstone`() = runBlocking {
        val store = store()
        val dao = FakeEpisodeFrameDao()
        dao.rows["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 0, capturedAt = 0, updatedAt = 50, deleted = 1,
        )
        store.save("ep-1", byteArrayOf(9)) // orphan file: the row was already deleted
        val destroyer = FrameDestroyer(store, dao) { 999L }

        destroyer.destroy("ep-1")

        assertNull(
            "the orphan file is deleted regardless, even though the row was already a tombstone",
            store.pathIfExists("ep-1"),
        )
        assertEquals("and the row's updatedAt is untouched", 50L, dao.getIncludingDeleted("ep-1")?.updatedAt)
    }

    @Test
    fun `a normal get does not see the tombstone destroy left`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        val destroyer = FrameDestroyer(dao = dao, now = { 1L })

        destroyer.destroy("ep-1")

        assertNull("get() filters deleted = 0: a tombstone looks nonexistent", dao.get("ep-1"))
    }

    @Test
    fun `destroyAll does a physical DELETE, leaves no tombstones`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        dao.rows["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 1, capturedAt = 1, updatedAt = 1, deleted = 0,
        )
        dao.rows["ep-2"] = EpisodeFrameEntity(
            episodeId = "ep-2", positionMs = 1, capturedAt = 1, updatedAt = 1, deleted = 0,
        )
        val destroyer = FrameDestroyer(dao = dao)

        destroyer.destroyAll()

        assertTrue("nothing can be left, not even tombstones", dao.rows.isEmpty())
    }
}
