package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogCodecTest {

    private val movie = CatalogItem(
        id = "c1", title = "Haz Que Regrese", poster = "p.jpg", durationS = 5400, adult = false,
        ref = "ref-1", type = "movie", genres = listOf("Terror", "Misterio"), score = 7.5,
        backdrop = "b.jpg", description = "una peli", episodeCount = 0,
    )
    private val series = CatalogItem(
        id = "c2", title = "Serie", poster = null, durationS = 0, adult = true,
        ref = "ref-2", type = "teleplay", genres = emptyList(), score = null,
        backdrop = null, description = "", episodeCount = 12,
    )

    @Test
    fun `round-trips rows with all fields, nullables and defaults preserved`() {
        val rows = listOf(
            MagisHomeRow(id = "magis_new_peliculas", title = "Estrenos", shown = listOf(movie), all = listOf(movie, series)),
            MagisHomeRow(id = "magis_g_series_drama", title = "Drama", shown = listOf(series), all = listOf(series)),
        )

        val decoded = HomeCatalogCodec.decode(HomeCatalogCodec.encode(rows))

        assertEquals(rows, decoded)
    }

    @Test
    fun `a null poster, score and backdrop survive as null`() {
        val decoded = HomeCatalogCodec.decode(
            HomeCatalogCodec.encode(listOf(MagisHomeRow("r", "R", listOf(series), listOf(series)))),
        )
        val item = decoded.single().shown.single()

        assertEquals(null, item.poster)
        assertEquals(null, item.score)
        assertEquals(null, item.backdrop)
        assertEquals("teleplay", item.type)
        assertEquals(12, item.episodeCount)
    }

    @Test
    fun `malformed json decodes to an empty list instead of throwing`() {
        assertTrue(HomeCatalogCodec.decode("not json at all").isEmpty())
        assertTrue(HomeCatalogCodec.decode("").isEmpty())
        assertTrue(HomeCatalogCodec.decode("{}").isEmpty())
    }
}
