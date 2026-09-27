package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.catalog.CatalogArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogCardStyleTest {
    private val dark = 0xFF1A1A1AL
    private val white = 0xFFFFFFFFL

    @Test fun `the initial is the first letter, upper-cased`() {
        assertEquals("X", cardInitial("Xuper"))
        assertEquals("I", cardInitial("  internet Archive"))
    }

    @Test fun `the initial keeps the accent`() {
        assertEquals("Ñ", cardInitial("Ñandú"))
        assertEquals("Á", cardInitial("ágil"))
    }

    @Test fun `the initial can be a digit`() {
        assertEquals("1", cardInitial("123 Canal"))
    }

    @Test fun `leading punctuation and emoji are skipped`() {
        assertEquals("C", cardInitial("🎬 Cine"))
        assertEquals("C", cardInitial("  --- ¡Canal!"))
        assertEquals("C", cardInitial("😀😀c"))
    }

    @Test fun `a name without a letter or digit gives a question mark, never an empty string`() {
        assertEquals("?", cardInitial(""))
        assertEquals("?", cardInitial("   "))
        assertEquals("?", cardInitial("---"))
        assertEquals("?", cardInitial("🎬🎬"))
    }

    @Test fun `a letter outside the basic plane is kept whole`() {
        assertEquals("𝒜", cardInitial("𝒜bc"))
        // Deseret small letter long I (U+10428) upper-cases to U+10400, also a surrogate pair.
        assertEquals("𐐀", cardInitial("𐐨x"))
    }

    @Test fun `upper-casing never widens the initial to several letters`() {
        assertEquals("ß", cardInitial("ßeta"))
    }

    @Test fun `no art, or an art without a colour, gives the default tile colour`() {
        assertEquals(PluginColors.DEFAULT, tileColor(null))
        assertEquals(PluginColors.DEFAULT, tileColor(CatalogArt(colorHex = null, iconFile = null)))
    }

    @Test fun `a bad colour gives the default tile colour`() {
        assertEquals(PluginColors.DEFAULT, tileColor(CatalogArt("E0A030", null)))
        assertEquals(PluginColors.DEFAULT, tileColor(CatalogArt("#E0A03", null)))
        assertEquals(PluginColors.DEFAULT, tileColor(CatalogArt("red", null)))
    }

    @Test fun `a valid colour is used, fully opaque`() {
        assertEquals(0xFFE0A030L, tileColor(CatalogArt("#E0A030", null)))
    }

    @Test fun `dark text goes on a light tile`() {
        assertEquals(dark, onTileColor(0xFFE0A030L))
        assertEquals(dark, onTileColor(PluginColors.DEFAULT))
        assertEquals(dark, onTileColor(0xFFFFFFFFL))
    }

    @Test fun `white text goes on a dark tile`() {
        assertEquals(white, onTileColor(0xFF102030L))
        assertEquals(white, onTileColor(0xFF000000L))
    }

    @Test fun `the text colour follows the tile colour across the range`() {
        // Pure blue is dark enough for white; pure green is light enough for dark text.
        assertEquals(white, onTileColor(0xFF0000FFL))
        assertEquals(dark, onTileColor(0xFF00FF00L))
    }

    @Test fun `the action label is the Spanish verb`() {
        assertEquals("Instalar", cardActionLabel(CatalogAction.INSTALL))
        assertEquals("Configurar", cardActionLabel(CatalogAction.CONFIGURE))
        assertEquals("Activar", cardActionLabel(CatalogAction.ENABLE))
        assertEquals("Instalado", cardActionLabel(CatalogAction.INSTALLED))
    }

    @Test fun `every action has its own label`() {
        val labels = CatalogAction.values().map { cardActionLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }

    @Test fun `a description takes two lines on a card`() {
        assertEquals(2, cardDescriptionLines())
    }

    @Test fun `a card shows the first two tags in the catalog's order`() {
        assertEquals(listOf("Películas", "Series"), cardTags(listOf("Películas", "Series", "Anime", "Documentales")))
    }

    @Test fun `a card with fewer tags than the limit shows them all, or none`() {
        assertEquals(listOf("Series"), cardTags(listOf("Series")))
        assertEquals(emptyList<String>(), cardTags(emptyList()))
    }

    @Test fun `a blank tag is skipped and does not use up one of the two places`() {
        assertEquals(listOf("Series", "Anime"), cardTags(listOf("", "  ", "Series", "Anime", "Cine")))
    }

    @Test fun `a tag is shown trimmed`() {
        assertEquals(listOf("Series"), cardTags(listOf("  Series ")))
    }
}
