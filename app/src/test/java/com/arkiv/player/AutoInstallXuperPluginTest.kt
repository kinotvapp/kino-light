package com.arkiv.player

import com.arkiv.player.data.credentials.RemoteCredentials
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginRegistry
import com.arkiv.player.data.plugin.PluginSettingsForm
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * In-memory [RemoteCredentialsStore]: mirrors `FakeRemoteCredentialsStore` in
 * `data/credentials/SeedRefresherTest.kt` (itself mirroring `FakeCredentialStore` in
 * `data/magis`) -- its own small private copy per test file, the established convention here
 * rather than a shared fake.
 */
private class FakeRemoteCredentialsStore(initial: RemoteCredentials? = null) : RemoteCredentialsStore {
    private var current: RemoteCredentials? = initial
    override fun read(): RemoteCredentials? = current
    override fun save(credentials: RemoteCredentials) { current = credentials }
    override fun clear() { current = null }
}

private fun sampleCredentials() = RemoteCredentials(
    activationBlob = "blob123",
    iptvHosts = "host1.com,host2.com",
    iptvAppId = "appId",
    iptvApkVersion = "1.2.3",
    tmdbApiKey = "tmdbKey",
)

/**
 * Records every [PluginAdmin.install] this migration actually commits, keyed by the previewed
 * address -- mirrors `FakeAdmin`'s style in `ui/plugin/PluginsViewModelTest.kt`. Every other
 * member is unused by this migration and kept trivial.
 */
private class RecordingPluginAdmin : PluginAdmin {
    override val plugins = MutableStateFlow(emptyList<InstalledPlugin>())
    val previewedInputs = mutableListOf<String>()
    val installedAddresses = mutableListOf<String>()

    override suspend fun preview(input: String): InstallPreview {
        previewedInputs += input
        val address = PluginAddress.parse(input)!!
        val manifest = PluginManifest(
            id = "xuper", name = "Xuper", version = "1.0.0", apiVersion = 1, entry = "plugin.js",
            description = "", author = "", homepage = "", hosts = emptyList(),
            capabilities = setOf("search", "home", "browse", "episodes", "resolve"),
            color = null, icon = null,
        )
        return InstallPreview(address, manifest, "{}", isUpdate = false, newHosts = emptyList())
    }

    override suspend fun install(preview: InstallPreview) { installedAddresses += preview.address.canonical }
    override suspend fun checkUpdate(id: String): UpdateOutcome = UpdateOutcome.UpToDate
    override fun setEnabled(id: String, enabled: Boolean) {}
    override fun uninstall(id: String) {}
    override suspend fun settingsOf(id: String): PluginSettingsForm? = null
    override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? = null
}

class AutoInstallXuperPluginTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    /**
     * Installs a plugin straight to disk, the same way `PluginRegistryTest`'s own helper does,
     * bypassing [com.arkiv.player.data.plugin.PluginInstaller] entirely: this test is about the
     * registry lookup [autoInstallXuperPluginIfNeeded] does, not the install pipeline itself.
     * [address] defaults to something other than Xuper's.
     */
    private fun install(id: String, address: String = "o/$id", enabled: Boolean = true) {
        val manifest = JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(
            staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L, enabled = enabled),
        )
        store.commit(staging, id)
        registry.reload()
    }

    @Test fun `an already-activated person gets the Xuper plugin installed automatically`() = runTest {
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        assertEquals(listOf(XuperPrivilege.SOURCE_REPO), admin.previewedInputs)
        assertEquals(listOf(XuperPrivilege.SOURCE_REPO), admin.installedAddresses)
    }

    @Test fun `nothing happens for someone who was never activated`() = runTest {
        val credentials = FakeRemoteCredentialsStore(initial = null)
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        assertTrue(admin.previewedInputs.isEmpty())
        assertTrue(admin.installedAddresses.isEmpty())
    }

    @Test fun `nothing happens if the Xuper plugin is already installed`() = runTest {
        install("xuper", address = XuperPrivilege.SOURCE_REPO)
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        assertTrue(admin.installedAddresses.isEmpty())
    }

    @Test fun `an installed-but-disabled Xuper plugin still counts as already there, so it is not re-previewed`() = runTest {
        install("xuper", address = XuperPrivilege.SOURCE_REPO, enabled = false)
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        assertTrue(admin.previewedInputs.isEmpty())
        assertTrue(admin.installedAddresses.isEmpty())
    }

    @Test fun `an installed plugin from a different repo never blocks Xuper's own install`() = runTest {
        install("archive") // some other plugin, unrelated address
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        assertEquals(listOf(XuperPrivilege.SOURCE_REPO), admin.installedAddresses)
    }

    /**
     * Fix round 1: a person who explicitly uninstalled the auto-installed Xuper plugin has no
     * live record left, same as someone who never had it migrated -- [PluginRegistry.plugins]
     * can't tell those apart. [PluginRegistry.uninstall] is what leaves the real signal, the
     * store's `removed.json` tombstone (see [PluginStore.removedName]); this must be honored
     * instead of silently reinstalling on the very next cold start.
     */
    @Test fun `a Xuper plugin explicitly uninstalled before is never silently reinstalled`() = runTest {
        install("xuper", address = XuperPrivilege.SOURCE_REPO)
        registry.uninstall("xuper")
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = true, credentials, registry, admin)

        // preview() still runs: it's the only way to learn Xuper's real manifest id, needed to
        // look up the tombstone at all -- the bug this test guards against is install() running.
        assertEquals(listOf(XuperPrivilege.SOURCE_REPO), admin.previewedInputs)
        assertTrue(admin.installedAddresses.isEmpty())
    }

    @Test fun `a new device never gets Xuper installed behind its back`() = runTest {
        val credentials = FakeRemoteCredentialsStore(sampleCredentials())
        val admin = RecordingPluginAdmin()

        autoInstallXuperPluginIfNeeded(runsMigration = false, credentials, registry, admin)

        assertTrue(admin.previewedInputs.isEmpty())
        assertTrue(admin.installedAddresses.isEmpty())
    }
}
