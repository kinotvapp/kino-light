package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The only thing about the history SQLite doesn't resolve on its own: normalizing the searched
 * text and deciding when two titles are the same work. The order, the cap, and the dedupe are
 * done by the query (`ORDER BY atMs DESC LIMIT`) and the PK with REPLACE -- see [SearchHistoryRepo].
 */
class SearchHistoryPolicyTest {

    @Test fun `the text is saved trimmed`() {
        assertEquals("dune", SearchHistoryPolicy.normalizeQuery("  dune  "))
    }

    @Test fun `empty or whitespace-only text isn't saved`() {
        assertNull(SearchHistoryPolicy.normalizeQuery(""))
        assertNull(SearchHistoryPolicy.normalizeQuery("   "))
    }

    @Test fun `the text keeps its capitalization`() {
        // The case-insensitive dedupe is done by the query with lower(); what's SAVED is what
        // the user typed, which is what shows up on the chip.
        assertEquals("One Piece", SearchHistoryPolicy.normalizeQuery("One Piece"))
    }

    @Test fun `a title's identity comes from its source's id`() {
        assertEquals("series:tmdb-1399", SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"))
        assertEquals("anime:anilist-21", SearchHistoryPolicy.titleId("anime", null, 21L, "One Piece"))
    }

    @Test fun `two series with the same name aren't the same work`() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("series", 1399, null, "The Office"),
            SearchHistoryPolicy.titleId("series", 2316, null, "The Office"),
        )
    }

    @Test fun `a movie and an anime with the same number don't collide`() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("movie", 21, null, "Peli"),
            SearchHistoryPolicy.titleId("anime", null, 21L, "Anime"),
        )
    }

    @Test fun `with no id at all identity falls back to the lowercase name`() {
        assertEquals("movie:n-dune", SearchHistoryPolicy.titleId("movie", null, null, "Dune"))
        assertEquals(
            SearchHistoryPolicy.titleId("movie", null, null, "Dune"),
            SearchHistoryPolicy.titleId("movie", null, null, "  DUNE  "),
        )
    }

    @Test fun `the RecentTitle overload gives the same id`() {
        val t = RecentTitle("series", 1399, null, "Game of Thrones", "", "2011")
        assertEquals(SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"), SearchHistoryPolicy.titleId(t))
    }
}
