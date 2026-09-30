package com.arkiv.player.ui.search

import com.arkiv.player.data.SourceSearchTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class OtherSourcesRouteTest {

    @Test
    fun `a movie with a TMDB id opens the search on TMDB's card, with the title as fallback`() {
        assertEquals(
            "search?kind=movie&tmdbId=533535&text=Deadpool%20%26%20Wolverine",
            otherSourcesRoute(SourceSearchTitle(isMovie = true, tmdbId = 533535, title = "Deadpool & Wolverine")),
        )
    }

    @Test
    fun `a series goes as a series`() {
        assertEquals(
            "search?kind=series&tmdbId=1399&text=Juego%20de%20tronos",
            otherSourcesRoute(SourceSearchTitle(isMovie = false, tmdbId = 1399, title = "Juego de tronos")),
        )
    }

    @Test
    fun `with no TMDB id the sources are searched by the title`() {
        assertEquals(
            "search?text=El%20Chavo%3F",
            otherSourcesRoute(SourceSearchTitle(isMovie = true, tmdbId = null, title = "El Chavo?")),
        )
    }
}
