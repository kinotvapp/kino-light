package com.arkiv.player.ui.tv

import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.ui.home.homeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure logic behind the TV home's "Para ti" row: whether it's drawn or not
 * ([showForYouRow]) and what the hero shows on focusing a card ([recommendationFeatured]) or a
 * Magis card ([magisCardFeatured]). Compose for TV has no UI test infrastructure in this project
 * (same reason as `TvMagisLinkOfferTest`), so these functions -extracted outside the composable on
 * purpose- are the part that CAN be tested in a plain JVM.
 */
class TvHomeScreenForYouTest {

    private fun catalogItem(
        id: String = "P1",
        title: String = "Una película",
        description: String = "",
        genres: List<String> = emptyList(),
        score: Double? = null,
        backdrop: String? = null,
        poster: String? = null,
    ) = CatalogItem(
        id = id, title = title, poster = poster, durationS = 0,
        type = "movie", genres = genres, score = score, backdrop = backdrop, description = description,
    )

    private fun recommendation(
        id: String = "r1",
        tmdbId: Int = 603,
        tipo: String = "movie",
        titulo: String = "Matrix",
        posterUrl: String = "https://image.tmdb.org/poster.jpg",
        porque: String = "porque terminaste Dragon Ball",
        ref: String = "magis:algo",
        orden: Int = 0,
    ) = RecommendationEntity(
        id = id, tmdbId = tmdbId, tipo = tipo, titulo = titulo, posterUrl = posterUrl,
        porque = porque, ref = ref, orden = orden, generadoAt = 0L,
    )

    // --- showForYouRow: with nothing to show, neither the title nor a gap ---

    @Test fun `with no recommendations the row isn't shown`() {
        assertEquals(false, showForYouRow(emptyList()))
    }

    @Test fun `with at least one current recommendation the row is shown`() {
        assertEquals(true, showForYouRow(listOf(recommendation())))
    }

    // --- recommendationFeatured: what gets painted in the hero on focusing a card ---

    @Test fun `the reason goes in meta, same as Continuar viendo's chapter label`() {
        val f = recommendationFeatured(recommendation(porque = "porque terminaste Dragon Ball"))
        assertEquals("porque terminaste Dragon Ball", f.meta)
    }

    @Test fun `the title passes through as-is`() {
        val f = recommendationFeatured(recommendation(titulo = "El Padrino"))
        assertEquals("El Padrino", f.title)
    }

    @Test fun `type movie translates to Pelicula`() {
        val f = recommendationFeatured(recommendation(tipo = "movie"))
        assertEquals("Película", f.subtitle)
    }

    @Test fun `type tv translates to Serie`() {
        val f = recommendationFeatured(recommendation(tipo = "tv"))
        assertEquals("Serie", f.subtitle)
    }

    @Test fun `an empty posterUrl falls back to null, not a blank URL`() {
        val f = recommendationFeatured(recommendation(posterUrl = ""))
        assertNull(f.imageUrl)
    }

    @Test fun `a posterUrl with content is kept`() {
        val f = recommendationFeatured(recommendation(posterUrl = "https://image.tmdb.org/poster.jpg"))
        assertEquals("https://image.tmdb.org/poster.jpg", f.imageUrl)
    }

    // --- magisCardFeatured: what a discovery row's card puts in the hero on focus ---

    @Test fun `with a description, it's the subtitle and homeMeta goes in meta`() {
        val item = catalogItem(
            title = "Interestelar",
            description = "Un grupo de astronautas viaja a través de un agujero de gusano.",
            genres = listOf("Sci-Fi"),
            score = 8.6,
            backdrop = "https://i/interestelar-bd.jpg",
            poster = "https://i/interestelar-poster.jpg",
        )

        val f = magisCardFeatured(item)

        assertEquals("Un grupo de astronautas viaja a través de un agujero de gusano.", f.subtitle)
        assertEquals(item.homeMeta(), f.meta)
        assertEquals("https://i/interestelar-bd.jpg", f.imageUrl)
    }

    @Test fun `with a blank description, the subtitle is blank, not homeMeta repeated`() {
        val item = catalogItem(title = "Sin sinopsis", description = "", poster = "https://i/p.jpg")

        val f = magisCardFeatured(item)

        assertEquals("", f.subtitle)
        assertEquals(item.homeMeta(), f.meta)
        assertEquals("https://i/p.jpg", f.imageUrl)
    }
}
