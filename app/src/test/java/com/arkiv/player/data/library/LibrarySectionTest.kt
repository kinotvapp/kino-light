package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySectionTest {

    private fun row(id: String, category: String?, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = category,
        source = "web",
    )

    private val series = LibraryGroup("tv:1", row("s", "series", 24), listOf(row("s", "series", 24)))
    private val movie = LibraryGroup("item:p", row("p", "movie", 1), listOf(row("p", "movie", 1)))
    private val all = listOf(series, movie)

    @Test
    fun `all returns the groups untouched`() {
        assertEquals(all, LibraryFilter.groups(LibrarySection.ALL_SAVED, all))
    }

    @Test
    fun `series leaves out the movies`() {
        assertEquals(listOf(series), LibraryFilter.groups(LibrarySection.SERIES, all))
    }

    @Test
    fun `movies leaves out the series`() {
        assertEquals(listOf(movie), LibraryFilter.groups(LibrarySection.MOVIES, all))
    }

    /** A single-episode item with no override is a movie by automatic detection. */
    @Test
    fun `a one-episode item with no override counts as a movie`() {
        val standalone = LibraryGroup("item:x", row("x", null, 1), listOf(row("x", null, 1)))
        assertEquals(listOf(standalone), LibraryFilter.groups(LibrarySection.MOVIES, listOf(standalone)))
    }

    /** These two do NOT come from the saved library: asking for them here is a caller error. */
    @Test
    fun `watched and downloads do not come out of this list`() {
        assertNull(LibraryFilter.groups(LibrarySection.WATCHED, all))
        assertNull(LibraryFilter.groups(LibrarySection.DOWNLOADS, all))
    }

    @Test
    fun `the menu labels go in the screen's order`() {
        assertEquals(
            listOf("Todo", "Series", "Películas", "Ya visto", "Descargas"),
            LibrarySection.entries.map { it.label },
        )
    }
}
