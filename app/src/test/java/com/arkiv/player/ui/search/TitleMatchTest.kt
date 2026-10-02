package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    private fun q(title: String = "The Dark Knight", type: String = "movie", year: Int = 2008, tmdb: Int = 155, vararg alts: String) =
        GatewaySearchQuery(q = title, type = type, year = year, tmdbId = tmdb, altTitles = alts.toList(), originalTitle = "")

    private fun r(title: String, kind: String = "movie", year: String = "", extra: Map<String, String> = emptyMap()) =
        GatewayResult(source = "plugin:x", title = title, ref = "r", kind = kind, year = year, extra = extra)

    @Test fun `normalization drops accents, case, punctuation and a leading article`() {
        assertEquals("dark knight", TitleMatch.normalize("The Dark Knight!"))
        assertEquals("senor",TitleMatch.normalize("El Señor"))
        assertEquals("amelie", TitleMatch.normalize("Amélie"))
        assertEquals("spider man", TitleMatch.normalize("Spider-Man"))
    }

    @Test fun `the same title with decoration matches`() {
        assertTrue(TitleMatch.matches(r("Dark Knight (2008) 1080p"), q()))
        assertTrue(TitleMatch.matches(r("THE DARK KNIGHT"), q()))
        assertTrue(TitleMatch.matches(r("El caballero oscuro"), q(alts = arrayOf("El caballero de la noche", "El caballero oscuro"))))
    }

    @Test fun `an unrelated title does not`() {
        assertFalse(TitleMatch.matches(r("Dead Poets Society"), q("Deadpool", year = 2016)))
        assertFalse(TitleMatch.matches(r("Batman Begins"), q()))
    }

    @Test fun `a movie's year may be off by one, not two`() {
        assertTrue(TitleMatch.matches(r("The Dark Knight", year = "2009"), q()))
        assertTrue(TitleMatch.matches(r("The Dark Knight", year = "2007"), q()))
        assertFalse(TitleMatch.matches(r("The Dark Knight", year = "2012"), q()))
    }

    @Test fun `the kind must agree with the card`() {
        assertFalse(TitleMatch.matches(r("The Dark Knight", kind = "series"), q()))
        assertFalse(TitleMatch.matches(r("Dark", kind = "movie"), q("Dark", type = "tv", year = 2017)))
        assertTrue(TitleMatch.matches(r("Dark", kind = "series", year = "2019"), q("Dark", type = "tv", year = 2017)))
    }

    @Test fun `a published TMDB id decides on its own`() {
        assertTrue(TitleMatch.matches(r("Other name", extra = mapOf("tmdbId" to "155")), q()))
        assertFalse(TitleMatch.matches(r("The Dark Knight", extra = mapOf("tmdbId" to "999")), q()))
    }

    @Test fun `a typed search and live channels are never filtered`() {
        assertTrue(TitleMatch.matches(r("Whatever"), q(tmdb = 0)))
        assertTrue(TitleMatch.matches(r("Canal Uno", kind = "live"), q()))
    }
}
