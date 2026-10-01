package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.ui.tv.gridLinesWithStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone lays the cards out two per line: a card whose neighbour in the line writes a status keeps room
 * for one, so both end at the same height (the helper is the one the TV grid uses, here with the phone's
 * column count).
 */
class PluginCardGridTest {
    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)

    private fun rowsWithStatus(vararg hasStatus: Boolean) = hasStatus.map { withStatus ->
        CatalogRow(
            CatalogEntry(id = "demo", repo = "o/r", name = "Demo", description = ""),
            if (withStatus) InstalledPlugin(manifest, record.copy(enabled = false), iconFile = null) else null,
        )
    }

    @Test fun `both cards of a two-column line reserve the status when either writes one`() {
        val rows = rowsWithStatus(false, true, false, false, false)
        assertEquals(listOf(true, true, false, false, false), gridLinesWithStatus(rows, columns = 2))
    }

    @Test fun `a status in the last card of an odd list reaches only its own line`() {
        val rows = rowsWithStatus(false, false, false, false, true)
        assertEquals(listOf(false, false, false, false, true), gridLinesWithStatus(rows, columns = 2))
    }

    @Test fun `no status anywhere reserves nothing`() {
        assertEquals(listOf(false, false, false), gridLinesWithStatus(rowsWithStatus(false, false, false), columns = 2))
    }
}

/** The "Firmado" slot a TV catalog card has on its status line. */
class CatalogSignedTagTest {
    private val row = CatalogRow(CatalogEntry(id = "demo", repo = "o/r", name = "Demo", description = ""), null)

    @Test fun `the tag follows the status line's label`() {
        assertEquals("Instalado · Firmado", withSignedTag("Instalado", SIGNED_TAG))
        assertEquals("Instalar", withSignedTag("Instalar", null))
    }

    @Test fun `the word is Firmado`() {
        assertEquals("Firmado", SIGNED_TAG)
    }

    @Test fun `only a signed entry carries the tag`() {
        assertEquals(null, cardSignedTag(row))
        assertEquals(SIGNED_TAG, cardSignedTag(row.copy(entry = row.entry.copy(signed = true))))
    }
}
