package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginHomeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenreTilesTest {
    private fun item(backdrop: String = "", poster: String = "") =
        GatewayResult(source = "p", title = "T", ref = "r", extra = mapOf("backdrop" to backdrop, "poster" to poster))

    private fun row(
        plugin: String, title: String, ref: String? = "ref-$title", genre: String? = null, items: List<GatewayResult> = listOf(item()),
    ) = PluginHomeRow(pluginId = plugin, pluginName = plugin.uppercase(), color = 0L, id = "id-$title", title = title, items = items, ref = ref, genre = genre)

    @Test fun `only browsable rows become tiles, the ones with a ref`() {
        val tiles = genreTilesOf(listOf(row("tv1", "Deportes"), row("tv1", "Sin ref", ref = null)), excludePluginId = null)
        assertEquals(listOf("Deportes"), tiles.map { it.title })
        assertEquals("ref-Deportes", tiles.single().ref)
        assertEquals("tv1", tiles.single().pluginId)
    }

    @Test fun `the Xuper plugin's rows are left out, its own catalog tiles already show them`() {
        val tiles = genreTilesOf(listOf(row("xuper", "Recién agregadas"), row("tv1", "Noticias")), excludePluginId = "xuper")
        assertEquals(listOf("Noticias"), tiles.map { it.title })
    }

    @Test fun `the preview is the first item's backdrop, else its poster, else none`() {
        val tiles = genreTilesOf(
            listOf(
                row("a", "A", items = listOf(item(backdrop = "b.jpg", poster = "p.jpg"))),
                row("a", "B", items = listOf(item(poster = "p.jpg"))),
                row("a", "C", items = listOf(item())),
            ),
            excludePluginId = null,
        )
        assertEquals(listOf("b.jpg", "p.jpg", null), tiles.map { it.previewUrl })
    }

    @Test fun `a declared genre beats a guess from the title, and a title with no hint has none`() {
        val tiles = genreTilesOf(
            listOf(row("a", "Noticias", genre = "infantil"), row("a", "Noticias del día"), row("a", "Recién agregadas")),
            excludePluginId = null,
        )
        assertEquals(listOf("infantil", "noticias", null), tiles.map { it.genre })
    }

    @Test fun `sections follow the vocabulary's order, tiles of different plugins together, the rest last`() {
        val tiles = genreTilesOf(
            listOf(
                row("a", "Series nuevas"), row("b", "Deportes en vivo"), row("a", "Recién agregadas"),
                row("b", "Películas de acción"), row("a", "Fútbol hoy"),
            ),
            excludePluginId = null,
        )
        val sections = genreSectionsOf(tiles)
        assertEquals(listOf("peliculas", "series", "deportes", null), sections.map { it.genre })
        assertEquals(listOf("Películas", "Series", "Deportes", OTHER_TILES_LABEL), sections.map { it.label })
        assertEquals(listOf("Deportes en vivo", "Fútbol hoy"), sections.first { it.genre == "deportes" }.tiles.map { it.title })
        assertEquals(listOf("Recién agregadas"), sections.last().tiles.map { it.title })
    }

    @Test fun `no tiles, no sections`() {
        assertEquals(emptyList<GenreSection>(), genreSectionsOf(emptyList()))
        assertNull(genreTilesOf(emptyList(), null).firstOrNull())
    }
}
