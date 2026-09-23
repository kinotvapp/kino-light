package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Already watched" is built by crossing per-item progress with the library's groups, so a series
 * saved from two sources is ONE card and not two.
 */
class LibraryWatchedTest {

    private fun row(id: String, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = "series",
        source = "web",
    )

    private fun group(key: String, vararg rows: LibraryRow) =
        LibraryGroup(key = key, primary = rows.first(), members = rows.toList())

    @Test
    fun `the group's count is the max across sources, not the sum`() {
        // Same series from two sources: 10 and 12 episodes watched. The 10 goes FIRST on purpose:
        // if the calculation were `first()` instead of `maxOf`, the test would give 10 and fail,
        // so it does tell one implementation apart from the other (with the 12 first, both give
        // the same result and the test proves nothing). They're alternate copies of the SAME
        // content, so adding them up (22) would lie just like it added up to 794 Naruto episodes.
        val g = group("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val watched = listOf(
            ItemWatched("web:series:a", episodes = 10, lastWatchedMs = 100L),
            ItemWatched("torrent:series:a", episodes = 12, lastWatchedMs = 50L),
        )
        val r = LibraryWatched.cross(listOf(g), watched)
        assertEquals(1, r.size)
        assertEquals(12, r.first().episodesWatched)
    }

    @Test
    fun `the group's last watched is the most recent across sources`() {
        val g = group("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val watched = listOf(
            ItemWatched("web:series:a", episodes = 12, lastWatchedMs = 100L),
            ItemWatched("torrent:series:a", episodes = 10, lastWatchedMs = 900L),
        )
        assertEquals(900L, LibraryWatched.cross(listOf(g), watched).first().lastWatchedMs)
    }

    @Test
    fun `a group with nothing watched is left out`() {
        val watched = group("tv:1", row("web:series:watched", 10))
        val unwatched = group("tv:2", row("web:series:unwatched", 10))
        val watchedList = listOf(ItemWatched("web:series:watched", 3, 10L))
        val r = LibraryWatched.cross(listOf(watched, unwatched), watchedList)
        assertEquals(listOf("tv:1"), r.map { it.group.key })
    }

    /** An item removed from the library leaves its progress in `playback`: it must not reappear. */
    @Test
    fun `a watched row with no group is ignored`() {
        val g = group("tv:1", row("web:series:a", 10))
        val watched = listOf(
            ItemWatched("web:series:a", 3, 10L),
            ItemWatched("web:series:deleted", 5, 999L),
        )
        val r = LibraryWatched.cross(listOf(g), watched)
        assertEquals(listOf("tv:1"), r.map { it.group.key })
    }

    @Test
    fun `sorts by last watched, most recent first`() {
        val old = group("tv:old", row("web:series:old", 10))
        val new = group("tv:new", row("web:series:new", 10))
        val watched = listOf(
            ItemWatched("web:series:old", 1, 100L),
            ItemWatched("web:series:new", 1, 900L),
        )
        val r = LibraryWatched.cross(listOf(old, new), watched)
        assertEquals(listOf("tv:new", "tv:old"), r.map { it.group.key })
    }

    @Test
    fun `with nothing watched returns empty`() {
        val g = group("tv:1", row("web:series:a", 10))
        assertEquals(emptyList<WatchedGroup>(), LibraryWatched.cross(listOf(g), emptyList()))
    }

    // --- watchedLabel --------------------------------------------------------------------

    @Test
    fun `a single episode goes in the singular`() {
        assertEquals("1 capítulo visto", LibraryWatched.watchedLabel(1))
    }

    @Test
    fun `more than one episode goes in the plural`() {
        assertEquals("3 capítulos vistos", LibraryWatched.watchedLabel(3))
    }
}
