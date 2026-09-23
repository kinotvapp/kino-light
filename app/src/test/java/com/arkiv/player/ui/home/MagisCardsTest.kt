package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.magis.MagisRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisCardsTest {

    private val movie = CatalogItem(
        id = "P1", title = "Una película", poster = "https://i/p1.jpg", durationS = 5400,
        ref = MagisRef("P1", "movie", 0).encode(), type = "movie",
        genres = listOf("Action", "Short", "Drama"), score = 7.7, backdrop = "https://b/p1.jpg",
    )
    private val series = CatalogItem(
        id = "S1", title = "Una serie", poster = null, durationS = 0,
        ref = MagisRef("S1", "teleplay", 0).encode(), type = "teleplay", episodeCount = 12,
    )

    @Test
    fun `a movie maps to the result search's Magis path takes`() {
        val r = movie.toGatewayResult()

        assertEquals("magis", r.source)
        assertEquals("Una película", r.title)
        assertEquals(movie.ref, r.ref)
        assertEquals("movie", r.kind)
        assertEquals("P1", r.extra["content_id"])
        assertEquals("movie", r.extra["program_type"])
        assertEquals("https://i/p1.jpg", r.extra["poster"])
        assertEquals("https://b/p1.jpg", r.extra["backdrop"])
        assertFalse(movie.isMagisSeries)
    }

    @Test
    fun `a series carries its type and chapter count, and no empty images`() {
        val r = series.toGatewayResult()

        assertTrue(series.isMagisSeries)
        assertEquals("series", r.kind)
        assertEquals("teleplay", r.extra["program_type"])
        assertEquals("12", r.extra["episode_count"])
        assertNull(r.extra["poster"])
        assertNull(r.extra["backdrop"])
    }

    @Test
    fun `the hero line says type, genres and score`() {
        assertEquals("Película  ·  Acción, Drama  ·  ★ 7.7", movie.homeMeta())
        assertEquals("Serie", series.homeMeta())
    }

    @Test
    fun `homeMeta caps genres at 3 even with more mappable ones`() {
        val manyGenres = movie.copy(genres = listOf("Action", "Adventure", "Comedy", "Drama"))

        assertEquals("Película  ·  Acción, Aventura, Comedia  ·  ★ 7.7", manyGenres.homeMeta())
    }

    @Test
    fun `the featured title is the first one of the first row`() {
        val rows = listOf(
            MagisHomeRow("a", "A", shown = listOf(series, movie), all = listOf(series, movie)),
            MagisHomeRow("b", "B", shown = listOf(movie), all = listOf(movie)),
        )

        assertEquals("S1", magisFeatured(rows)?.id)
        assertNull(magisFeatured(null))
        assertNull(magisFeatured(emptyList()))
    }
}
