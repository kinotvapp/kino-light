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
        withTimeout(5_000) { vm.loading.first { !it } }

        assertEquals(PluginMoreTarget.Browse("mi-xuper", spec.title, "magis_g_series_drama"), vm.browseTarget(spec))
    }

    @Test fun `the tiles go away while the xuper plugin isn't usable, and come back with it`() = runBlocking<Unit> {
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
        // Becoming usable again reloads (cache-first): let it land before Main is reset.
        withTimeout(5_000) { vm.loading.first { !it } }
    }

    // --- no portal read without a usable Xuper plugin ------------------------------------------

    /** The same catalog, counting every portal tree read (`rows()` with no store always ends in `load()`, which reads it). */
    private val treeReads = java.util.concurrent.atomic.AtomicInteger()
    private val countingCatalog = MagisHomeCatalog(tree = { root ->
        treeReads.incrementAndGet()
        if (root != "series") emptyList() else listOf(
            CatalogSection(
                id = 1, name = "All", adult = false,
                items = (1..6).map { CatalogItem(id = "s$it", title = "t$it", poster = null, durationS = 0, type = "teleplay", genres = listOf("Drama")) },
            ),
        )
    })

    @Test fun `without a usable xuper plugin the catalog is never read, not even on reload`() = runBlocking {
        for (installed in listOf(
            emptyList(),
            listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, enabled = false)),
            listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, damaged = true)),
            listOf(plugin("xuper", "someone-else/kino-plugin-xuper")),
        )) {
            val reload = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val vm = CategoriesViewModel(countingCatalog, MutableStateFlow(installed), reload)
            reload.emit(Unit)
            assertEquals(installed.toString(), 0, treeReads.get())
            assertTrue(vm.rows.value.isEmpty())
            assertEquals(false, vm.loading.value)
        }
    }

    @Test fun `the catalog is read once the xuper plugin becomes usable`() = runBlocking {
        val plugins = MutableStateFlow(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, enabled = false)))
        val vm = CategoriesViewModel(countingCatalog, plugins)
        assertEquals(0, treeReads.get())

        plugins.value = listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO))
        withTimeout(5_000) { vm.rows.first { it.isNotEmpty() } }
        withTimeout(5_000) { vm.loading.first { !it } }
        assertTrue(treeReads.get() > 0)
    }

    private fun tile(plugin: String, title: String, ref: String, genre: String? = null) =
        GenreTile(plugin, plugin.uppercase(), title, null, genre ?: com.arkiv.player.data.plugin.Genre.infer(title), ref)

    @Test fun `with Xuper off, other plugins' rows are the whole tab, opened by their own plugin and ref`() {
        val plugins = MutableStateFlow(listOf(plugin("tv1", "o/tv1")))
        val tiles = MutableStateFlow(listOf(tile("tv1", "Deportes en vivo", "r-dep"), tile("tv2", "Recién agregadas", "r-new")))
        val vm = CategoriesViewModel(catalog, plugins, tiles = tiles)
        assertEquals(emptyList<CategorySpec>(), vm.rows.value)   // no Xuper: none of its catalog tiles
        assertEquals(listOf("deportes", null), vm.genreSections.value.map { it.genre })
        val spec = vm.specOf(vm.genreSections.value.first().tiles.single())
        assertEquals(PluginMoreTarget.Browse("tv1", "Deportes en vivo", "r-dep"), vm.browseTarget(spec))
    }

    @Test fun `tiles of two plugins with the same ref never share a key`() {
        val tiles = MutableStateFlow(listOf(tile("a", "Noticias", "same"), tile("b", "Noticias 2", "same")))
        val vm = CategoriesViewModel(catalog, MutableStateFlow(emptyList()), tiles = tiles)
        val specs = vm.genreSections.value.flatMap { it.tiles }.map(vm::specOf)
        assertEquals(2, specs.map { it.id }.toSet().size)
    }

    @Test fun `a Xuper tile still opens through Xuper with its row id`() {
        val plugins = MutableStateFlow(listOf(plugin("mi-xuper", XuperPrivilege.SOURCE_REPO)))
        val vm = CategoriesViewModel(catalog, plugins)
        assertEquals(PluginMoreTarget.Browse("mi-xuper", "Drama", "magis_g_series_drama"), vm.browseTarget(CategorySpec("magis_g_series_drama", "Drama", null)))
    }
}
