package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows get drawn in the TV results, in what order, and which ones are skipped. */
class SourceTabTest {

    private fun magis(title: String) = PlaySource.Magis(
        GatewayResult(source = "magis", title = title, ref = "r-$title"),
    )

    private fun caracol(title: String) = PlaySource.Ditu(
        GatewayResult(source = "ditu", title = title, ref = "ditu1:VOD:$title"),
    )

    @Test fun `a caracol result falls into its tab`() {
        assertEquals(SourceTab.CARACOL, tabOf(caracol("c")))
    }

    @Test fun `the counts include caracol even at zero`() {
        // Without the key, Caracol's chip doesn't paint until its first result arrives.
        val counts = countsByTab(listOf(magis("m")))
        assertTrue(SourceTab.CARACOL in counts)
        assertEquals(0, counts[SourceTab.CARACOL])
        assertEquals(1, counts[SourceTab.MAGIS])
        assertEquals(1, counts[SourceTab.ALL])
    }

    @Test fun `caracol goes after magis even if it arrives first`() {
        val r = visibleRows(listOf(caracol("c"), magis("m")), SourceTab.ALL)
        assertEquals(listOf(SourceTab.MAGIS, SourceTab.CARACOL), r.map { it.first })
    }

    @Test fun `the caracol filter leaves only caracol`() {
        val r = visibleRows(listOf(caracol("c"), magis("m")), SourceTab.CARACOL)
        assertEquals(listOf(SourceTab.CARACOL), r.map { it.first })
        assertEquals(listOf(caracol("c")), filterByTab(listOf(caracol("c"), magis("m")), SourceTab.CARACOL))
    }

    @Test fun `the rows go in the enum's order`() {
        val r = visibleRows(listOf(magis("m")), SourceTab.ALL)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
    }

    @Test fun `a source with no results leaves no row`() {
        val r = visibleRows(listOf(magis("m")), SourceTab.ALL)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
    }

    @Test fun `with no results there's no row at all`() {
        assertTrue(visibleRows(emptyList(), SourceTab.ALL).isEmpty())
    }

    @Test fun `with a filter set only one row is left`() {
        val r = visibleRows(listOf(magis("m")), SourceTab.MAGIS)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
        assertEquals(1, r.first().second.size)
    }

    @Test fun `a filter over an empty source leaves no rows`() {
        assertTrue(visibleRows(emptyList(), SourceTab.MAGIS).isEmpty())
    }

    @Test fun `each row keeps its source's arrival order`() {
        val sources = listOf(magis("a"), magis("b"))
        val row = visibleRows(sources, SourceTab.ALL).first { it.first == SourceTab.MAGIS }
        assertEquals(listOf("a", "b"), row.second.map { (it as PlaySource.Magis).result.title })
    }
}
