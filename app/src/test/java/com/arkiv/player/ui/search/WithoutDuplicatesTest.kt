package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The title grid merges TMDB and AniList, so anything that's anime and also on TMDB used to show
 * up twice ("evangelion" is the textbook case). Since the grid is now the search's autocomplete, a
 * duplicate adds nothing: it's the same word twice.
 */
class WithoutDuplicatesTest {

    private fun tmdb(title: String, year: String = "1995") =
        TitleCard("series", 1, null, title, "", year, null)

    private fun anime(title: String, year: String = "1995") =
        TitleCard("anime", null, 30L, title, "", year, null)

    @Test fun `empty list`() {
        assertEquals(emptyList<TitleCard>(), withoutDuplicates(emptyList()))
    }

    @Test fun `the same title in TMDB and AniList stays only once`() {
        val out = withoutDuplicates(listOf(tmdb("Neon Genesis Evangelion"), anime("Neon Genesis Evangelion")))
        assertEquals(listOf("Neon Genesis Evangelion"), out.map { it.title })
    }

    /** The first one wins: the list arrives as `tmdb + anime`, and the order already shown doesn't change. */
    @Test fun `the first one in the list wins`() {
        assertEquals("series", withoutDuplicates(listOf(tmdb("Evangelion"), anime("Evangelion"))).single().kind)
        assertEquals("anime", withoutDuplicates(listOf(anime("Evangelion"), tmdb("Evangelion"))).single().kind)
    }

    @Test fun `capitals, accents and punctuation make no difference`() {
        val out = withoutDuplicates(listOf(tmdb("El Padrino"), anime("el padrino"), tmdb("¡El  PADRINO!")))
        assertEquals(1, out.size)
    }

    /** Two movies with the same name and a different year are two different movies (remakes). */
    @Test fun `same title with different years does NOT merge`() {
        val out = withoutDuplicates(listOf(tmdb("It", "1990"), tmdb("It", "2017")))
        assertEquals(listOf("1990", "2017"), out.map { it.year })
    }

    @Test fun `different titles are left alone and keep their order`() {
        val input = listOf(tmdb("Evangelion 1.0"), tmdb("Evangelion 2.0"), anime("Evangelion 3.0"))
        assertEquals(input.map { it.title }, withoutDuplicates(input).map { it.title })
    }

    /** A blank title has nothing to compare against: it's let through instead of collapsing them all into one. */
    @Test fun `blank titles don't collapse into each other`() {
        val out = withoutDuplicates(listOf(tmdb(""), anime("")))
        assertEquals(2, out.size)
    }
}
