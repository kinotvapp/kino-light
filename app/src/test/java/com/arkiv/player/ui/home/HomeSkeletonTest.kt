package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSkeletonTest {
    private fun plugin(enabled: Boolean) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled),
        iconFile = null,
    )

    private val usable = listOf(plugin(enabled = true))

    @Test fun `plugin rows are loading only while a usable plugin's pass is not over`() {
        assertTrue(homePluginRowsLoading(usable, pluginRowsSettled = false))
        assertFalse(homePluginRowsLoading(usable, pluginRowsSettled = true))
        assertFalse(homePluginRowsLoading(emptyList<InstalledPlugin>(), pluginRowsSettled = false))
        assertFalse(homePluginRowsLoading(listOf(plugin(enabled = false)), pluginRowsSettled = false))
    }

    @Test fun `the phone shows two skeleton rows until real plugin rows take their place`() {
        assertEquals(2, homeSkeletonRowCount(loading = true, rowsAbove = 0, pluginRowCount = 0, slots = PHONE_HOME_SKELETON_SLOTS))
        assertEquals(1, homeSkeletonRowCount(loading = true, rowsAbove = 0, pluginRowCount = 1, slots = PHONE_HOME_SKELETON_SLOTS))
        // A full set of real rows: never skeletons AND real rows filling the space.
        assertEquals(0, homeSkeletonRowCount(loading = true, rowsAbove = 0, pluginRowCount = 2, slots = PHONE_HOME_SKELETON_SLOTS))
        assertEquals(0, homeSkeletonRowCount(loading = true, rowsAbove = 0, pluginRowCount = 7, slots = PHONE_HOME_SKELETON_SLOTS))
    }

    @Test fun `the TV fills only what its two-row zone leaves free`() {
        // Nothing above: both rows are skeletons.
        assertEquals(2, homeSkeletonRowCount(loading = true, rowsAbove = 0, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS))
        // The live row alone (the reported case): one skeleton under it instead of black.
        assertEquals(1, homeSkeletonRowCount(loading = true, rowsAbove = 1, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS))
        // Live row + the first plugin row already fill the zone.
        assertEquals(0, homeSkeletonRowCount(loading = true, rowsAbove = 1, pluginRowCount = 1, slots = TV_HOME_VISIBLE_ROWS))
        // Continue watching + live already fill it: nothing to hold a place for on screen.
        assertEquals(0, homeSkeletonRowCount(loading = true, rowsAbove = 2, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS))
        assertEquals(0, homeSkeletonRowCount(loading = true, rowsAbove = 3, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS))
    }

    @Test fun `no skeleton once the pass settled, even with no row at all`() {
        assertEquals(0, homeSkeletonRowCount(loading = false, rowsAbove = 0, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS))
        assertEquals(0, homeSkeletonRowCount(loading = false, rowsAbove = 0, pluginRowCount = 0, slots = PHONE_HOME_SKELETON_SLOTS))
    }

    @Test fun `skeletons never show together with the empty state`() {
        for (plugins in listOf(emptyList(), listOf(plugin(enabled = false)), usable)) {
            val loading = homePluginRowsLoading(plugins, pluginRowsSettled = false)
            val skeletons = homeSkeletonRowCount(loading, rowsAbove = 0, pluginRowCount = 0, slots = TV_HOME_VISIBLE_ROWS)
            val empty = homeShowsEmptyState(plugins, pluginRowCount = 0, liveAvailable = false)
            assertFalse("plugins=$plugins", skeletons > 0 && empty)
            assertTrue("plugins=$plugins", skeletons > 0 || empty)
        }
    }

    @Test fun `skeleton keys are stable and distinct from every real row key`() {
        assertEquals(listOf("skeleton-0", "skeleton-1"), (0 until 2).map(::homeSkeletonKey))
    }

    @Test fun `the TV hero shows its skeleton only while loading with nothing featured`() {
        assertTrue(tvHeroShowsSkeleton(hasFeatured = false, loading = true))
        assertFalse(tvHeroShowsSkeleton(hasFeatured = true, loading = true))
        // A settled Home with nothing featured (the empty state) is not "about to arrive".
        assertFalse(tvHeroShowsSkeleton(hasFeatured = false, loading = false))
    }

    @Test fun `the skeleton's spoken label`() {
        assertEquals("Cargando tus fuentes…", HOME_LOADING_LINE)
    }
}
