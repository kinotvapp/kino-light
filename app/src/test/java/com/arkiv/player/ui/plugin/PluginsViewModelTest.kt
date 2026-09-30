package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.NuvioPluginInstaller
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.PluginFetcher
import com.arkiv.player.data.plugin.PluginInstaller
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.PluginSettingsForm
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.SettingType
import com.arkiv.player.data.plugin.PluginTimeoutException
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.data.plugin.catalog.CatalogArtProvider
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.data.plugin.catalog.CatalogProvider
import com.arkiv.player.data.plugin.catalog.CatalogResult
import com.arkiv.player.data.plugin.catalog.PluginCatalog
import com.arkiv.player.data.plugin.discovery.DiscoveredPlugin
import com.arkiv.player.data.plugin.discovery.DiscoveryOrigin
import com.arkiv.player.data.plugin.discovery.DiscoveryResult
import com.arkiv.player.data.plugin.discovery.PluginDiscoveryProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors

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
        val forgottenRejections = mutableListOf<String>()
        val revokedAnyVideoHost = mutableListOf<String>()
        val previewed = mutableListOf<String>()
        override suspend fun preview(input: String): InstallPreview { previewed += input; previewGate?.await(); return previewResult() }
        override suspend fun install(preview: InstallPreview) { installResult(); installed += preview }
        override suspend fun checkUpdate(id: String): UpdateOutcome { updateChecks++; return update() }
        override fun setEnabled(id: String, enabled: Boolean) { this.enabled[id] = enabled }
        override fun uninstall(id: String) { uninstalled += id }
        override fun forgetHostRejections(id: String) { forgottenRejections += id }
        override fun revokeAnyVideoHost(id: String) { revokedAnyVideoHost += id }
        var form: PluginSettingsForm? = null
        var settingsFailure: Exception? = null
        /**
         * The real admin only knows a plugin's settings once it is installed and the registry has reloaded
         * (`registry.find(id)`); with this on, [settingsOf] answers null until [install] has run for that id.
         */
        var settingsOnlyAfterInstall = false
        var saveResult: String? = null
        val saved = mutableListOf<Map<String, Any?>>()
        override suspend fun settingsOf(id: String): PluginSettingsForm? {
            settingsFailure?.let { throw it }
            if (settingsOnlyAfterInstall && installed.none { it.manifest.id == id }) return null
            return form
        }
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

    @Test fun `Quitar permiso de video amplio revokes it and says so on the plugin's card`() {
        val admin = FakeAdmin()
        val vm = vm(admin)
        vm.revokeAnyVideoHost("demo")
        assertEquals(listOf("demo"), admin.revokedAnyVideoHost)
        assertEquals("Se quitó el permiso de video amplio", vm.state.value.message)
        assertEquals("demo", vm.state.value.messagePluginId)
    }

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

    // The dialog's two exits: dismissing it (Cancelar, Back) forgets what was typed and what the last try said, so
    // it reopens empty; cancelling the consent sheet it raised keeps the text, so it returns as it was left.
    @Test fun `dismissing the dialog clears the typed address and the refusal that was under it`() {
        val admin = FakeAdmin().apply { previewResult = { throw InstallException("No encontré kino-plugin.json") } }
        val vm = vm(admin)
        vm.onAddressChange("nope"); vm.add()
        assertEquals("No encontré kino-plugin.json", vm.state.value.message)

        vm.onAddressChange(addressAfterDialogDismissed())

        with(vm.state.value) {
            assertEquals("", address)
            assertNull(message)
        }
    }

    // The dialog opens empty too: a confirmed install that failed leaves its address in the field (the failure message
    // is about it), and the next "Agregar" must not show that stale repository.
    @Test fun `opening the dialog empties the address a failed install left and drops the message about a row`() {
        val admin = FakeAdmin().apply {
            previewResult = { preview }
            installResult = { throw PluginTimeoutException("search", 15_000) }
            update = { UpdateOutcome.UpToDate }
        }
        admin.plugins.value = listOf(installedPlugin)
        val vm = vm(admin)
        vm.onQueryChange("archive")
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        assertEquals("o/r", vm.state.value.address)
        vm.checkUpdate("demo")
        assertEquals("demo", vm.state.value.messagePluginId)

        vm.onAddressChange("")

        with(vm.state.value) {
            assertEquals("", address)
            assertNull(message)
            assertNull(messagePluginId)
            assertEquals("archive", query)
        }
    }

    @Test fun `cancelling the consent keeps the typed address`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add()
        vm.cancelConsent()
        assertEquals("o/r", vm.state.value.address)
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

    // ---- Nuvio: a typed address that is a provider repo opens the scraper picker instead ----

    @get:Rule val tmp = TemporaryFolder()

    private val nuvioManifestJson = """
        { "name": "nuvio-providers", "scrapers": [
          { "id": "fakesrc", "name": "FakeSrc", "filename": "providers/fakesrc.js", "enabled": true, "supportedTypes": ["movie"] }
        ] }
    """.trimIndent()
    private val nuvioScraperJs = """
        function getStreams() { return [{ name: "FakeSrc", title: "t", url: "https://fakesrc.example/v.mp4", quality: "1080p" }]; }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    /**
     * A real [NuvioPluginInstaller] (it is a concrete class, not an interface: faking it would mean
     * duplicating its own logic), wired to serve exactly [files] and backed by a throwaway
     * [PluginStore] on [tmp]. [PluginInstaller]'s own fetcher and probe are never reached by
     * `previewRepo`/`previewScraper` (only `install`/`checkUpdate` touch them), so they are stubs
     * that fail loudly if that ever changes.
     */
    private fun nuvioInstaller(files: Map<String, String>, gate: CompletableDeferred<Unit>? = null): NuvioPluginInstaller {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        val pluginInstaller = PluginInstaller(
            store,
            PluginFetcher { url, _ -> throw FileNotFoundException(url) },
            probe = { _, _ -> error("not used by previewRepo/previewScraper") },
        )
        return NuvioPluginInstaller(
            pluginInstaller,
            PluginFetcher { url, _ ->
                // [gate] holds back everything but the manifest: a conversion still downloading its script.
                if (gate != null && !url.endsWith("manifest.json")) gate.await()
                files[url]?.toByteArray() ?: throw FileNotFoundException(url)
            },
        )
    }

    @Test fun `a typed address that is a Nuvio provider repo opens the scraper picker, not the normal consent`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf("https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/nuvio-repo")
        vm.add()
        assertEquals(listOf("fakesrc"), vm.state.value.nuvioPicker?.scrapers?.map { it.id })
        assertEquals("owner/nuvio-repo", vm.state.value.nuvioPicker?.repoInput)
        assertNull(vm.state.value.consent)
        assertTrue(admin.previewed.isEmpty())
    }

    /**
     * `yoruix/nuvio-providers` (GitHub redirects the old name `tapframe/nuvio-providers` to it) has
     * default branch `template`, whose `manifest.json` is a placeholder, not Nuvio-shaped -- see
     * [NuvioPluginInstallerTest]'s own fallback tests for the installer-level behavior this exercises
     * end to end: the picker opens off the address that actually worked (`@main`), and picking a
     * scraper from it re-resolves from THAT address, not the person's raw, still-failing typed text.
     */
    @Test fun `typing an address whose default branch is not Nuvio-shaped still opens the picker via the @main fallback`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf(
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/HEAD/manifest.json" to """[{"disabled":true}]""",
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json" to nuvioManifestJson,
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/providers/fakesrc.js" to nuvioScraperJs,
        ))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("yoruix/nuvio-providers")
        vm.add()
        assertEquals(listOf("fakesrc"), vm.state.value.nuvioPicker?.scrapers?.map { it.id })
        assertEquals("yoruix/nuvio-providers@main", vm.state.value.nuvioPicker?.repoInput)
        vm.pickNuvioScraper("fakesrc")
        assertNotNull(vm.state.value.consent)
        assertTrue("fakesrc.example" in vm.state.value.consent!!.newHosts)
    }

    @Test fun `a typed address that is not a Nuvio manifest falls back to the normal consent, even with a Nuvio installer wired in`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val nuvio = nuvioInstaller(emptyMap()) // no manifest.json at all: previewRepo answers null
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/not-nuvio")
        vm.add()
        assertEquals(listOf("owner/not-nuvio"), admin.previewed)
        assertEquals(preview, vm.state.value.consent)
        assertNull(vm.state.value.nuvioPicker)
    }

    @Test fun `a Nuvio repo with no installable scrapers says so instead of opening an empty picker`() {
        val admin = FakeAdmin()
        val disabledManifest = """{ "name": "n", "scrapers": [ { "id": "x", "filename": "x.js", "enabled": false } ] }"""
        val nuvio = nuvioInstaller(mapOf("https://raw.githubusercontent.com/owner/empty/HEAD/manifest.json" to disabledManifest))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/empty")
        vm.add()
        assertNull(vm.state.value.nuvioPicker)
        assertNull(vm.state.value.consent)
        assertEquals("Este repositorio de Nuvio no tiene scrapers instalables en Android", vm.state.value.message)
    }

    @Test fun `picking a scraper converts it and opens the same consent sheet, keeping the picker open behind it`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to nuvioScraperJs,
        ))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/nuvio-repo")
        vm.add()
        vm.pickNuvioScraper("fakesrc")
        // The full-screen picker stays open behind the consent sheet: several scrapers of the same repo
        // can be added in a row without retyping the address.
        assertNotNull(vm.state.value.nuvioPicker)
        val consent = vm.state.value.consent
        assertNotNull(consent)
        assertTrue("fakesrc.example" in consent!!.newHosts)
        // Hands off to the SAME confirmInstall/admin.install path: nothing installs on its own here.
        assertTrue(admin.installed.isEmpty())
        // Confirming the install clears the consent sheet only: the picker is still there to add another.
        vm.confirmInstall()
        assertNull(vm.state.value.consent)
        assertNotNull(vm.state.value.nuvioPicker)
        assertEquals(listOf(consent), admin.installed)
    }

    @Test fun `reinstalling a damaged Nuvio-origin plugin re-converts its scraper instead of the generic preview`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to nuvioScraperJs,
        ))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        val damaged = InstalledPlugin(
            manifest.copy(id = "nuvio-fakesrc-abc123"),
            InstalledRecord("owner/nuvio-repo", "1.0.0", "x", listOf("fakesrc.example"), 0L, damaged = true,
                nuvioRepo = "owner/nuvio-repo", nuvioScraperId = "fakesrc"),
            null,
        )
        vm.reinstall(damaged)
        // Never the generic path: the Nuvio repo has no kino-plugin.json for it to read.
        assertTrue(admin.previewed.isEmpty())
        val consent = vm.state.value.consent
        assertNotNull(consent)
        assertEquals("fakesrc", consent!!.nuvioOrigin?.scraperId)
        assertEquals(listOf("api.themoviedb.org", "fakesrc.example"), consent.manifest.hosts)
    }

    @Test fun `the source picker's Instalar on a damaged Nuvio-origin plugin re-converts its scraper instead of the generic preview`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to nuvioScraperJs,
        ))
        // Built exactly as the picker's own view model is (phone and TV share it), not a hand-wired one.
        val vm = newSourcePickerViewModel(admin, FakeCatalog(), NoCatalogArt, NoDiscovery, nuvio, io = dispatcher)
        val damaged = InstalledPlugin(
            manifest.copy(id = "nuvio-fakesrc-abc123"),
            InstalledRecord("owner/nuvio-repo", "1.0.0", "x", listOf("fakesrc.example"), 0L, damaged = true,
                nuvioRepo = "owner/nuvio-repo", nuvioScraperId = "fakesrc"),
            null,
        )
        // The picker's "Tus plugins" row for it, and the very action its "Instalar" button runs.
        val row = pickerInstalledRows(listOf(damaged), shown = emptyList()).single()
        assertEquals(CatalogAction.INSTALL, catalogActionOf(row))
        runCatalogAction(vm, row)
        assertTrue(admin.previewed.isEmpty())
        assertNull(vm.state.value.message)
        val consent = vm.state.value.consent
        assertNotNull(consent)
        assertEquals("fakesrc", consent!!.nuvioOrigin?.scraperId)
        assertEquals(listOf("api.themoviedb.org", "fakesrc.example"), consent.manifest.hosts)
    }

    @Test fun `reinstalling a normal plugin still goes through the generic preview`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvioInstaller(emptyMap()))
        vm.reinstall(installedPlugin)
        assertEquals(listOf("o/r"), admin.previewed)
        assertEquals(preview, vm.state.value.consent)
    }

    @Test fun `cancelling the scraper picker clears it without previewing anything`() {
        val admin = FakeAdmin()
        val nuvio = nuvioInstaller(mapOf("https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson))
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/nuvio-repo")
        vm.add()
        vm.cancelNuvioPicker()
        assertNull(vm.state.value.nuvioPicker)
        assertNull(vm.state.value.consent)
    }

    @Test fun `Back while a scraper is still converting cancels it, so no consent sheet pops up later`() {
        val admin = FakeAdmin()
        val gate = CompletableDeferred<Unit>()
        val nuvio = nuvioInstaller(
            mapOf(
                "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifestJson,
                "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to nuvioScraperJs,
            ),
            gate,
        )
        val vm = PluginsViewModel(admin, io = dispatcher, nuvioPluginInstaller = nuvio)
        vm.onAddressChange("owner/nuvio-repo")
        vm.add()
        vm.pickNuvioScraper("fakesrc")
        assertTrue(vm.state.value.busy)
        vm.cancelNuvioPicker()
        gate.complete(Unit)
        assertNull(vm.state.value.nuvioPicker)
        assertNull(vm.state.value.consent)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.message)
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

    // ---- A new install that still needs setup opens its Configurar straight away ----

    /** [configurable] as the registry reports it right after installing it: its required settings still empty. */
    private val needingSetup = configurable.copy(missingSettings = listOf("server", "password"))
    private val setupPreview = preview.copy(manifest = configurable.manifest)

    @Test fun `installing a plugin that needs setup opens its Configurar and keeps the installed message`() {
        val admin = FakeAdmin().apply { previewResult = { setupPreview }; form = PluginSettingsForm(needingSetup, emptyMap()); settingsOnlyAfterInstall = true }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        val draft = vm.state.value.configuring!!
        assertEquals("demo", draft.pluginId)
        assertEquals("Demo", draft.pluginName)
        assertEquals(mapOf("server" to "", "password" to "", "hd" to false), draft.values)
        with(vm.state.value) {
            assertEquals("Demo quedó instalado", message)
            assertEquals("", address)
            assertFalse(busy)
        }
    }

    @Test fun `the settings are read after the install, when the registry knows the plugin`() {
        // The real admin answers null for a plugin it has not installed yet, so a read placed before the install
        // would silently never open Configurar. This fake behaves the same way.
        val admin = FakeAdmin().apply { previewResult = { setupPreview }; form = PluginSettingsForm(needingSetup, emptyMap()); settingsOnlyAfterInstall = true }
        val vm = vm(admin)
        assertNull(runBlocking { admin.settingsOf("demo") })
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        assertEquals(listOf(setupPreview), admin.installed)
        assertEquals("demo", vm.state.value.configuring?.pluginId)
    }

    @Test fun `installing a plugin with nothing missing does not open Configurar`() {
        val admin = FakeAdmin().apply { previewResult = { setupPreview }; form = PluginSettingsForm(configurable, emptyMap()); settingsOnlyAfterInstall = true }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        assertNull(vm.state.value.configuring)
        assertEquals("Demo quedó instalado", vm.state.value.message)
    }

    @Test fun `updating a plugin that needs setup never opens Configurar`() {
        val update = setupPreview.copy(isUpdate = true)
        val admin = FakeAdmin().apply { this.update = { UpdateOutcome.NeedsApproval(update) }; form = PluginSettingsForm(needingSetup, emptyMap()); settingsOnlyAfterInstall = true }
        val vm = vm(admin)
        vm.checkUpdate("demo"); vm.confirmInstall()
        assertEquals(listOf(update), admin.installed)
        assertNull(vm.state.value.configuring)
        assertEquals("Demo quedó actualizado a la 1.0.0", vm.state.value.message)
    }

    @Test fun `a plugin that vanished right after installing leaves the installed message and no dialog`() {
        val admin = FakeAdmin().apply { previewResult = { setupPreview }; form = null }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        with(vm.state.value) {
            assertNull(configuring)
            assertEquals("Demo quedó instalado", message)
            assertFalse(settingsClosed)
        }
    }

    @Test fun `a settings read that throws after installing leaves the installed message and no dialog`() {
        val admin = FakeAdmin().apply { previewResult = { setupPreview }; settingsFailure = java.io.IOException("keystore") }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        with(vm.state.value) {
            assertNull(configuring)
            assertEquals("Demo quedó instalado", message)
            assertFalse(busy)
        }
    }

    @Test fun `a failed install does not open Configurar`() {
        val admin = FakeAdmin().apply {
            previewResult = { setupPreview }
            installResult = { throw PluginTimeoutException("search", 15_000) }
            form = PluginSettingsForm(needingSetup, emptyMap())
        }
        val vm = vm(admin)
        vm.onAddressChange("o/r"); vm.add(); vm.confirmInstall()
        assertNull(vm.state.value.configuring)
        assertEquals("El plugin no respondió a tiempo", vm.state.value.message)
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
        // Only Configurar builds it this way: Ajustes ▸ Plugins passes both providers (see below) and
        // downloads the catalog and art on first open.
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
        // Two DIFFERENT addresses that both fail to parse must not match through their shared "no canonical form".
        assertNull(installedIdFor("a b/c", "x y/z"))
        assertNull(installedIdFor("x y/z", "a/b"))
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

    // ---- Art of the recommended plugins ----

    private class FakeArtProvider(private val onDisk: Map<String, CatalogArt> = emptyMap()) : CatalogArtProvider {
        val cachedCalls = mutableListOf<String>()
        val refreshCalls = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var cachedFailure: Exception? = null
        var retryFailedCalls = 0
        var retryFailedFailure: Exception? = null

        /** "refresh:<repo>" and "retryFailed", in the order they were called. */
        val events = mutableListOf<String>()

        /** What [refresh] does for a repo; the default answers null at once. */
        var onRefresh: suspend (repo: String) -> CatalogArt? = { null }

        override fun cached(repo: String): CatalogArt? {
            cachedCalls += repo
            cachedFailure?.let { throw it }
            return onDisk[repo]
        }

        override suspend fun refresh(repo: String): CatalogArt? {
            refreshCalls += repo
            events += "refresh:$repo"
            try {
                return onRefresh(repo)
            } catch (e: CancellationException) {
                cancelled += repo
                throw e
            }
        }

        override fun retryFailed() {
            retryFailedCalls++
            events += "retryFailed"
            retryFailedFailure?.let { throw it }
        }
    }

    private val alfaArt = CatalogArt("#112233", null)
    private val betaArt = CatalogArt("#445566", null)
    private val gammaArt = CatalogArt("#778899", null)
    private fun threeEntries() = arrayOf(entry("alfa", "o/alfa"), entry("beta", "o/beta"), entry("gamma", "o/gamma"))

    private fun vmWithArt(catalog: CatalogProvider, provider: CatalogArtProvider, io: CoroutineDispatcher = dispatcher) =
        PluginsViewModel(FakeAdmin(), io = io, catalogProvider = catalog, artProvider = provider)

    @Test fun `the first art already holds the disk copy of every visible row, before any refresh answers`() {
        val never = CompletableDeferred<CatalogArt?>()
        val provider = FakeArtProvider(mapOf("o/alfa" to alfaArt, "o/beta" to betaArt)).apply { onRefresh = { never.await() } }
        val vm = vmWithArt(FakeCatalog(*threeEntries()), provider)
        assertEquals(mapOf("o/alfa" to alfaArt, "o/beta" to betaArt), vm.art.value)
        assertEquals(setOf("o/alfa", "o/beta", "o/gamma"), provider.cachedCalls.toSet())
    }

    @Test fun `a refresh is started once for each visible row's repo`() {
        val provider = FakeArtProvider()
        vmWithArt(FakeCatalog(*threeEntries()), provider)
        // FakeCatalog answers the same rows on disk and from the download: nothing may be asked twice.
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma"), provider.refreshCalls)
    }

    @Test fun `each refresh lands as it arrives, without waiting for the others`() {
        val gates = mapOf("o/alfa" to CompletableDeferred<CatalogArt?>(), "o/beta" to CompletableDeferred<CatalogArt?>())
        val provider = FakeArtProvider().apply { onRefresh = { repo -> gates.getValue(repo).await() } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider)
        assertTrue(vm.art.value.isEmpty())
        gates.getValue("o/beta").complete(betaArt)
        assertEquals(mapOf("o/beta" to betaArt), vm.art.value)
        gates.getValue("o/alfa").complete(alfaArt)
        assertEquals(mapOf("o/alfa" to alfaArt, "o/beta" to betaArt), vm.art.value)
    }

    @Test fun `a repo with no art is absent from the map, not a null value`() {
        val provider = FakeArtProvider().apply { onRefresh = { repo -> if (repo == "o/alfa") alfaArt else null } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider)
        assertEquals(setOf("o/alfa"), vm.art.value.keys)
        assertFalse(vm.art.value.containsKey("o/beta"))
    }

    @Test fun `the refreshed art replaces the disk copy`() {
        val fresh = CatalogArt("#AABBCC", null)
        val provider = FakeArtProvider(mapOf("o/alfa" to alfaArt)).apply { onRefresh = { fresh } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        assertEquals(mapOf("o/alfa" to fresh), vm.art.value)
    }

    @Test fun `a refresh that answers null keeps the disk copy on screen`() {
        val provider = FakeArtProvider(mapOf("o/alfa" to alfaArt))
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
        assertEquals(mapOf("o/alfa" to alfaArt), vm.art.value)
    }

    @Test fun `rows that arrive with the download are refreshed, the ones already requested are not`() {
        val gate = CompletableDeferred<CatalogResult>()
        val disk = CatalogResult(PluginCatalog(listOf(entry("alfa", "o/alfa"))), CatalogOrigin.CACHE)
        val provider = FakeArtProvider().apply { onRefresh = { repo -> if (repo == "o/gamma") gammaArt else null } }
        val vm = vmWithArt(ScriptedCatalog(disk) { gate.await() }, provider)
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
        gate.complete(result(CatalogOrigin.FRESH, "alfa", "beta", "gamma"))
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma"), provider.refreshCalls)
        assertEquals(mapOf("o/gamma" to gammaArt), vm.art.value)
    }

    @Test fun `only the rows the search lists are refreshed, and the others when they show up`() {
        val gate = CompletableDeferred<CatalogResult>()
        val provider = FakeArtProvider()
        val vm = vmWithArt(ScriptedCatalog(emptySeed) { gate.await() }, provider)
        vm.onQueryChange("beta")
        gate.complete(result(CatalogOrigin.FRESH, "alfa", "beta", "gamma"))
        assertEquals(listOf("o/beta"), provider.refreshCalls)
        vm.onQueryChange("")
        assertEquals(listOf("o/beta", "o/alfa", "o/gamma"), provider.refreshCalls)
    }

    @Test fun `Reintentar requests the rows it adds and none of the ones that already have art`() {
        var loads = 0
        val provider = FakeArtProvider().apply { onRefresh = { repo -> if (repo == "o/gamma") null else alfaArt } }
        val catalog = ScriptedCatalog(result(CatalogOrigin.SEED, "alfa")) {
            if (loads++ == 0) result(CatalogOrigin.FRESH, "alfa", "beta") else result(CatalogOrigin.FRESH, "alfa", "beta", "gamma")
        }
        val vm = vmWithArt(catalog, provider)
        assertEquals(listOf("o/alfa", "o/beta"), provider.refreshCalls)
        vm.reloadCatalog()
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma"), provider.refreshCalls)
    }

    @Test fun `a repo whose refresh answered null is not asked again when its row comes back`() {
        val provider = FakeArtProvider()
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        vm.onQueryChange("nothing matches this")
        vm.onQueryChange("")
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
    }

    // ---- Reintentar also retries the art ----

    /** A provider that is offline for the first answer of each repo and online after that. */
    private fun offlineThenOnline() = FakeArtProvider().apply {
        val answered = HashMap<String, Int>()
        onRefresh = { repo -> if (answered.merge(repo, 1, Int::plus) == 1) null else CatalogArt("#112233", null) }
    }

    @Test fun `offline at first and online at Reintentar, the art of the listed rows arrives`() {
        val provider = offlineThenOnline()
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider)
        assertTrue(vm.art.value.isEmpty())

        vm.reloadCatalog()

        assertEquals(setOf("o/alfa", "o/beta"), vm.art.value.keys)
        assertEquals(listOf("o/alfa", "o/beta", "o/alfa", "o/beta"), provider.refreshCalls)
    }

    @Test fun `Reintentar tells the provider to forget its failures before it asks again`() {
        val provider = offlineThenOnline()
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        assertEquals(0, provider.retryFailedCalls)

        vm.reloadCatalog()

        assertEquals(1, provider.retryFailedCalls)
        assertEquals(listOf("refresh:o/alfa", "retryFailed", "refresh:o/alfa"), provider.events)
    }

    @Test fun `Reintentar does not request again a repo that already has art, from the disk or from a refresh`() {
        val provider = FakeArtProvider(mapOf("o/alfa" to alfaArt)).apply {
            onRefresh = { repo -> if (repo == "o/beta") betaArt else null }
        }
        val vm = vmWithArt(FakeCatalog(*threeEntries()), provider)
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma"), provider.refreshCalls)

        vm.reloadCatalog()

        // Only gamma has no art: alfa (disk copy) and beta (refreshed) are left alone.
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma", "o/gamma"), provider.refreshCalls)
        assertEquals(mapOf("o/alfa" to alfaArt, "o/beta" to betaArt), vm.art.value)
    }

    @Test fun `Reintentar does not request twice a repo whose request is still in flight, but does retry the others`() {
        val gate = CompletableDeferred<CatalogArt?>()
        val provider = FakeArtProvider().apply { onRefresh = { repo -> if (repo == "o/alfa") gate.await() else null } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider)
        assertEquals(listOf("o/alfa", "o/beta"), provider.refreshCalls)

        vm.reloadCatalog()
        vm.reloadCatalog()

        // Alfa is still being asked: never twice at the same time. Beta ended with nothing: once per Reintentar.
        assertEquals(listOf("o/alfa", "o/beta", "o/beta", "o/beta"), provider.refreshCalls)
        gate.complete(alfaArt)
        assertEquals(mapOf("o/alfa" to alfaArt), vm.art.value)
        assertEquals(listOf("o/alfa", "o/beta", "o/beta", "o/beta"), provider.refreshCalls)
    }

    @Test fun `a repo that answered null is asked again by Reintentar and by nothing else`() {
        val provider = FakeArtProvider()
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        vm.onQueryChange("nothing matches this")
        vm.onQueryChange("")
        assertEquals(1, provider.refreshCalls.size)

        vm.reloadCatalog()
        assertEquals(2, provider.refreshCalls.size)
        vm.onQueryChange("nothing matches this")
        vm.onQueryChange("")
        assertEquals(2, provider.refreshCalls.size)

        vm.reloadCatalog()
        assertEquals(3, provider.refreshCalls.size)
    }

    @Test fun `Reintentar forgets the rows the search hides too, and they are requested when they come back`() {
        val provider = FakeArtProvider()
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider)
        vm.onQueryChange("alfa")

        vm.reloadCatalog()
        // Only the listed row is requested now.
        assertEquals(listOf("o/alfa", "o/beta", "o/alfa"), provider.refreshCalls)

        vm.onQueryChange("")
        assertEquals(listOf("o/alfa", "o/beta", "o/alfa", "o/beta"), provider.refreshCalls)
    }

    @Test fun `a provider whose retryFailed throws does not stop Reintentar from reloading the list and the art`() {
        val provider = offlineThenOnline().apply { retryFailedFailure = IllegalStateException("boom") }
        val catalog = FakeCatalog(entry("alfa", "o/alfa"))
        val vm = vmWithArt(catalog, provider)

        vm.reloadCatalog()

        assertEquals(listOf(false, true), catalog.forceFlags)
        assertEquals(setOf("o/alfa"), vm.art.value.keys)
    }

    @Test fun `a repo still being refreshed is not requested again when its row comes back`() {
        val gate = CompletableDeferred<CatalogArt?>()
        val provider = FakeArtProvider().apply { onRefresh = { gate.await() } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        // The first request is still pending while the row leaves (search), comes back, and Reintentar reloads.
        vm.onQueryChange("nothing matches this")
        vm.onQueryChange("")
        vm.reloadCatalog()
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
        assertTrue(vm.art.value.isEmpty())
        gate.complete(alfaArt)
        assertEquals(mapOf("o/alfa" to alfaArt), vm.art.value)
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
    }

    @Test fun `clearing the view model cancels the refreshes still running`() {
        val never = CompletableDeferred<CatalogArt?>()
        val provider = FakeArtProvider().apply { onRefresh = { never.await() } }
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                vmWithArt(FakeCatalog(entry("alfa", "o/alfa"), entry("beta", "o/beta")), provider) as T
        }
        val vm = ViewModelProvider(store, factory)[PluginsViewModel::class.java]
        assertEquals(listOf("o/alfa", "o/beta"), provider.refreshCalls)
        assertTrue(provider.cancelled.isEmpty())
        store.clear()
        assertEquals(setOf("o/alfa", "o/beta"), provider.cancelled.toSet())
        // A late answer after the screen is gone lands nowhere.
        never.complete(alfaArt)
        assertTrue(vm.art.value.isEmpty())
    }

    @Test fun `every refresh runs on the io dispatcher, never on the thread that built the view model`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "art-io") }
        try {
            val ran = CompletableDeferred<String>()
            val provider = FakeArtProvider().apply { onRefresh = { ran.complete(Thread.currentThread().name); alfaArt } }
            val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider, io = executor.asCoroutineDispatcher())
            // Coroutine debug mode appends " @coroutine#N" to the name of the thread a coroutine runs on.
            val thread = runBlocking { withTimeout(10_000) { ran.await() } }
            assertTrue("ran on $thread", thread.startsWith("art-io"))
            assertEquals(mapOf("o/alfa" to alfaArt), runBlocking { withTimeout(10_000) { vm.art.first { it.isNotEmpty() } } })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `a refresh queued on io does not delay the first art from the disk`() {
        val queued = StandardTestDispatcher()
        val provider = FakeArtProvider(mapOf("o/alfa" to alfaArt))
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider, io = queued)
        assertEquals(mapOf("o/alfa" to alfaArt), vm.art.value)
        assertTrue(provider.refreshCalls.isEmpty())
        queued.scheduler.runCurrent()
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
    }

    @Test fun `a provider whose refresh throws leaves the state as it was, and the other rows still land`() {
        val provider = FakeArtProvider(mapOf("o/beta" to betaArt)).apply {
            onRefresh = { repo ->
                when (repo) {
                    "o/beta" -> throw IllegalStateException("boom")
                    "o/gamma" -> throw CancellationException("gone")
                    else -> alfaArt
                }
            }
        }
        val vm = vmWithArt(FakeCatalog(*threeEntries()), provider)
        assertEquals(mapOf("o/alfa" to alfaArt, "o/beta" to betaArt), vm.art.value)
        assertEquals(listOf("o/alfa", "o/beta", "o/gamma"), provider.refreshCalls)
    }

    // runTest fails the test on an exception that escapes a coroutine started inside it; a plain JVM test
    // would only see it reach the thread's uncaught handler, while on a device it kills the process.
    @Test fun `a provider whose refresh throws does not crash`() = runTest {
        val provider = FakeArtProvider().apply { onRefresh = { throw IllegalStateException("boom") } }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        assertEquals(listOf("o/alfa"), provider.refreshCalls)
        assertTrue(vm.art.value.isEmpty())
    }

    @Test fun `a provider whose disk read throws gives a view model with no art yet, and the refresh still lands`() {
        val provider = FakeArtProvider().apply {
            cachedFailure = IllegalStateException("disk")
            onRefresh = { alfaArt }
        }
        val vm = vmWithArt(FakeCatalog(entry("alfa", "o/alfa")), provider)
        assertEquals(mapOf("o/alfa" to alfaArt), vm.art.value)
    }

    @Test fun `a view model built without an art provider shows no art, and the default provider knows none`() {
        val vm = PluginsViewModel(FakeAdmin(), io = dispatcher, catalogProvider = FakeCatalog(*threeEntries()))
        assertTrue(vm.art.value.isEmpty())
        assertNull(NoCatalogArt.cached("o/alfa"))
        assertNull(runBlocking { NoCatalogArt.refresh("o/alfa") })
        NoCatalogArt.retryFailed()
        // Only Configurar passes neither provider (Ajustes ▸ Plugins passes both): no rows are listed, so
        // nothing is ever asked.
        val plain = PluginsViewModel(FakeAdmin(), io = dispatcher)
        assertTrue(plain.catalog.value.rows.isEmpty())
        assertTrue(plain.art.value.isEmpty())
    }

    private class FakeDiscovery(
        private val onDisk: DiscoveryResult = DiscoveryResult.NONE,
        private val download: suspend (force: Boolean) -> DiscoveryResult,
    ) : PluginDiscoveryProvider {
        val forceFlags = mutableListOf<Boolean>()
        var cachedReads = 0
        override fun cached(): DiscoveryResult { cachedReads++; return onDisk }
        override suspend fun load(force: Boolean): DiscoveryResult { forceFlags += force; return download(force) }
    }

    private val xuper = DiscoveredPlugin("kinotvapp", "kino-plugin-xuper", "xuper", "Xuper", "Películas y series", 5)

    @Test fun `community plugins come after the catalog, marked, deduped and marked installed`() {
        val archive = entry("internet-archive", "kinotvapp/kino-plugin-archive")
        val found = listOf(
            DiscoveredPlugin("KinoTvApp", "Kino-Plugin-Archive", "archive-org", "Internet Archive", "", 9),
            xuper,
            DiscoveredPlugin("o", "r", "demo", "Demo", "", 1),
        )
        val admin = FakeAdmin().apply { plugins.value = listOf(installedPlugin) }
        val vm = PluginsViewModel(admin, io = dispatcher, catalogProvider = FakeCatalog(archive), discovery = FakeDiscovery { DiscoveryResult(found, DiscoveryOrigin.FRESH) })
        val rows = vm.community.value.rows
        assertEquals(listOf("kinotvapp/kino-plugin-xuper", "o/r"), rows.map { it.entry.repo })
        assertTrue(rows.all { it.community })
        assertNull(rows[0].installed)
        assertEquals(installedPlugin, rows[1].installed)
        assertEquals(listOf(listOf("por kinotvapp"), listOf("por o")), rows.map { it.entry.tags })
        assertFalse(vm.community.value.loading)
        assertFalse(vm.catalog.value.rows.any { it.community })
    }

    @Test fun `a Xuper installed from the legacy address shows the discovered official Xuper as installed`() {
        val legacy = InstalledPlugin(manifest.copy(id = "xuper"), InstalledRecord("kinotvapp/kino-plugin-xuper", "1.0.0", "x", emptyList(), 0L), null)
        val official = DiscoveredPlugin("xuper-plugin", "kino-plugin-xuper", "xuper", "Xuper", "", 5)
        val admin = FakeAdmin().apply { plugins.value = listOf(legacy) }
        val vm = PluginsViewModel(admin, io = dispatcher, discovery = FakeDiscovery { DiscoveryResult(listOf(official), DiscoveryOrigin.FRESH) })
        val row = vm.community.value.rows.single()
        assertEquals("xuper-plugin/kino-plugin-xuper", row.entry.repo)
        assertEquals(legacy, row.installed)
        assertEquals(catalogActionOf(CatalogRow(row.entry, legacy)), catalogActionOf(row))
        assertFalse(catalogActionOf(row) == CatalogAction.INSTALL)
    }

    @Test fun `community state tells a search whose finds are all in Recomendados from one that found nothing`() {
        val archive = entry("internet-archive", "kinotvapp/kino-plugin-archive")
        val found = listOf(DiscoveredPlugin("KinoTvApp", "Kino-Plugin-Archive", "internet-archive", "Internet Archive", "", 9))
        val all = communityUiState(DiscoveryResult(found, DiscoveryOrigin.FRESH), false, listOf(archive), "", emptyList())
        assertTrue(all.rows.isEmpty())
        assertTrue(all.allRecommended)
        assertFalse(communityUiState(DiscoveryResult.NONE, false, listOf(archive), "", emptyList()).allRecommended)
        val impostor = listOf(DiscoveredPlugin("evil", "xuper", "xuper", "Xuper", "", 9))
        assertFalse(communityUiState(DiscoveryResult(impostor, DiscoveryOrigin.FRESH), false, listOf(archive), "", emptyList()).allRecommended)
    }

    // Fix round 1: a damaged plugin is reinstalled from the address it was installed from, never from the
    // row's catalog address: a legacy Xuper (kinotvapp) on a row naming the new repo would be refused on the id clash.
    @Test fun `reinstalling a damaged legacy Xuper from its catalog row previews the legacy address`() {
        val legacy = InstalledPlugin(
            manifest.copy(id = "xuper"),
            InstalledRecord("kinotvapp/kino-plugin-xuper", "1.0.0", "x", emptyList(), 0L, damaged = true),
            null,
        )
        val admin = FakeAdmin().apply { plugins.value = listOf(legacy); previewResult = { preview } }
        val vm = vm(admin, FakeCatalog(entry("xuper", "xuper-plugin/kino-plugin-xuper")))
        val row = vm.catalog.value.rows.single()
        assertEquals(legacy, row.installed)
        runCatalogAction(vm, row)
        assertEquals(listOf("kinotvapp/kino-plugin-xuper"), admin.previewed)
        assertEquals(preview, vm.state.value.consent)
    }

    @Test fun `reinstalling a damaged plugin with a ref from the picker previews exactly its installed address`() {
        val broken = InstalledPlugin(manifest, InstalledRecord("o/r@dev", "1.0.0", "x", emptyList(), 0L, damaged = true), null)
        val admin = FakeAdmin().apply { plugins.value = listOf(broken); previewResult = { preview } }
        val vm = vm(admin)
        runCatalogAction(vm, pickerInstalledRows(listOf(broken), emptyList()).single())
        assertEquals(listOf("o/r@dev"), admin.previewed)
    }

    @Test fun `installing a community plugin goes through the same preview and consent`() {
        val admin = FakeAdmin().apply { previewResult = { preview } }
        val vm = PluginsViewModel(admin, io = dispatcher, discovery = FakeDiscovery { DiscoveryResult(listOf(xuper), DiscoveryOrigin.FRESH) })
        vm.installFromCatalog(vm.community.value.rows.single().entry)
        assertEquals(listOf("kinotvapp/kino-plugin-xuper"), admin.previewed)
        assertEquals(preview, vm.state.value.consent)
        assertTrue(admin.installed.isEmpty())
    }

    @Test fun `opening searches when due, Actualizar forces a search`() {
        val discovery = FakeDiscovery { DiscoveryResult.NONE }
        val vm = PluginsViewModel(FakeAdmin(), io = dispatcher, discovery = discovery)
        vm.refreshCommunity()
        assertEquals(listOf(false, true), discovery.forceFlags)
    }

    @Test fun `a discovery that throws leaves the catalog alone and ends its own loading`() {
        val vm = PluginsViewModel(
            FakeAdmin(), io = dispatcher, catalogProvider = FakeCatalog(entry("a", "o/a")),
            discovery = FakeDiscovery { throw IOException("offline") },
        )
        assertEquals(listOf("o/a"), vm.catalog.value.rows.map { it.entry.repo })
        with(vm.community.value) {
            assertFalse(loading)
            assertFalse(refreshing)
            assertTrue(rows.isEmpty())
        }
    }

    @Test fun `the disk copy of the community list is there from the first frame while the search runs`() {
        val gate = CompletableDeferred<DiscoveryResult>()
        val vm = PluginsViewModel(
            FakeAdmin(), io = dispatcher,
            discovery = FakeDiscovery(DiscoveryResult(listOf(xuper), DiscoveryOrigin.CACHE)) { gate.await() },
        )
        assertEquals(listOf("kinotvapp/kino-plugin-xuper"), vm.community.value.rows.map { it.entry.repo })
        assertFalse(vm.community.value.loading)
        assertTrue(vm.community.value.refreshing)
        gate.complete(DiscoveryResult.NONE)
    }

    @Test fun `the community disk copy is read on io, never while the screen composes`() {
        val io = kotlinx.coroutines.test.StandardTestDispatcher(dispatcher.scheduler)
        val gate = CompletableDeferred<DiscoveryResult>()
        val discovery = FakeDiscovery(DiscoveryResult(listOf(xuper), DiscoveryOrigin.CACHE)) { gate.await() }
        val vm = PluginsViewModel(FakeAdmin(), io = io, discovery = discovery)
        // First frame: nothing read yet, shown as loading (never as an error or an empty result).
        assertEquals(0, discovery.cachedReads)
        assertTrue(vm.community.value.loading)
        assertTrue(vm.community.value.rows.isEmpty())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, discovery.cachedReads)
        assertEquals(listOf("kinotvapp/kino-plugin-xuper"), vm.community.value.rows.map { it.entry.repo })
        assertTrue(vm.community.value.refreshing)
        gate.complete(DiscoveryResult.NONE)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun `the search box filters community rows too`() {
        val vm = PluginsViewModel(FakeAdmin(), io = dispatcher, discovery = FakeDiscovery { DiscoveryResult(listOf(xuper), DiscoveryOrigin.FRESH) })
        vm.onQueryChange("zzz")
        assertTrue(vm.community.value.rows.isEmpty())
        vm.onQueryChange("xup")
        assertEquals(1, vm.community.value.rows.size)
    }

    @Test fun `community rows get their art requested like catalog rows`() {
        val asked = mutableListOf<String>()
        val art = object : CatalogArtProvider {
            override fun cached(repo: String): CatalogArt? = null
            override suspend fun refresh(repo: String): CatalogArt? { asked += repo; return null }
            override fun retryFailed() = Unit
        }
        PluginsViewModel(
            FakeAdmin(), io = dispatcher, catalogProvider = FakeCatalog(entry("a", "o/a")), artProvider = art,
            discovery = FakeDiscovery { DiscoveryResult(listOf(xuper), DiscoveryOrigin.FRESH) },
        )
        assertTrue("o/a" in asked && "kinotvapp/kino-plugin-xuper" in asked)
    }
}
