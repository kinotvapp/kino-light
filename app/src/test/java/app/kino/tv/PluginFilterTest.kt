package app.kino.tv

import app.kino.tv.data.KinoSources
import app.kino.tv.data.PluginCategory
import app.kino.tv.data.PluginKind
import app.kino.tv.data.categoryChips
import app.kino.tv.data.filterPlugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFilterTest {
    @Test
    fun chipsStartWithTodosAndKeepTheFixedOrder() {
        val chips = categoryChips(KinoSources.all)
        assertNull(chips.first())
        assertEquals(PluginCategory.entries.toList(), chips.drop(1))
    }

    @Test
    fun chipsOnlyNameCategoriesTheListHas() {
        val chips = categoryChips(KinoSources.community)
        assertTrue(chips.drop(1).all { c -> KinoSources.community.any { c in it.categories } })
    }

    @Test
    fun filterCombinesCategoryAndText() {
        val radio = filterPlugins(KinoSources.all, "", PluginCategory.RADIO)
        assertTrue(radio.isNotEmpty() && radio.all { PluginCategory.RADIO in it.categories })
        assertTrue(filterPlugins(KinoSources.all, "zzz", null).isEmpty())
        assertEquals(KinoSources.all, filterPlugins(KinoSources.all, "  ", null))
    }

    @Test
    fun onlyNuvioAndStremioWearABadge() {
        assertNull(PluginKind.KINO.badge)
        assertEquals("Nuvio", PluginKind.NUVIO.badge)
        assertEquals("Stremio", PluginKind.STREMIO.badge)
    }
}
