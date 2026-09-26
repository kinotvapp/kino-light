package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.ui.plugin.PluginMoreTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which installed plugin the Categorías tiles open ("Ver más" over the tapped row): the one
 * [XuperPrivilege.grants] by its install address, and only while it's usable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesXuperTargetTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** Six series dramas: enough for the classifier's `magis_g_series_drama` genre row. */
    private val catalog = MagisHomeCatalog(tree = { root ->
        if (root != "series") emptyList() else listOf(
            CatalogSection(
                id = 1, name = "All", adult = false,
                items = (1..6).map { CatalogItem(id = "s$it", title = "t$it", poster = null, durationS = 0, type = "teleplay", genres = listOf("Drama")) },
            ),
        )
    })

    private fun plugin(id: String, address: String, enabled: Boolean = true, damaged: Boolean = false, unresponsive: Boolean = false) =
        InstalledPlugin(
            manifest = PluginManifest(id, "Name $id", "1.0.0", 1, "plugin.js", "", "", "", emptyList(), setOf("home", "browse"), null, null),
            record = InstalledRecord(address, "1.0.0", "sha", emptyList(), 1L, enabled = enabled, unresponsive = unresponsive, damaged = damaged),
            iconFile = null,
        )

    @Test fun `the recognized install is the target, by address and whatever its manifest id`() {
        val plugins = listOf(plugin("demo", "someone/kino-plugin-demo"), plugin("mi-xuper", XuperPrivilege.SOURCE_REPO))
        assertEquals("mi-xuper", CategoriesViewModel.xuperPluginId(plugins))
    }

    @Test fun `a copy claiming the xuper id from another repo is never the target`() {
        assertNull(CategoriesViewModel.xuperPluginId(listOf(plugin("xuper", "someone-else/kino-plugin-xuper"))))
        assertNull(CategoriesViewModel.xuperPluginId(listOf(plugin("xuper", "${XuperPrivilege.SOURCE_REPO}@dev"))))
    }

    @Test fun `no target while the xuper plugin is disabled, damaged, unresponsive or not installed`() {
        assertNull(CategoriesViewModel.xuperPluginId(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, enabled = false))))
        assertNull(CategoriesViewModel.xuperPluginId(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, damaged = true))))
        assertNull(CategoriesViewModel.xuperPluginId(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, unresponsive = true))))
        assertNull(CategoriesViewModel.xuperPluginId(emptyList()))
    }

    @Test fun `a tile opens the xuper plugin's Ver mas over that row id`() = runBlocking {
        val vm = CategoriesViewModel(catalog, MutableStateFlow(listOf(plugin("mi-xuper", XuperPrivilege.SOURCE_REPO))))
        val spec = withTimeout(5_000) { vm.rows.first { it.isNotEmpty() } }.first { it.id == "magis_g_series_drama" }

        assertEquals(PluginMoreTarget.Browse("mi-xuper", spec.title, "magis_g_series_drama"), vm.browseTarget(spec))
    }

    @Test fun `the tiles go away while the xuper plugin isn't usable, and come back with it`() = runBlocking {
        val plugins = MutableStateFlow(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO)))
        val vm = CategoriesViewModel(catalog, plugins)
        val spec = withTimeout(5_000) { vm.rows.first { it.isNotEmpty() } }.first()

        plugins.value = listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, enabled = false))
        assertTrue(vm.rows.value.isEmpty())
        assertNull(vm.browseTarget(spec))

        plugins.value = emptyList()
        assertTrue(vm.rows.value.isEmpty())

        plugins.value = listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO))
        assertTrue(vm.rows.value.isNotEmpty())
    }
}
