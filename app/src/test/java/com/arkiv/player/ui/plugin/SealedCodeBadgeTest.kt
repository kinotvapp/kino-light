package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.PluginCatalogParser
import com.arkiv.player.data.plugin.discovery.DiscoveredPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Código cerrado": the pill on a sealed-code plugin's card (catalog, community and installed; phone and TV share it). */
class SealedCodeBadgeTest {
    private val entry = CatalogEntry("x", "o/x", "X", "")

    @Test fun `a sealed catalog or community card says Código cerrado, a plain one says nothing new`() {
        assertEquals("Código cerrado", cardPill(CatalogRow(entry.copy(sealed = true), null)))
        assertEquals("Código cerrado", cardPill(CatalogRow(entry.copy(sealed = true), null, community = true)))
        assertNull(cardPill(CatalogRow(entry, null)))
        // What the person already used keeps its own pill.
        assertEquals("Lo que ya usabas", cardPill(CatalogRow(entry.copy(legacyDefault = true, sealed = true), null)))
    }

    @Test fun `the catalog reads sealed, and an entry without it is plain`() {
        val json = """{"schema":1,"plugins":[{"id":"a","repo":"o/a","name":"A","sealed":true},{"id":"b","repo":"o/b","name":"B"}]}"""
        val c = PluginCatalogParser.parse(json, emptySet())!!
        assertTrue(c.entries[0].sealed)
        assertFalse(c.entries[1].sealed)
    }

    @Test fun `a discovered sealed plugin keeps its pill in the community list`() {
        val rows = communityRows(listOf(DiscoveredPlugin("o", "s", "s", "S", "", 1, sealed = true)), emptyList(), emptyList(), "")
        assertEquals("Código cerrado", cardPill(rows.single()))
    }

    @Test fun `an installed card shows the pill only for sealed code`() {
        val m = PluginManifest("demo", "Demo", "1.0.0", 5, "plugin.kjs", "", "", "", listOf("example.com"), setOf("search"), null, null, entrySealed = true)
        val r = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, authorKey = "00")
        assertEquals("Código cerrado", installedCardModel(InstalledPlugin(m, r, iconFile = null), null).pill)
        val plain = m.copy(entry = "plugin.js", apiVersion = 4, entrySealed = false)
        assertNull(installedCardModel(InstalledPlugin(plain, r.copy(authorKey = null), iconFile = null), null).pill)
    }
}
