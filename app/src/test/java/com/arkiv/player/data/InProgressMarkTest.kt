package com.arkiv.player.data

import com.arkiv.player.data.db.PlaybackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InProgressMarkTest {

    private val halfway = PlaybackEntity("plugin:a:m1::movie", 600_000L, 7_200_000L, watched = false, lastPlayedAt = 1_000L, updatedAt = 1_001L)

    @Test
    fun `the mark keeps position and duration and only moves lastPlayedAt`() {
        val row = inProgressRow(halfway.episodeId, halfway, now = 5_000L)
        assertEquals(PlaybackEntity(halfway.episodeId, 600_000L, 7_200_000L, false, 5_000L), row)
    }

    @Test
    fun `a watched chapter gets no mark`() {
        assertNull(inProgressRow("e", halfway.copy(watched = true), now = 5_000L))
    }

    @Test
    fun `undo with no previous row deletes it`() {
        val written = inProgressRow("e", null, now = 5_000L)!!
        val mark = InProgressMark("e", previous = null, written = written)
        // Room stamped updatedAt on insert: that difference must not count as "someone wrote".
        assertEquals(InProgressUndo.Delete, mark.undoAgainst(written.copy(updatedAt = 5_002L)))
    }

    @Test
    fun `undo with a previous row restores it byte for byte`() {
        val written = inProgressRow(halfway.episodeId, halfway, now = 5_000L)!!
        val mark = InProgressMark(halfway.episodeId, previous = halfway, written = written)
        assertEquals(InProgressUndo.Restore(halfway), mark.undoAgainst(written.copy(updatedAt = 5_002L)))
    }

    @Test
    fun `a row written after the mark is kept`() {
        val written = inProgressRow("e", null, now = 5_000L)!!
        val mark = InProgressMark("e", previous = null, written = written)
        val saved = PlaybackEntity("e", 12_000L, 7_200_000L, false, 9_000L, updatedAt = 9_001L)
        assertEquals(InProgressUndo.Keep, mark.undoAgainst(saved))
    }

    @Test
    fun `a failed pick that created the item takes the item back`() {
        assertTrue(dropsUnplayedPick(createdByPick = true, previous = null, itemHistoryRows = 0, itemDownloads = 0))
    }

    @Test
    fun `an item that was there before, played before, or has a download stays`() {
        assertFalse(dropsUnplayedPick(createdByPick = false, previous = null, itemHistoryRows = 0, itemDownloads = 0))
        assertFalse(dropsUnplayedPick(createdByPick = true, previous = halfway, itemHistoryRows = 1, itemDownloads = 0))
        assertFalse(dropsUnplayedPick(createdByPick = true, previous = null, itemHistoryRows = 1, itemDownloads = 0))
        assertFalse(dropsUnplayedPick(createdByPick = true, previous = null, itemHistoryRows = 0, itemDownloads = 1))
    }

    @Test
    fun `a row that is already gone stays gone`() {
        val written = inProgressRow("e", null, now = 5_000L)!!
        assertEquals(InProgressUndo.Keep, InProgressMark("e", null, written).undoAgainst(null))
    }
}
