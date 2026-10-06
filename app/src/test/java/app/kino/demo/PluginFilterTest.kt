package app.kino.demo

import app.kino.demo.data.DemoSources
import app.kino.demo.data.PluginCategory
import app.kino.demo.data.PluginKind
import app.kino.demo.data.categoryChips
import app.kino.demo.data.filterPlugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFilterTest {
    @Test
    fun chipsStartWithTodosAndKeepTheFixedOrder() {
        val chips = categoryChips(DemoSources.all)
        assertNull(chips.first())
        assertEquals(PluginCategory.entries.toList(), chips.drop(1))
    }

    @Test
    fun chipsOnlyNameCategoriesTheListHas() {
        val chips = categoryChips(DemoSources.community)
        assertTrue(chips.drop(1).all { c -> DemoSources.community.any { c in it.categories } })
    }

    @Test
    fun filterCombinesCategoryAndText() {
        val radio = filterPlugins(DemoSources.all, "", PluginCategory.RADIO)
        assertTrue(radio.isNotEmpty() && radio.all { PluginCategory.RADIO in it.categories })
        assertTrue(filterPlugins(DemoSources.all, "zzz", null).isEmpty())
        assertEquals(DemoSources.all, filterPlugins(DemoSources.all, "  ", null))
    }

    @Test
    fun onlyNuvioAndStremioWearABadge() {
        assertNull(PluginKind.KINO.badge)
        assertEquals("Nuvio", PluginKind.NUVIO.badge)
        assertEquals("Stremio", PluginKind.STREMIO.badge)
    }
}
