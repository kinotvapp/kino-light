package com.arkiv.player.ui.player

import com.arkiv.player.data.InProgressMark
import com.arkiv.player.data.InProgressUndo
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.inProgressRow
import com.arkiv.player.data.undoAgainst
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attempt ledger against an in-memory `playback` table that behaves like the repository:
 * the mark is [inProgressRow], Room's trigger stamps `updatedAt` on insert, the undo is [undoAgainst].
 */
class PlaybackAttemptsTest {

    private class Table {
        val rows = mutableMapOf<String, PlaybackEntity>()
        var clock = 1_000L
        private fun stamp(row: PlaybackEntity) = if (row.updatedAt == 0L) row.copy(updatedAt = ++clock) else row

        suspend fun mark(id: String): InProgressMark? {
            val previous = rows[id]
            val written = inProgressRow(id, previous, now = ++clock) ?: return null
            rows[id] = stamp(written)
            return InProgressMark(id, previous, written)
        }

        suspend fun undo(mark: InProgressMark) {
            when (val u = mark.undoAgainst(rows[mark.episodeId])) {
                InProgressUndo.Keep -> Unit
                InProgressUndo.Delete -> rows.remove(mark.episodeId)
                is InProgressUndo.Restore -> rows[mark.episodeId] = u.row
            }
        }

        /** `ArkivRepository.savePlayback`, as far as the row goes. */
        fun save(id: String, pos: Long, dur: Long) {
            rows[id] = stamp(PlaybackEntity(id, pos, dur, watched = false, lastPlayedAt = ++clock))
        }
    }

    private val table = Table()
    private val attempts = PlaybackAttempts(mark = table::mark, undo = table::undo)

    private val earlier = PlaybackEntity("plugin:a:s1::s1e2", 600_000L, 2_400_000L, watched = false, lastPlayedAt = 10L, updatedAt = 11L)
    private val finished = PlaybackEntity("plugin:a:s1::s1e1", 2_300_000L, 2_400_000L, watched = true, lastPlayedAt = 20L, updatedAt = 21L)

    @Test
    fun `a failure before starting with no previous row deletes the row`() = runTest {
        val a = attempts.open("plugin:a:m1::movie")
        attempts.begin(a, writeMark = true)
        assertTrue("the early mark is there while resolving", "plugin:a:m1::movie" in table.rows)
        attempts.failed(a)
        assertNull(table.rows["plugin:a:m1::movie"])
    }

    @Test
    fun `a failure before starting restores the previous in-progress row exactly`() = runTest {
        table.rows[earlier.episodeId] = earlier
        val a = attempts.open(earlier.episodeId)
        attempts.begin(a, writeMark = true)
        assertEquals(earlier.positionMs, table.rows[earlier.episodeId]?.positionMs)
        assertTrue(table.rows[earlier.episodeId]!!.lastPlayedAt > earlier.lastPlayedAt)
        attempts.failed(a)
        assertEquals(earlier, table.rows[earlier.episodeId])
    }

    @Test
    fun `a watched chapter is never touched, failed or not`() = runTest {
        table.rows[finished.episodeId] = finished
        val a = attempts.open(finished.episodeId)
        attempts.begin(a, writeMark = true)
        attempts.failed(a)
        attempts.close()
        assertEquals(finished, table.rows[finished.episodeId])
    }

    @Test
    fun `an attempt that started keeps its row and the saves update it as today`() = runTest {
        val a = attempts.open("e1")
        attempts.begin(a, writeMark = true)
        attempts.started("e1")
        table.save("e1", 20_000L, 7_200_000L)
        attempts.close()
        assertEquals(20_000L, table.rows["e1"]?.positionMs)
        assertEquals(7_200_000L, table.rows["e1"]?.durationMs)
    }

    @Test
    fun `leaving while resolving takes the mark back`() = runTest {
        table.rows[earlier.episodeId] = earlier
        attempts.begin(attempts.open(earlier.episodeId), writeMark = true)
        attempts.close()
        assertEquals(earlier, table.rows[earlier.episodeId])
        attempts.begin(attempts.open("e2"), writeMark = true)
        attempts.close()
        assertFalse("e2" in table.rows)
    }

    @Test
    fun `a second load settles the first attempt that never started`() = runTest {
        val a = attempts.open("e1")
        attempts.begin(a, writeMark = true)
        val b = attempts.open("e2")
        attempts.begin(b, writeMark = true)
        assertFalse("e1 never played", "e1" in table.rows)
        assertTrue("e2" in table.rows)
        // e1's own resolve fails late: it no longer owns anything.
        attempts.failed(a)
        assertTrue("e2" in table.rows)
    }

    @Test
    fun `a second load keeps the first attempt that did start`() = runTest {
        val a = attempts.open("e1")
        attempts.begin(a, writeMark = true)
        attempts.started("e1")
        attempts.begin(attempts.open("e2"), writeMark = true)
        assertTrue("e1" in table.rows)
    }

    @Test
    fun `an attempt superseded before its coroutine ran never marks`() = runTest {
        val a = attempts.open("e1")
        val b = attempts.open("e2")
        attempts.begin(a, writeMark = true)
        assertFalse("never written, not written-then-undone", "e1" in table.rows)
        attempts.begin(b, writeMark = true)
        assertFalse("e1" in table.rows)
        assertTrue("e2" in table.rows)
    }

    @Test
    fun `leaving before the load's coroutine ran leaves nothing behind`() = runTest {
        val a = attempts.open("e1")
        attempts.close()
        attempts.begin(a, writeMark = true)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `quick loads racing on the lock end in order`() = runTest {
        table.rows[earlier.episodeId] = earlier
        val a = attempts.open(earlier.episodeId)
        val first = launch { attempts.begin(a, writeMark = true) }
        val b = attempts.open(earlier.episodeId)
        val second = launch { attempts.begin(b, writeMark = true) }
        first.join(); second.join()
        attempts.failed(b)
        assertEquals("a retry of the same chapter that fails puts back the original", earlier, table.rows[earlier.episodeId])
    }

    @Test
    fun `a retry of the same chapter restores the original row, not the first attempt's mark`() = runTest {
        table.rows[earlier.episodeId] = earlier
        val a = attempts.open(earlier.episodeId)
        attempts.begin(a, writeMark = true)
        attempts.failed(a)
        val b = attempts.open(earlier.episodeId)
        attempts.begin(b, writeMark = true)
        attempts.close()
        assertEquals(earlier, table.rows[earlier.episodeId])
    }

    @Test
    fun `a live channel ends the previous attempt without marking anything`() = runTest {
        attempts.begin(attempts.open("e1"), writeMark = true)
        attempts.begin(attempts.open("live:xuper:7"), writeMark = false)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `a start signal for another chapter doesn't save this attempt`() = runTest {
        attempts.begin(attempts.open("e2"), writeMark = true)
        attempts.started("e1")
        attempts.close()
        assertFalse("e2" in table.rows)
    }

    @Test
    fun `a save that slipped in without the start signal still wins`() = runTest {
        attempts.begin(attempts.open("e1"), writeMark = true)
        table.save("e1", 3_000L, 7_200_000L)
        attempts.close()
        assertEquals(3_000L, table.rows["e1"]?.positionMs)
    }
}
