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
import kotlinx.coroutines.test.StandardTestDispatcher
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
        // What the disk holds: the same entries, as the cache would give them.
        override fun cachedOrSeed(): CatalogResult = result.copy(origin = CatalogOrigin.CACHE)
    }

    private val emptySeed = CatalogResult(PluginCatalog(emptyList()), CatalogOrigin.SEED)

    /** A provider whose disk copy is [onDisk] and whose download is whatever [download] does. */
    private class ScriptedCatalog(
        private val onDisk: CatalogResult,
        private val download: suspend (force: Boolean) -> CatalogResult,
    ) : CatalogProvider {
        override suspend fun load(force: Boolean): CatalogResult = download(force)
        override fun cachedOrSeed(): CatalogResult = onDisk
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

    // ---- Canonical-address matching: "same plugin" is the installer's canonical form, not the raw string ----

    private fun installedAt(address: String) = installedPlugin.copy(record = installedPlugin.record.copy(address = address))

    private fun installedIdFor(entryRepo: String, installedAddress: String): String? {
        val admin = FakeAdmin().apply { plugins.value = listOf(installedAt(installedAddress)) }
        return vm(admin, FakeCatalog(entry("demo", entryRepo))).catalog.value.rows.single().installed?.id
    }

    @Test fun `a catalog repo with a dot-git suffix matches the plugin installed without it`() {
        assertEquals("demo", installedIdFor("a/b.git", "a/b"))
    }

    @Test fun `a catalog repo without the suffix matches a plugin installed with it`() {
        assertEquals("demo", installedIdFor("a/b", "a/b.git"))
    }

    @Test fun `a github URL and an explicit HEAD ref are the same plugin as the bare repo`() {
        assertEquals("demo", installedIdFor("a/b", "https://github.com/a/b"))
        assertEquals("demo", installedIdFor("a/b", "a/b@HEAD"))
    }

    @Test fun `two different repos never match`() {
        assertNull(installedIdFor("a/b", "a/c"))
        assertNull(installedIdFor("a/b.git", "x/b"))
    }

    @Test fun `a different ref or folder is another plugin`() {
        assertNull(installedIdFor("a/b", "a/b@v2"))
        assertNull(installedIdFor("a/b", "a/b/sub"))
    }

    @Test fun `canonical does not fold case, so neither does the match (the installer compares the same way)`() {
        assertNull(installedIdFor("A/B", "a/b"))
    }

    @Test fun `an address that does not parse never matches a valid one, only itself`() {
        assertNull(installedIdFor("a/b", "not an address"))
        assertNull(installedIdFor("a b/c", "a/c"))
        assertEquals("demo", installedIdFor("a b/c", "a b/c"))
    }

    @Test fun `the query is kept in the state for the search box`() {
        val vm = vm(FakeAdmin())
        vm.onQueryChange("archive")
        assertEquals("archive", vm.state.value.query)
    }

    @Test fun `a catalog that throws with nothing on disk ends the loading state with no rows`() {
        val vm = vm(FakeAdmin(), ScriptedCatalog(emptySeed) { throw IllegalStateException("boom") })
        assertFalse(vm.catalog.value.loading)
        assertFalse(vm.catalog.value.refreshing)
        assertTrue(vm.catalog.value.rows.isEmpty())
    }

    @Test fun `a slow older load never lands on top of a newer one`() {
        val gate = CompletableDeferred<CatalogResult>()
        val fresh = CatalogResult(PluginCatalog(listOf(entry("fresh", "o/fresh"))), CatalogOrigin.FRESH)
        var calls = 0
        val vm = vm(FakeAdmin(), ScriptedCatalog(emptySeed) { force -> if (calls++ == 0) gate.await() else fresh.also { assertTrue(force) } })
        assertTrue(vm.catalog.value.loading)
        vm.reloadCatalog()
        assertEquals(listOf("fresh"), vm.catalog.value.rows.map { it.entry.id })
        gate.complete(CatalogResult(PluginCatalog(listOf(entry("stale", "o/stale"))), CatalogOrigin.CACHE))
        assertEquals(listOf("fresh"), vm.catalog.value.rows.map { it.entry.id })
        assertEquals(CatalogOrigin.FRESH, vm.catalog.value.origin)
    }

    // ---- Device pass: instant first paint, background refresh ----

    private fun result(origin: CatalogOrigin, vararg ids: String) =
        CatalogResult(PluginCatalog(ids.map { entry(it, "o/$it") }), origin)

    @Test fun `the rows are there on the first frame while the download is still pending`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.CACHE, "a", "b")) { gate.await() })
        with(vm.catalog.value) {
            assertEquals(listOf("a", "b"), rows.map { it.entry.id })
            assertFalse(loading)
            assertTrue(refreshing)
            assertEquals(CatalogOrigin.CACHE, origin)
        }
    }

    @Test fun `the seed shows at once when there is no cache, and no spinner hides it`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { gate.await() })
        with(vm.catalog.value) {
            assertEquals(listOf("seed"), rows.map { it.entry.id })
            assertFalse(loading)
            assertTrue(refreshing)
            assertEquals(CatalogOrigin.SEED, origin)
        }
    }

    @Test fun `only an empty list with a download pending counts as loading`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(emptySeed) { gate.await() })
        assertTrue(vm.catalog.value.loading)
        assertTrue(vm.catalog.value.refreshing)
        gate.complete(emptySeed)
        assertFalse(vm.catalog.value.loading)
        assertFalse(vm.catalog.value.refreshing)
    }

    @Test fun `the download replaces the rows and ends refreshing`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { gate.await() })
        assertTrue(vm.catalog.value.refreshing)
        gate.complete(result(CatalogOrigin.FRESH, "new1", "new2"))
        with(vm.catalog.value) {
            assertEquals(listOf("new1", "new2"), rows.map { it.entry.id })
            assertEquals(CatalogOrigin.FRESH, origin)
            assertFalse(refreshing)
            assertFalse(loading)
        }
    }

    @Test fun `a download that fails keeps the rows and ends refreshing`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.CACHE, "a")) { gate.await() })
        gate.completeExceptionally(java.io.IOException("offline"))
        with(vm.catalog.value) {
            assertEquals(listOf("a"), rows.map { it.entry.id })
            assertEquals(CatalogOrigin.CACHE, origin)
            assertFalse(refreshing)
        }
    }

    @Test fun `a download that ends on the seed again leaves the seed showing and refreshing over`() {
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { result(CatalogOrigin.SEED, "seed") })
        with(vm.catalog.value) {
            assertEquals(listOf("seed"), rows.map { it.entry.id })
            assertEquals(CatalogOrigin.SEED, origin)
            assertFalse(refreshing)
        }
    }

    @Test fun `reloading sets refreshing again until the forced download ends`() {
        val gates = mutableListOf<CompletableDeferred<CatalogResult>>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { CompletableDeferred<CatalogResult>().also { gates += it }.await() })
        gates[0].complete(result(CatalogOrigin.SEED, "seed"))
        assertFalse(vm.catalog.value.refreshing)
        vm.reloadCatalog()
        assertTrue(vm.catalog.value.refreshing)
        assertEquals(listOf("seed"), vm.catalog.value.rows.map { it.entry.id })
        gates[1].complete(result(CatalogOrigin.FRESH, "new"))
        assertFalse(vm.catalog.value.refreshing)
        assertEquals(listOf("new"), vm.catalog.value.rows.map { it.entry.id })
    }

    @Test fun `a forced reload that fails keeps the rows on screen`() {
        var calls = 0
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { if (calls++ == 0) result(CatalogOrigin.FRESH, "a") else throw IllegalStateException("boom") })
        vm.reloadCatalog()
        assertEquals(listOf("a"), vm.catalog.value.rows.map { it.entry.id })
        assertFalse(vm.catalog.value.refreshing)
    }

    @Test fun `a provider whose disk read throws still gives a view model, and the download still lands`() {
        val gate = CompletableDeferred<CatalogResult>()
        val provider = object : CatalogProvider {
            override suspend fun load(force: Boolean) = gate.await()
            override fun cachedOrSeed(): CatalogResult = throw IllegalStateException("disk")
        }
        val vm = vm(FakeAdmin(), provider)
        // Nothing to show yet, and a download pending: the spinner, not a crash.
        assertTrue(vm.catalog.value.rows.isEmpty())
        assertTrue(vm.catalog.value.loading)
        gate.complete(result(CatalogOrigin.FRESH, "a"))
        assertEquals(listOf("a"), vm.catalog.value.rows.map { it.entry.id })
        assertFalse(vm.catalog.value.refreshing)
    }

    @Test fun `the query filters the rows painted from the disk before the download ends`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(CatalogResult(PluginCatalog(listOf(entry("ia", "o/ia", "Internet Archive"), entry("own", "o/own", "Tu servidor"))), CatalogOrigin.CACHE)) { gate.await() })
        vm.onQueryChange("servidor")
        assertEquals(listOf("own"), vm.catalog.value.rows.map { it.entry.id })
        assertTrue(vm.catalog.value.refreshing)
    }

    // ---- failures: which sources failed, for Phase 2's telemetry ----

    @Test fun `a load whose result has failures exposes them in the catalog state`() {
        val failed = result(CatalogOrigin.SEED, "seed").copy(failures = mapOf("network" to "timeout", "cache" to "unreadable"))
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { failed })
        assertEquals(mapOf("network" to "timeout", "cache" to "unreadable"), vm.catalog.value.failures)
    }

    @Test fun `the disk copy shown first carries the failures of cachedOrSeed`() {
        val gate = CompletableDeferred<CatalogResult>()
        val disk = result(CatalogOrigin.SEED, "seed").copy(failures = mapOf("cache" to "corrupt"))
        val vm = vm(FakeAdmin(), ScriptedCatalog(disk) { gate.await() })
        assertTrue(vm.catalog.value.refreshing)
        assertEquals(mapOf("cache" to "corrupt"), vm.catalog.value.failures)
    }

    @Test fun `a disk copy with no failures shows none`() {
        val gate = CompletableDeferred<CatalogResult>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.CACHE, "a")) { gate.await() })
        assertTrue(vm.catalog.value.failures.isEmpty())
    }

    @Test fun `a later successful load replaces the failures`() {
        val gate = CompletableDeferred<CatalogResult>()
        val disk = result(CatalogOrigin.SEED, "seed").copy(failures = mapOf("cache" to "corrupt"))
        val vm = vm(FakeAdmin(), ScriptedCatalog(disk) { gate.await() })
        assertEquals(mapOf("cache" to "corrupt"), vm.catalog.value.failures)
        gate.complete(result(CatalogOrigin.FRESH, "new"))
        assertTrue(vm.catalog.value.failures.isEmpty())
    }

    @Test fun `a download that throws keeps the failures of the copy on screen`() {
        val disk = result(CatalogOrigin.SEED, "seed").copy(failures = mapOf("cache" to "corrupt"))
        val vm = vm(FakeAdmin(), ScriptedCatalog(disk) { throw java.io.IOException("offline") })
        assertEquals(mapOf("cache" to "corrupt"), vm.catalog.value.failures)
    }

    @Test fun `an older load that ends after a newer one started does not switch refreshing off`() {
        // A real provider blocks on IO, so the cancelled load finishes AFTER the reload has started: replay
        // that order with a queueing dispatcher.
        val standard = StandardTestDispatcher()
        Dispatchers.setMain(standard)
        val gates = mutableListOf<CompletableDeferred<CatalogResult>>()
        val vm = vm(FakeAdmin(), ScriptedCatalog(result(CatalogOrigin.SEED, "seed")) { CompletableDeferred<CatalogResult>().also { gates += it }.await() })
        standard.scheduler.runCurrent()
        assertEquals(1, gates.size)
        vm.reloadCatalog()
        standard.scheduler.runCurrent()
        assertEquals(2, gates.size)
        assertTrue(vm.catalog.value.refreshing)
        gates[1].complete(result(CatalogOrigin.FRESH, "new"))
        standard.scheduler.runCurrent()
        assertFalse(vm.catalog.value.refreshing)
        assertEquals(listOf("new"), vm.catalog.value.rows.map { it.entry.id })
    }
}
