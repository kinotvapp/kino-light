package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The TV search's "Buscar" button sends the search text straight to the sources, without going
 * through a TMDB card (whether typed or autocompleted with a card).
 * [freeTextCard] is the piece that translates that text into the card the rest of the wizard
 * already knows how to handle.
 */
class FreeTextCardTest {

    @Test fun `empty text searches nothing`() {
        assertNull(freeTextCard(""))
    }

    @Test fun `only whitespace searches nothing`() {
        assertNull(freeTextCard("   \t "))
    }

    @Test fun `the text becomes the title, with no extra whitespace`() {
        assertEquals("gladiador", freeTextCard("  gladiador  ")?.title)
    }

    /** With no ids the gateway searches by text across all four sources; with ids it would tiebreak
     *  by tmdb_id and tie us back to the exact title, which is exactly what "Ir" avoids. */
    @Test fun `carries no catalog ids`() {
        val card = freeTextCard("el padrino")
        assertNull(card?.tmdbId)
        assertNull(card?.anilistId)
    }

    /** "movie" filters nothing in the gateway (with season/episode at 0 all four sources return
     *  movies and series alike) and makes going back from sources return to the keyboard instead
     *  of landing on the season selector of a card that doesn't exist. */
    @Test fun `is a movie card, so back returns to the keyboard`() {
        assertEquals("movie", freeTextCard("el padrino")?.kind)
    }

    @Test fun `doesn't make up a poster, year or synopsis`() {
        val card = freeTextCard("el padrino")
        assertEquals("", card?.posterUrl)
        assertEquals("", card?.year)
        assertNull(card?.overview)
    }
}
