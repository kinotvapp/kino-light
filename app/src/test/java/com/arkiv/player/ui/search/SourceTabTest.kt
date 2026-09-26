package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows get drawn in the TV results, in what order, and which ones are skipped. */
class SourceTabTest {

    private fun caracol(title: String) = PlaySource.Ditu(
        GatewayResult(source = "ditu", title = title, ref = "ditu1:VOD:$title"),
    )

    private fun plugin(title: String, id: String = "demo", name: String = "Demo") = PlaySource.Plugin(
        id, name, 0xFFE0A030, GatewayResult(source = "plugin:$id", title = title, ref = "plg1:$id:$title"),
    )

    @Test fun `plugin tabs come after the fixed ones, in arrival order, only when they bring results`() {
        val sources = listOf(plugin("a", "p2", "Dos"), caracol("c"), plugin("b", "p1", "Uno"))
        assertEquals(listOf("all", "ditu", "plugin:p2", "plugin:p1"), tabsFor(sources).map { it.key })
        assertEquals(listOf("all", "ditu"), tabsFor(listOf(caracol("c"))).map { it.key })
    }

    /** Xuper searches through its plugin: its tab is the plugin's, there is no native one. */
    @Test fun `xuper's tab is its plugin's, there is no native xuper tab`() {
        val sources = listOf(plugin("a", "xuper", "Xuper"))
        assertEquals(listOf("all", "ditu", "plugin:xuper"), tabsFor(sources).map { it.key })
        assertEquals(listOf("all", "ditu"), tabsFor(emptyList()).map { it.key })
        assertFalse(countsByTab(sources).keys.any { it.key == "magis" })
        assertEquals(listOf(SourceTab.ALL, SourceTab.CARACOL), SourceTab.FIXED)
    }

    @Test fun `a plugin tab filters and counts its own results`() {
        val sources = listOf(plugin("a"), plugin("b"), caracol("c"))
        val tab = tabOf(sources[0])
        assertEquals(2, countsByTab(sources)[tab])
        assertEquals(listOf("a", "b"), filterByTab(sources, tab).map { (it as PlaySource.Plugin).result.title })
        assertEquals(listOf(SourceTab.CARACOL, tab), visibleRows(sources, SourceTab.ALL).map { it.first })
    }

    @Test fun `tabs are equal by key whatever their label`() {
        assertEquals(tabOf(plugin("a")), tabForSource("plugin:demo"))
        assertEquals("Demo", tabForSource("plugin:demo", mapOf("plugin:demo" to "Demo"))!!.label)
    }

    @Test fun `a caracol result falls into its tab`() {
        assertEquals(SourceTab.CARACOL, tabOf(caracol("c")))
    }

    @Test fun `the counts include caracol even at zero`() {
        // Without the key, Caracol's chip doesn't paint until its first result arrives.
        val counts = countsByTab(listOf(plugin("p")))
        assertTrue(SourceTab.CARACOL in counts)
        assertEquals(0, counts[SourceTab.CARACOL])
        assertEquals(1, counts[tabOf(plugin("p"))])
        assertEquals(1, counts[SourceTab.ALL])
    }

    @Test fun `caracol goes before a plugin even if the plugin arrives first`() {
        val r = visibleRows(listOf(plugin("p"), caracol("c")), SourceTab.ALL)
        assertEquals(listOf(SourceTab.CARACOL, tabOf(plugin("p"))), r.map { it.first })
    }

    @Test fun `the caracol filter leaves only caracol`() {
        val r = visibleRows(listOf(caracol("c"), plugin("p")), SourceTab.CARACOL)
        assertEquals(listOf(SourceTab.CARACOL), r.map { it.first })
        assertEquals(listOf(caracol("c")), filterByTab(listOf(caracol("c"), plugin("p")), SourceTab.CARACOL))
    }

    @Test fun `the rows go in the fixed order`() {
        val r = visibleRows(listOf(plugin("p"), caracol("c")), SourceTab.ALL)
        assertEquals(listOf(SourceTab.CARACOL, tabOf(plugin("p"))), r.map { it.first })
    }

    @Test fun `a source with no results leaves no row`() {
        // Caracol is a fixed tab but brought nothing here: no row for it.
        val r = visibleRows(listOf(plugin("p")), SourceTab.ALL)
        assertEquals(listOf(tabOf(plugin("p"))), r.map { it.first })
    }

    @Test fun `with no results there's no row at all`() {
        assertTrue(visibleRows(emptyList(), SourceTab.ALL).isEmpty())
    }

    @Test fun `with a filter set only one row is left`() {
        val tab = tabOf(plugin("p"))
        val r = visibleRows(listOf(plugin("p"), caracol("c")), tab)
        assertEquals(listOf(tab), r.map { it.first })
        assertEquals(1, r.first().second.size)
    }

    @Test fun `a filter over an empty source leaves no rows`() {
        assertTrue(visibleRows(emptyList(), SourceTab.CARACOL).isEmpty())
    }

    @Test fun `each row keeps its source's arrival order`() {
        val sources = listOf(caracol("a"), caracol("b"))
        val row = visibleRows(sources, SourceTab.ALL).first { it.first == SourceTab.CARACOL }
        assertEquals(listOf("a", "b"), row.second.map { (it as PlaySource.Ditu).result.title })
    }
}
