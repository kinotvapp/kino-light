package com.arkiv.player.ui.home

import com.arkiv.player.ui.search.TitleCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupRowsTest {
    private fun movie(id: Int, title: String = "M$id") = TitleCard("movie", id, null, title, "", "2025", null)
    private fun series(id: Int) = TitleCard("series", id, null, "S$id", "", "2020", null)
    private fun anime(id: Long) = TitleCard("anime", null, id, "A$id", "", "2019", null)

    @Test fun `a movie already seen in another row doesn't repeat`() {
        val seen = setOf(cardKey(movie(1)))
        val out = dedupAgainst(seen, listOf(movie(1), movie(2)))
        assertEquals(listOf("M2"), out.map { it.title })
    }

    @Test fun `also removes repeats within the same row`() {
        val out = dedupAgainst(emptySet(), listOf(movie(7), movie(7), movie(8)))
        assertEquals(listOf(7, 8), out.map { it.tmdbId })
    }

    @Test fun `keeps the original order of the survivors`() {
        val out = dedupAgainst(setOf(cardKey(movie(2))), listOf(movie(1), movie(2), movie(3)))
        assertEquals(listOf(1, 3), out.map { it.tmdbId })
    }

    @Test fun `a movie and a series with the same TMDB id don't clash`() {
        // TMDB ids are different spaces: movie 100 isn't series 100.
        val out = dedupAgainst(setOf(cardKey(movie(100))), listOf(series(100)))
        assertEquals(1, out.size)
    }

    @Test fun `anime is identified by its AniList id`() {
        val out = dedupAgainst(setOf(cardKey(anime(20L))), listOf(anime(20L), anime(21L)))
        assertEquals(listOf(21L), out.map { it.anilistId })
    }

    @Test fun `with nothing seen before it returns everything`() {
        val cards = listOf(movie(1), series(2), anime(3L))
        assertEquals(cards, dedupAgainst(emptySet(), cards))
    }

    @Test fun `doesn't mutate the set it receives`() {
        val seen = setOf(cardKey(movie(1)))
        dedupAgainst(seen, listOf(movie(2), movie(3)))
        assertEquals(1, seen.size)
        assertTrue(seen.contains(cardKey(movie(1))))
    }
}
