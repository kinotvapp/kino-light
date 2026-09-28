package com.arkiv.player.ui.tv

import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CommunityUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvGridFocusTest {
    // The fresh-device picker seen on the KALLEY TV: two recommended cards, "Actualizar", one community card.
    private val picker = listOf(
        GridBlock.Cards(listOf("card-archive", "card-own"), headerKey = "recommended-header"),
        GridBlock.Action("community-header"),
        GridBlock.Cards(listOf("community-xuper")),
    )

    @Test fun `cards are cut into lines of the column count, an action is a line of its own`() {
        val lines = gridFocusLines(
            listOf(GridBlock.Cards(listOf("a", "b", "c", "d", "e")), GridBlock.Action("act"), GridBlock.Cards(listOf("x"))),
            columns = 4,
        )
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("e"), listOf("act"), listOf("x")), lines)
    }

    @Test fun `empty card blocks add no line`() {
        assertEquals(listOf(listOf("act")), gridFocusLines(listOf(GridBlock.Cards(emptyList()), GridBlock.Action("act")), 4))
    }

    // Found on the KALLEY TV: Down from the LEFT card of the last recommended line jumped to "Listo".
    @Test fun `Down from every card of the last recommended line reaches Actualizar`() {
        val lines = gridFocusLines(picker, 4)
        assertEquals("community-header", gridFocusNeighbour(lines, "card-archive", down = true))
        assertEquals("community-header", gridFocusNeighbour(lines, "card-own", down = true))
    }

    @Test fun `Down from Actualizar reaches the first community card, and Down from the last line leaves the grid`() {
        val lines = gridFocusLines(picker, 4)
        assertEquals("community-xuper", gridFocusNeighbour(lines, "community-header", down = true))
        assertNull(gridFocusNeighbour(lines, "community-xuper", down = true))
    }

    @Test fun `Up reverses Down, and Up from the first line leaves the grid`() {
        val lines = gridFocusLines(picker, 4)
        assertEquals("community-header", gridFocusNeighbour(lines, "community-xuper", down = false))
        assertEquals("card-archive", gridFocusNeighbour(lines, "community-header", down = false))
        assertNull(gridFocusNeighbour(lines, "card-archive", down = false))
    }

    @Test fun `a move keeps the column, clamped to the last card of a shorter line`() {
        val lines = gridFocusLines(listOf(GridBlock.Cards(listOf("a", "b", "c", "d", "e", "f"))), 4)
        assertEquals("f", gridFocusNeighbour(lines, "c", down = true))
        assertEquals("f", gridFocusNeighbour(lines, "d", down = true))
        assertEquals("b", gridFocusNeighbour(lines, "f", down = false))
    }

    @Test fun `Tus plugins comes first and leads down to the recommended cards`() {
        val lines = gridFocusLines(
            listOf(GridBlock.Cards(listOf("installed-a"), headerKey = "installed-header")) + picker,
            columns = 4,
        )
        assertEquals("card-archive", gridFocusNeighbour(lines, "installed-a", down = true))
        assertEquals("installed-a", gridFocusNeighbour(lines, "card-own", down = false))
    }

    @Test fun `an unknown key has no neighbour`() {
        assertNull(gridFocusNeighbour(gridFocusLines(picker, 4), "listo", down = true))
    }

    @Test fun `only the first line of a card block names its header`() {
        val blocks = listOf(GridBlock.Cards(listOf("a", "b", "c", "d", "e"), headerKey = "h"), GridBlock.Action("act"))
        assertEquals("h", gridStopHeader(blocks, 4, "a"))
        assertEquals("h", gridStopHeader(blocks, 4, "d"))
        assertNull(gridStopHeader(blocks, 4, "e"))
        assertNull(gridStopHeader(blocks, 4, "act"))
        assertNull(gridStopHeader(blocks, 4, "unknown"))
    }

    @Test fun `the picker fits four cards on a 1280 dp screen`() {
        // 1280 dp minus the screen's 64 dp side paddings.
        assertEquals(4, tvPickerColumns(1152f))
    }

    @Test fun `the picker column count follows the width, within bounds`() {
        assertEquals(3, tvPickerColumns(900f))
        assertEquals(2, tvPickerColumns(300f))
        assertEquals(6, tvPickerColumns(4000f))
    }

    @Test fun `the picker's grid walks Tus plugins, Recomendados, Actualizar and the community cards in that order`() {
        val xuper = CatalogRow(CatalogEntry("xuper", "kinotvapp/kino-plugin-xuper", "Xuper", ""), null, community = true)
        val blocks = pickerFocusBlocks(listOf("broken"), listOf("archive", "own"), CommunityUiState(loading = false, rows = listOf(xuper)))
        val lines = gridFocusLines(blocks, tvPickerColumns(1152f))
        assertEquals(
            listOf(
                listOf("installed-broken"),
                listOf("card-archive", "card-own"),
                listOf(COMMUNITY_HEADER_KEY),
                listOf("community-kinotvapp/kino-plugin-xuper"),
            ),
            lines,
        )
        assertEquals(PICKER_RECOMMENDED_HEADER_KEY, gridStopHeader(blocks, 4, "card-own"))
        assertEquals(PICKER_INSTALLED_HEADER_KEY, gridStopHeader(blocks, 4, "installed-broken"))
    }

    @Test fun `with the community list empty, Down from the recommended cards still reaches Actualizar`() {
        val lines = gridFocusLines(pickerFocusBlocks(emptyList(), listOf("archive"), CommunityUiState(loading = false)), 4)
        assertEquals(COMMUNITY_HEADER_KEY, gridFocusNeighbour(lines, "card-archive", down = true))
        assertNull(gridFocusNeighbour(lines, COMMUNITY_HEADER_KEY, down = true))
    }
}
