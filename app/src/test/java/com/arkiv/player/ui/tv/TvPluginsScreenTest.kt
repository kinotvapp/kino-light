package com.arkiv.player.ui.tv

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CatalogUiState
import com.arkiv.player.ui.plugin.catalogRefreshLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPluginsScreenTest {
    @Test fun `each catalog action reads as a sentence with the plugin name`() {
        assertEquals("Instalar Internet Archive", catalogRowLabel(CatalogAction.INSTALL, "Internet Archive"))
        assertEquals("Configurar Mi servidor", catalogRowLabel(CatalogAction.CONFIGURE, "Mi servidor"))
        assertEquals("Activar Xuper", catalogRowLabel(CatalogAction.ENABLE, "Xuper"))
    }

    @Test fun `an installed plugin reads as a status, not as an action`() {
        assertEquals("Xuper: instalado", catalogRowLabel(CatalogAction.INSTALLED, "Xuper"))
    }

    // Found on the KALLEY TV: Up inside the `usuario/repositorio` field only moved the caret, so the
    // recommended rows above it could not be reached with the D-pad once the person was below the field.
    @Test fun `Up and Down both leave a text field, in their own direction`() {
        assertEquals(FocusDirection.Up, fieldExitDirection(Key.DirectionUp))
        assertEquals(FocusDirection.Down, fieldExitDirection(Key.DirectionDown))
    }

    @Test fun `Left, Right and typing keys stay with the text field`() {
        assertNull(fieldExitDirection(Key.DirectionLeft))
        assertNull(fieldExitDirection(Key.DirectionRight))
        assertNull(fieldExitDirection(Key.A))
    }

    // The header row (tabs and "Agregar") sits above the grid, and its button at the far right is right above
    // the last column: a Right key on a card of that column would find the button, which is up and to the
    // side. Nothing is meant to be to the right of a card of the last column, nor of the very last card.
    @Test fun `a card of the last column has nothing to its right`() {
        assertFalse(cardHasNothingToTheRight(index = 0, lastIndex = 8, columns = 3))
        assertFalse(cardHasNothingToTheRight(index = 1, lastIndex = 8, columns = 3))
        assertTrue(cardHasNothingToTheRight(index = 2, lastIndex = 8, columns = 3))
        assertFalse(cardHasNothingToTheRight(index = 3, lastIndex = 8, columns = 3))
        assertTrue(cardHasNothingToTheRight(index = 5, lastIndex = 8, columns = 3))
    }

    @Test fun `the very last card has nothing to its right, whichever column it is in`() {
        assertTrue(cardHasNothingToTheRight(index = 7, lastIndex = 7, columns = 3))
        assertTrue(cardHasNothingToTheRight(index = 3, lastIndex = 3, columns = 3))
        assertTrue(cardHasNothingToTheRight(index = 0, lastIndex = 0, columns = 3))
    }

    // Instalados lays four cards per line on the TV: Right stops at the fourth card of each line (under
    // "Agregar") and at the last card, wherever a partly filled line leaves it.
    @Test fun `Instalados is four columns and Right stops at the fourth card of each line`() {
        assertEquals(4, TV_INSTALLED_COLUMNS)
        val stops = (0..9).filter { cardHasNothingToTheRight(it, lastIndex = 9, columns = TV_INSTALLED_COLUMNS) }
        assertEquals(listOf(3, 7, 9), stops)
        assertEquals(listOf(3, 4), (0..4).filter { cardHasNothingToTheRight(it, lastIndex = 4, columns = TV_INSTALLED_COLUMNS) })
    }

    // Recomendados and "De la comunidad" draw the Instalados card now, so they keep its four columns (and the
    // same Right stops: the fourth card of a line, under "Agregar", and the last card).
    @Test fun `Recomendados lays the Instalados card four per line`() {
        assertEquals(TV_INSTALLED_COLUMNS, TV_RECOMMENDED_COLUMNS)
        assertEquals(listOf(3, 5), (0..5).filter { cardHasNothingToTheRight(it, lastIndex = 5, columns = TV_RECOMMENDED_COLUMNS) })
    }

    @Test fun `a message or a live notice reserves room on the four cards of its own line only`() {
        assertEquals(
            listOf(false, false, false, false, true, true, true, true, false),
            com.arkiv.player.ui.plugin.installedGridLinesWithMessage(9, messageIndex = 6, columns = TV_INSTALLED_COLUMNS),
        )
        assertEquals(
            listOf(true, true, true, true, false, false),
            com.arkiv.player.ui.plugin.installedGridLinesReserving(listOf(false, false, true, false, false, false), TV_INSTALLED_COLUMNS),
        )
    }

    // The dialog is a window of its own: when it goes away the person must find the button that opened it
    // focused again, and only then (a consent that never came from the dialog leaves focus where it is).
    @Test fun `focus goes back to the Agregar button only when the dialog has just gone away`() {
        assertTrue(focusReturnsToAdd(wasShown = true, shown = false))
        assertFalse(focusReturnsToAdd(wasShown = false, shown = false))
        assertFalse(focusReturnsToAdd(wasShown = false, shown = true))
        assertFalse(focusReturnsToAdd(wasShown = true, shown = true))
    }

    // Uninstalling from the actions dialog removes the very card the dialog is about to send focus back
    // to. Found on the KALLEY TV: focus fell through to Ajustes' first chip instead of staying on Instalados.
    @Test fun `focus returns to the same card when it is still installed`() {
        assertEquals("b", installedFocusReturnTarget(returnId = "b", remainingIds = listOf("a", "b", "c")))
    }

    @Test fun `an uninstalled card's focus falls to the first remaining card`() {
        assertEquals("a", installedFocusReturnTarget(returnId = "b", remainingIds = listOf("a", "c")))
    }

    @Test fun `uninstalling the last plugin leaves no card to focus`() {
        assertNull(installedFocusReturnTarget(returnId = "b", remainingIds = emptyList()))
    }

    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)

    private fun row(installed: InstalledRecord?, missingSettings: List<String> = emptyList()) = CatalogRow(
        CatalogEntry(id = "demo", repo = "o/r", name = "Demo", description = ""),
        installed?.let { InstalledPlugin(manifest, it, iconFile = null, missingSettings = missingSettings) },
    )

    // A card only writes a status when the button says something else than "Instalado": that is when the
    // person needs to know why the button reads Activar / Configurar / Instalar again.
    @Test fun `a plugin that is not installed has no status line`() {
        assertNull(cardStatus(row(null)))
    }

    @Test fun `a working installed plugin has no status line, its button already says Instalado`() {
        assertNull(cardStatus(row(record)))
    }

    @Test fun `an update waiting for approval alone has no status line either`() {
        assertNull(cardStatus(row(record.copy(pendingVersion = "1.1.0"))))
    }

    @Test fun `a disabled, unresponsive, damaged or unconfigured plugin says why`() {
        assertEquals(PluginStatus.DISABLED, cardStatus(row(record.copy(enabled = false))))
        assertEquals(PluginStatus.UNRESPONSIVE, cardStatus(row(record.copy(unresponsive = true))))
        assertEquals(PluginStatus.DAMAGED, cardStatus(row(record.copy(damaged = true))))
        assertEquals(PluginStatus.NEEDS_SETUP, cardStatus(row(record, missingSettings = listOf("x"))))
    }

    private fun rowsWithStatus(vararg hasStatus: Boolean) = hasStatus.map { row(if (it) record.copy(enabled = false) else null) }

    // The grid gives every line the height of its tallest card: a card without a status must leave room
    // for it, or the cards of a line end at different heights.
    @Test fun `every card of a line with a status reserves its line, the other lines do not`() {
        val rows = rowsWithStatus(false, true, false, false, false, false, false)
        assertEquals(listOf(true, true, true, false, false, false, false), gridLinesWithStatus(rows, columns = 3))
    }

    @Test fun `a status in the last, partial line reaches only that line`() {
        val rows = rowsWithStatus(false, false, false, false, true)
        assertEquals(listOf(false, false, false, true, true), gridLinesWithStatus(rows, columns = 3))
    }

    @Test fun `no status anywhere reserves nothing, and no rows gives an empty answer`() {
        assertEquals(listOf(false, false, false, false), gridLinesWithStatus(rowsWithStatus(false, false, false, false), columns = 3))
        assertEquals(emptyList<Boolean>(), gridLinesWithStatus(emptyList(), columns = 3))
    }

    // The focused card is scaled up, so it needs room around it: the scroll keeps a margin between it and
    // the edge of the window, on both sides.
    @Test fun `an item that fits with its margin does not scroll the grid`() {
        assertEquals(0f, scrollDistanceWithMargin(offset = 40f, size = 100f, containerSize = 400f, margin = 20f), 0f)
    }

    @Test fun `an item too close to the bottom scrolls just enough to leave the margin`() {
        // bottom edge 390 + margin 20 = 410, 10 past the 400 container
        assertEquals(10f, scrollDistanceWithMargin(offset = 290f, size = 100f, containerSize = 400f, margin = 20f), 0.001f)
    }

    @Test fun `an item too close to the top scrolls back just enough to leave the margin`() {
        // top edge 5 - margin 20 = -15
        assertEquals(-15f, scrollDistanceWithMargin(offset = 5f, size = 100f, containerSize = 400f, margin = 20f), 0.001f)
    }

    @Test fun `an item below the window scrolls until it and its margin are in`() {
        // bottom edge 550 + 20 = 570, 170 past the container
        assertEquals(170f, scrollDistanceWithMargin(offset = 450f, size = 100f, containerSize = 400f, margin = 20f), 0.001f)
    }

    @Test fun `without a margin it is the plain minimal scroll`() {
        assertEquals(0f, scrollDistanceWithMargin(offset = 300f, size = 100f, containerSize = 400f, margin = 0f), 0f)
        assertEquals(1f, scrollDistanceWithMargin(offset = 301f, size = 100f, containerSize = 400f, margin = 0f), 0.001f)
    }

    private fun seedState(origin: CatalogOrigin?, refreshing: Boolean) = CatalogUiState(loading = false, origin = origin, refreshing = refreshing)

    // The initial focus scrolls the grid against the seed block; a notice line that appeared after that
    // pushed the cards down (found on the KALLEY TV: the focused first card lost its last line). So the
    // line is laid out from the start, with the text that will replace it.
    @Test fun `while the refresh runs the notice line already has the text the failed refresh will show`() {
        val running = refreshNoticeSlot(seedState(CatalogOrigin.SEED, refreshing = true))
        val failed = refreshNoticeSlot(seedState(CatalogOrigin.SEED, refreshing = false))
        assertNotNull(running)
        assertEquals(failed, running)
    }

    @Test fun `the slot is the notice itself once the refresh has failed`() {
        assertEquals(
            catalogRefreshLine(seedState(CatalogOrigin.SEED, refreshing = false))!!.notice,
            refreshNoticeSlot(seedState(CatalogOrigin.SEED, refreshing = false)),
        )
    }

    @Test fun `there is no slot when the seed block is not shown`() {
        assertNull(refreshNoticeSlot(seedState(CatalogOrigin.FRESH, refreshing = false)))
        assertNull(refreshNoticeSlot(seedState(CatalogOrigin.CACHE, refreshing = true)))
        assertNull(refreshNoticeSlot(seedState(null, refreshing = false)))
    }

    // Measured on the KALLEY TV (1280x720 px, 1.33 px per dp), in pixels of the grid's own coordinates: the
    // viewport is 597 px tall, the first card 322 px, the focus margin 12 dp = 16 px. The card starts at 258
    // with the seed block and no notice line, 37 px lower with the notice line reserved, and 169 px higher
    // with no seed block at all.
    @Test fun `the first card is scrolled fully into view by the initial focus in each state of the window`() {
        val margin = 16f
        assertEquals(36f, scrollDistanceWithMargin(offset = 295f, size = 322f, containerSize = 597f, margin = margin), 0.001f)
        assertEquals(0f, scrollDistanceWithMargin(offset = 258f, size = 322f, containerSize = 597f, margin = margin), 0.001f)
        assertEquals(0f, scrollDistanceWithMargin(offset = 169f, size = 322f, containerSize = 597f, margin = margin), 0.001f)
    }
}
