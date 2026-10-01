package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.EntrySignature
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

/** "Firmado": the pill on an author-signed plugin's catalog or community card, the tag on its installed card's status line. */
class SignedBadgeTest {
    private val entry = CatalogEntry("x", "o/x", "X", "")

    @Test fun `a signed catalog or community card says Firmado, an unsigned one says nothing new`() {
        assertEquals("Firmado", cardPill(CatalogRow(entry.copy(signed = true), null)))
        assertEquals("Firmado", cardPill(CatalogRow(entry.copy(signed = true), null, community = true)))
        assertNull(cardPill(CatalogRow(entry, null)))
        // What the person already used keeps its own pill.
        assertEquals("Lo que ya usabas", cardPill(CatalogRow(entry.copy(legacyDefault = true, signed = true), null)))
    }

    @Test fun `the catalog reads signed, and an entry without it is unsigned`() {
        val json = """{"schema":1,"plugins":[{"id":"a","repo":"o/a","name":"A","signed":true},{"id":"b","repo":"o/b","name":"B"}]}"""
        val c = PluginCatalogParser.parse(json, emptySet())!!
        assertTrue(c.entries[0].signed)
        assertFalse(c.entries[1].signed)
    }

    @Test fun `a discovered signed plugin keeps its pill in the community list`() {
        val rows = communityRows(listOf(DiscoveredPlugin("o", "s", "s", "S", "", 1, signed = true)), emptyList(), emptyList(), "")
        assertEquals("Firmado", cardPill(rows.single()))
    }

    @Test fun `an installed card tags its status line only for a signed plugin`() {
        val m = PluginManifest("demo", "Demo", "1.0.0", 5, "plugin.js", "", "", "", listOf("example.com"), setOf("search"), null, null,
            signature = EntrySignature(ByteArray(32), ByteArray(64)))
        val r = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, authorKey = "00")
        assertEquals("Firmado", installedCardModel(InstalledPlugin(m, r, iconFile = null), null).signedTag)
        val plain = m.copy(apiVersion = 4, signature = null)
        assertNull(installedCardModel(InstalledPlugin(plain, r.copy(authorKey = null), iconFile = null), null).signedTag)
    }
}
