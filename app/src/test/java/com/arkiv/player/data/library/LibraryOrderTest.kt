package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of "My library": the last thing you watched goes first, and what you just added too,
 * so you don't have to scroll down to find the series you've been watching.
 */
class LibraryOrderTest {

    private fun row(id: String, addedAt: Long) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = 10,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = "series",
        source = "web",
    )

    private fun group(key: String, vararg rows: LibraryRow) =
        LibraryGroup(key = key, primary = rows.first(), members = rows.toList())

    @Test
    fun `the most recently watched goes first`() {
        val old = row("a", addedAt = 0L)
        val new = row("b", addedAt = 0L)
        val r = LibraryOrder.sortedRows(
            listOf(old, new),
            mapOf("a" to 100L, "b" to 900L),
        )
        assertEquals(listOf("b", "a"), r.map { it.identifier })
    }

    @Test
    fun `something just added beats something watched a while ago`() {
        val watched = row("watched", addedAt = 10L)
        val justAdded = row("justAdded", addedAt = 900L)
        val r = LibraryOrder.sortedRows(listOf(watched, justAdded), mapOf("watched" to 100L))
        assertEquals(listOf("justAdded", "watched"), r.map { it.identifier })
    }

    /**
     * The map doesn't tell finished episodes apart from ones left halfway: both arrive as a
     * timestamp. Finishing E4 last night has to leave the series first today, which is the case
     * that motivates this feature.
     */
    @Test
    fun `an item with no playback is sorted by its added date`() {
        val unwatched = row("unwatched", addedAt = 500L)
        val watched = row("watched", addedAt = 0L)
        val r = LibraryOrder.sortedRows(listOf(watched, unwatched), mapOf("watched" to 100L))
        assertEquals(listOf("unwatched", "watched"), r.map { it.identifier })
    }

    /** `max(...)` and not playback alone: re-adding something old brings it to the front. */
    @Test
    fun `with old progress but a recent add, the add wins`() {
        val row = row("a", addedAt = 900L)
        assertEquals(900L, LibraryOrder.recencyOf(row, mapOf("a" to 100L)))
    }

    @Test
    fun `recency is the playback when it is later than the add`() {
        val row = row("a", addedAt = 100L)
        assertEquals(900L, LibraryOrder.recencyOf(row, mapOf("a" to 900L)))
    }

    /** Tie: the incoming order (which comes `addedAt DESC` from SQL) is respected. */
    @Test
    fun `a tie keeps the incoming order`() {
        val first = row("first", addedAt = 100L)
        val second = row("second", addedAt = 100L)
        val r = LibraryOrder.sortedRows(listOf(first, second), emptyMap())
        assertEquals(listOf("first", "second"), r.map { it.identifier })
    }

    /**
     * A series saved from two sources is ONE card: watching it through either one moves the
     * whole group up. Same criterion as `LibraryWatched`.
     */
    @Test
    fun `a group's recency is its most recent member's`() {
        val naruto = group("tv:46260", row("web:series:a", 0L), row("torrent:series:a", 0L))
        val other = group("tv:1", row("web:series:other", 0L))
        val r = LibraryOrder.sortedGroups(
            listOf(other, naruto),
            mapOf("torrent:series:a" to 900L, "web:series:other" to 100L),
        )
        assertEquals(listOf("tv:46260", "tv:1"), r.map { it.key })
    }

    /**
     * Same point as `a tie keeps the incoming order`, but over [LibraryOrder.sortedGroups]:
     * `LibraryGrouping.kt` and `observeLibraryGroups` document `group`'s order as a tiebreaker,
     * which is only true if `sortedByDescending` is stable for groups too.
     */
    @Test
    fun `a tie between groups keeps the incoming order`() {
        val first = group("tv:1", row("web:series:a", addedAt = 100L))
        val second = group("tv:2", row("web:series:b", addedAt = 100L))
        val r = LibraryOrder.sortedGroups(listOf(first, second), emptyMap())
        assertEquals(listOf("tv:1", "tv:2"), r.map { it.key })
    }
}
