package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private val movieGenres = listOf(TmdbGenre(28, "Acción"), TmdbGenre(35, "Comedia"))
    private val tvGenres = listOf(TmdbGenre(16, "Animación"))
    private val animeGenres = listOf("Action")

    @Test fun `the fixed rows go in order and before the genres`() {
        val ids = buildRowSpecs(movieGenres, tvGenres, emptyList()).map { it.id }
        assertEquals(
            listOf(
                "series_populares", "series_top",
                "anime", "anime_populares", "anime_top",
            ),
            ids.take(5),
        )
    }

    @Test fun `adds a row per movie genre and per series genre`() {
        val specs = buildRowSpecs(movieGenres, tvGenres, animeGenres)
        assertTrue(specs.any { it.id == "g_movie_28" && it.source == RowSource.Discover("movie", 28) })
        assertTrue(specs.any { it.id == "g_movie_35" })
        assertTrue(specs.any { it.id == "g_tv_16" && it.source == RowSource.Discover("tv", 16) })
        assertEquals(5 + 3 + animeGenres.size, specs.size)
    }

    @Test fun `with no genres only the fixed ones are left`() {
        assertEquals(5, buildRowSpecs(emptyList(), emptyList(), emptyList()).size)
    }

    @Test fun `the ids are unique`() {
        val specs = buildRowSpecs(movieGenres, tvGenres, animeGenres)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `the same genre id on movies and series doesn't collide`() {
        val shared = listOf(TmdbGenre(16, "Animación"))
        val specs = buildRowSpecs(shared, shared, emptyList())
        assertTrue(specs.any { it.id == "g_movie_16" })
        assertTrue(specs.any { it.id == "g_tv_16" })
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `no curated movie row exists (cartelera, populares, or tendencias)`() {
        val ids = buildRowSpecs(emptyList(), emptyList(), emptyList()).map { it.id }
        assertTrue("cartelera" !in ids)
        assertTrue("peliculas_populares" !in ids)
        assertTrue("tendencias" !in ids)
        val sources = buildRowSpecs(emptyList(), emptyList(), emptyList()).map { it.source }
        assertTrue(sources.none { it is RowSource.Curated && it.type == "movie" })
    }

    @Test fun `there's no upcoming row (releases with no sources)`() {
        val sources = buildRowSpecs(emptyList(), emptyList(), emptyList()).map { it.source }
        assertTrue(sources.none { it == RowSource.Curated("movie", TmdbCategory.UPCOMING) })
    }

    @Test fun `anime genres generate their own row without colliding`() {
        val specs = buildRowSpecs(listOf(TmdbGenre(16, "Animación")), listOf(TmdbGenre(16, "Animación")), listOf("Action"))
        assertTrue(specs.any { it.id == "g_anime_action" && it.source == RowSource.Anime("POPULARITY_DESC", "Action") })
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `shortcut route by card type`() {
        val movie = TitleCard("movie", 42, null, "X", "", "2025", null)
        val series = TitleCard("series", 1399, null, "Y", "", "2011", null)
        val anime = TitleCard("anime", null, 20L, "Z", "", "1999", null)
        assertEquals("search?kind=movie&tmdbId=42", searchShortcutRoute(movie))
        assertEquals("search?kind=series&tmdbId=1399", searchShortcutRoute(series))
        assertEquals("search?kind=anime&anilistId=20", searchShortcutRoute(anime))
    }
}
