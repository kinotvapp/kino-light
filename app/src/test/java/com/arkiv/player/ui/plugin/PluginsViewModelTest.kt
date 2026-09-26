package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.PluginSettingsForm
import com.arkiv.player.data.plugin.SettingType
import com.arkiv.player.data.plugin.PluginTimeoutException
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.data.plugin.catalog.CatalogProvider
import com.arkiv.player.data.plugin.catalog.CatalogResult
import com.arkiv.player.data.plugin.catalog.PluginCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val preview = InstallPreview(PluginAddress("o", "r"), manifest, "{}", isUpdate = false, newHosts = listOf("example.com"))
    private val installedPlugin = InstalledPlugin(manifest, InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L), null)

    private class FakeAdmin : PluginAdmin {
        override val plugins = MutableStateFlow(emptyList<InstalledPlugin>())
        var previewResult: () -> InstallPreview = { throw InstallException("nope") }
        var installResult: () -> Unit = {}
        val installed = mutableListOf<InstallPreview>()
        var previewGate: CompletableDeferred<Unit>? = null
        var update: () -> UpdateOutcome = { UpdateOutcome.UpToDate }
        var updateChecks = 0
        val enabled = mutableMapOf<String, Boolean>()
        val uninstalled = mutableListOf<String>()
        val previewed = mutableListOf<String>()
        override suspend fun preview(input: String): InstallPreview { previewed += input; previewGate?.await(); return previewResult() }
        override suspend fun install(preview: InstallPreview) { installResult(); installed += preview }
        override suspend fun checkUpdate(id: String): UpdateOutcome { updateChecks++; return update() }
        override fun setEnabled(id: String, enabled: Boolean) { this.enabled[id] = enabled }
        override fun uninstall(id: String) { uninstalled += id }
        var form: PluginSettingsForm? = null
        var saveResult: String? = null
        val saved = mutableListOf<Map<String, Any?>>()
        override suspend fun settingsOf(id: String): PluginSettingsForm? = form
        override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? { saved += values; return saveResult }
    }

    private class FakeCatalog(vararg entries: CatalogEntry) : CatalogProvider {
        private val result = CatalogResult(PluginCatalog(entries.toList()), CatalogOrigin.FRESH)
        val forceFlags = mutableListOf<Boolean>()
        override suspend fun load(force: Boolean): CatalogResult { forceFlags += force; return result }
    }

    private fun entry(id: String, repo: String, name: String = id) = CatalogEntry(id, repo, name, "")

    private fun vm(admin: PluginAdmin, catalog: CatalogProvider = FakeCatalog()) =
        PluginsViewModel(admin, io = dispatcher, catalogProvider = catalog)

    @Test fun `adding shows the consent sheet and nothing is installed until confirmed`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = vm(admin)
        vm.onAddressChange("o/r")
        vm.add()
        assertEquals(preview, vm.state.value.consent)
        assertTrue(admin.installed.isEmpty())
        vm.confirmInstall()
        assertEquals(listOf(preview), admin.installed)
        with(vm.state.value) {
            assertEquals("Demo quedó instalado", message)
            assertNull(messagePluginId)
            assertEquals("", address)
            assertNull(consent)
            assertFalse(busy)
        }
    }

    @Test fun `cancel installs nothing`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.cancelConsent()
        assertNull(vm.state.value.consent)
        assertTrue(admin.installed.isEmpty())
    }

    @Test fun `a refused address shows the installer's message`() {
        val admin = FakeAdmin().apply { previewResult = { throw InstallException("Escribe usuario/repositorio, por ejemplo kinotvapp/kino-plugin-archive") } }
        val vm = vm(admin)
        vm.onAddressChange("nope"); vm.add()
        assertEquals("Escribe usuario/repositorio, por ejemplo kinotvapp/kino-plugin-archive", vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `an engine failure is said in Spanish, never with its raw message`() {
        val admin = FakeAdmin().apply {
            previewResult = { preview }
            installResult = { throw PluginTimeoutException("search", 15_000) }
        }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        assertEquals("El plugin no respondió a tiempo", vm.state.value.message)
        assertEquals("o/r", vm.state.value.address)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `an update that needs approval reopens the consent sheet`() {
        val update = preview.copy(isUpdate = true, newHosts = listOf("cdn.example.net"))
        val admin = FakeAdmin().apply { this.update = { UpdateOutcome.NeedsApproval(update) } }
        val vm = vm(admin)
        vm.checkUpdate("demo")
        assertEquals(update, vm.state.value.consent)
        assertNull(vm.state.value.message)
    }

    @Test fun `an approved update is reported on the plugin's row and keeps the typed address`() {
        val update = preview.copy(manifest = manifest.copy(version = "1.1.0"), isUpdate = true, newHosts = listOf("cdn.example.net"))
        val admin = FakeAdmin().apply { this.update = { UpdateOutcome.NeedsApproval(update) } }
        val vm = vm(admin)
        vm.onAddressChange("otro/repo")
        vm.checkUpdate("demo")
        vm.confirmInstall()
        assertEquals(listOf(update), admin.installed)
        with(vm.state.value) {
            assertEquals("Demo quedó actualizado a la 1.1.0", message)
            assertEquals("demo", messagePluginId)
            assertEquals("otro/repo", address)
        }
    }

    @Test fun `update outcomes are said in words, on the plugin's row`() {
        val admin = FakeAdmin()
        val vm = vm(admin)
        vm.checkUpdate("demo")
        assertEquals("Ya tienes la última versión", vm.state.value.message)
        assertEquals("demo", vm.state.value.messagePluginId)
        admin.update = { UpdateOutcome.Applied("1.1.0") }; vm.checkUpdate("demo")
        assertEquals("Actualizado a la 1.1.0", vm.state.value.message)
        admin.update = { UpdateOutcome.Failed("GitHub respondió 500") }; vm.checkUpdate("demo")
        assertEquals("GitHub respondió 500", vm.state.value.message)
    }

    @Test fun `an update needing a newer Kino is a failed check, not a pending approval`() {
        val admin = FakeAdmin().apply { update = { UpdateOutcome.Failed("Este plugin necesita una versión más nueva de Kino") } }
        val vm = vm(admin)
        vm.checkUpdate("demo")
        with(vm.state.value) {
            assertEquals("Este plugin necesita una versión más nueva de Kino", message)
            assertEquals("demo", messagePluginId)
            assertNull(consent)
        }
    }

    @Test fun `a thrown update check lands on the row in Spanish`() {
        val admin = FakeAdmin().apply { update = { throw java.io.IOException("timeout") } }
        val vm = vm(admin)
        vm.checkUpdate("demo")
        assertEquals("No hay conexión, vuelve a intentarlo", vm.state.value.message)
        assertEquals("demo", vm.state.value.messagePluginId)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `nothing else starts while an action is running`() {
        val gate = CompletableDeferred<Unit>()
        val admin = FakeAdmin().apply { previewResult = { preview }; previewGate = gate }
        val vm = vm(admin)
        vm.onAddressChange("o/r")
        vm.add()
        assertTrue(vm.state.value.busy)
        vm.checkUpdate("demo")
        assertEquals(0, admin.updateChecks)
        vm.askUninstall(installedPlugin)
        assertNull(vm.state.value.confirmUninstall)
        gate.complete(Unit)
        assertEquals(preview, vm.state.value.consent)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `toggling goes to the admin`() {
        val admin = FakeAdmin()
        val vm = vm(admin)
        vm.setEnabled("demo", false)
        assertEquals(mapOf("demo" to false), admin.enabled)
    }

    @Test fun `uninstall asks first`() {
        val admin = FakeAdmin()
        val vm = vm(admin)
        vm.askUninstall(installedPlugin)
        assertTrue(admin.uninstalled.isEmpty())
        vm.confirmUninstall()
        assertEquals(listOf("demo"), admin.uninstalled)
        assertEquals("Demo quedó desinstalado", vm.state.value.message)
        assertNull(vm.state.value.confirmUninstall)
    }

    @Test fun `cancelling the uninstall keeps the plugin`() {
        val admin = FakeAdmin()
        val vm = vm(admin)
        vm.askUninstall(installedPlugin)
        vm.cancelUninstall()
        assertNull(vm.state.value.confirmUninstall)
        assertTrue(admin.uninstalled.isEmpty())
    }

    @Test fun `a row message falls back to the section when its plugin is gone`() {
        val state = PluginsUiState(message = "Ya tienes la última versión", messagePluginId = "demo")
        assertEquals("demo", rowMessagePluginId(state, listOf(installedPlugin)))
        assertNull(rowMessagePluginId(state, emptyList()))
        assertNull(rowMessagePluginId(state.copy(messagePluginId = null), listOf(installedPlugin)))
    }

    private val configurable = installedPlugin.copy(
        manifest = manifest.copy(settings = listOf(
            PluginSetting("server", "Servidor", SettingType.URL, required = true),
            PluginSetting("password", "Contraseña", SettingType.PASSWORD, required = true),
            PluginSetting("hd", "Solo HD", SettingType.TOGGLE, default = false),
        )),
    )

    @Test fun `Configurar opens with the stored values, defaults filling the rest`() {
        val admin = FakeAdmin().apply { form = PluginSettingsForm(configurable, mapOf("server" to "http://10.0.0.2")) }
        val vm = vm(admin)
        vm.openSettings("demo")
        val draft = vm.state.value.configuring!!
        assertEquals(mapOf("server" to "http://10.0.0.2", "password" to "", "hd" to false), draft.values)
        assertEquals("Demo", draft.pluginName)
    }

    @Test fun `a missing required value or a loopback server is refused before anything is saved`() {
        val admin = FakeAdmin().apply { form = PluginSettingsForm(configurable, emptyMap()) }
        val vm = vm(admin)
        vm.openSettings("demo")
        vm.onSettingChange("server", "http://10.0.0.2")
        vm.saveSettings()
        assertEquals("Completa \"Contraseña\"", vm.state.value.configuring!!.error)
        vm.onSettingChange("password", "x")
        vm.onSettingChange("server", "http://127.0.0.1:8096")
        vm.saveSettings()
        assertTrue(vm.state.value.configuring!!.error!!.contains("no es una dirección válida"))
        assertEquals(emptyList<Map<String, Any?>>(), admin.saved)
    }

    @Test fun `saving closes Configurar and says so on the plugin's row`() {
        val admin = FakeAdmin().apply { form = PluginSettingsForm(configurable, emptyMap()) }
        val vm = vm(admin)
        vm.openSettings("demo")
        vm.onSettingChange("server", "http://10.0.0.2:8096")
        vm.onSettingChange("password", "s3cr3t")
        vm.saveSettings()
        assertNull(vm.state.value.configuring)
        assertTrue(vm.state.value.settingsClosed)
        assertEquals("Demo quedó configurado", vm.state.value.message)
        assertEquals("demo", vm.state.value.messagePluginId)
        assertEquals(listOf(mapOf<String, Any?>("server" to "http://10.0.0.2:8096", "password" to "s3cr3t", "hd" to false)), admin.saved)
    }

    @Test fun `a save the store refuses keeps Configurar open with its reason`() {
        val admin = FakeAdmin().apply { form = PluginSettingsForm(configurable, emptyMap()); saveResult = "El plugin ya no está instalado" }
        val vm = vm(admin)
        vm.openSettings("demo")
        vm.onSettingChange("server", "http://10.0.0.2")
        vm.onSettingChange("password", "x")
        vm.saveSettings()
        assertEquals("El plugin ya no está instalado", vm.state.value.configuring!!.error)
        assertFalse(vm.state.value.configuring!!.saving)
    }

    @Test fun `Configurar of a plugin that is gone closes at once`() {
        val vm = vm(FakeAdmin())
        vm.openSettings("demo")
        assertNull(vm.state.value.configuring)
        assertTrue(vm.state.value.settingsClosed)
    }

    @Test fun `the catalog loads on start and marks the entries that are installed`() {
        val admin = FakeAdmin().apply { plugins.value = listOf(installedPlugin) }
        val vm = vm(admin, FakeCatalog(entry("demo", "o/r"), entry("own", "o/own")))
        val rows = vm.catalog.value.rows
        assertEquals(listOf("demo", "own"), rows.map { it.entry.id })
        assertEquals("demo", rows[0].installed?.id)
        assertNull(rows[1].installed)
        assertFalse(vm.catalog.value.loading)
    }

    @Test fun `a view model built without a catalog provider settles on an empty seed catalog`() {
        // Ajustes ▸ Plugins and Configurar build it this way so visiting them never downloads the catalog.
        val vm = PluginsViewModel(FakeAdmin(), io = dispatcher)
        with(vm.catalog.value) {
            assertFalse(loading)
            assertTrue(rows.isEmpty())
            assertEquals(CatalogOrigin.SEED, origin)
        }
    }

    @Test fun `the query filters the catalog rows`() {
        val vm = vm(FakeAdmin(), FakeCatalog(entry("ia", "o/ia", "Internet Archive"), entry("own", "o/own", "Tu servidor")))
        vm.onQueryChange("servidor")
        assertEquals(listOf("own"), vm.catalog.value.rows.map { it.entry.id })
    }

    @Test fun `installing a catalog entry previews ITS repo and opens the consent sheet`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = vm(admin, FakeCatalog(entry("ia", "kinotvapp/kino-plugin-archive")))
        vm.installFromCatalog(vm.catalog.value.rows.single().entry)
        assertEquals(listOf("kinotvapp/kino-plugin-archive"), admin.previewed)
        assertNotNull(vm.state.value.consent)
    }

    @Test fun `a catalog entry with a repo the parser would refuse never reaches preview`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = vm(admin)
        for (repo in listOf("../evil", "https://evil.example/x", "a/b/c", "a/b?x=1", "")) {
            vm.installFromCatalog(CatalogEntry("x", repo, "X", ""))
        }
        assertTrue(admin.previewed.isEmpty())
    }

    @Test fun `reloading the catalog forces a fresh download`() {
        val catalog = FakeCatalog(entry("a", "o/a"))
        val vm = vm(FakeAdmin(), catalog)
        vm.reloadCatalog()
        assertEquals(listOf(false, true), catalog.forceFlags)
    }

    @Test fun `a row follows the installed plugins after the catalog has loaded`() {
        val admin = FakeAdmin()
        val vm = vm(admin, FakeCatalog(entry("demo", "o/r")))
        assertNull(vm.catalog.value.rows.single().installed)
        admin.plugins.value = listOf(installedPlugin)
        assertEquals("demo", vm.catalog.value.rows.single().installed?.id)
        admin.plugins.value = emptyList()
        assertNull(vm.catalog.value.rows.single().installed)
    }

    @Test fun `the query is kept in the state for the search box`() {
        val vm = vm(FakeAdmin())
        vm.onQueryChange("archive")
        assertEquals("archive", vm.state.value.query)
    }

    @Test fun `a catalog that throws ends the loading state with no rows`() {
        val vm = vm(FakeAdmin(), CatalogProvider { throw IllegalStateException("boom") })
        assertFalse(vm.catalog.value.loading)
        assertTrue(vm.catalog.value.rows.isEmpty())
    }

    @Test fun `a slow older load never lands on top of a newer one`() {
        val gate = CompletableDeferred<CatalogResult>()
        val fresh = CatalogResult(PluginCatalog(listOf(entry("fresh", "o/fresh"))), CatalogOrigin.FRESH)
        var calls = 0
        val vm = vm(FakeAdmin(), CatalogProvider { force -> if (calls++ == 0) gate.await() else fresh.also { assertTrue(force) } })
        assertTrue(vm.catalog.value.loading)
        vm.reloadCatalog()
        assertEquals(listOf("fresh"), vm.catalog.value.rows.map { it.entry.id })
        gate.complete(CatalogResult(PluginCatalog(listOf(entry("stale", "o/stale"))), CatalogOrigin.CACHE))
        assertEquals(listOf("fresh"), vm.catalog.value.rows.map { it.entry.id })
        assertEquals(CatalogOrigin.FRESH, vm.catalog.value.origin)
    }
}
