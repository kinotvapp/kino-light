package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.DefaultPluginAdmin
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioPluginInstaller
import com.arkiv.player.data.plugin.PluginConfigStore
import com.arkiv.player.data.plugin.PluginInstaller
import com.arkiv.player.data.plugin.PluginRegistry
import com.arkiv.player.data.plugin.UpdateOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What plugin sync does to this device's plugins, behind one interface the mirror and the reconciler
 * share (and tests fake). Everything here acts on THIS device only and never records a sync row: the
 * person's own actions are recorded by [SyncingPluginAdmin], never what sync itself does.
 */
interface PluginSyncHost {
    fun installed(id: String): InstalledPlugin?
    fun installedAll(): List<InstalledPlugin>

    /** The plugin's shared settings as stored here ([PluginConfigStore.sharedValues]). Disk IO. */
    suspend fun sharedSettings(plugin: InstalledPlugin): Map<String, Any>

    /**
     * Fetches what [row] names and builds the consent preview, exactly as an install by hand would:
     * a repo plugin from GitHub, a Nuvio one re-converted from its repo. Throws `InstallException`.
     */
    suspend fun preview(row: PluginInstallEntity): InstallPreview
    suspend fun install(preview: InstallPreview)
    fun setEnabled(id: String, enabled: Boolean)
    fun uninstall(id: String)
    suspend fun checkUpdate(id: String): UpdateOutcome

    /** Adds [reach]'s approved hosts and adopts its "no"s and its broad video permission ([PluginRegistry.applyPeerGrants]). */
    fun applyGrants(id: String, reach: PluginReach)

    /** Overlays [values] on the plugin's shared settings and refreshes it like a settings save. */
    suspend fun applySharedSettings(id: String, values: Map<String, Any>)
}

/** Production [PluginSyncHost]: the undecorated admin, so nothing sync does is recorded back as the person's action. */
class DefaultPluginSyncHost(
    private val registry: PluginRegistry,
    private val admin: DefaultPluginAdmin,
    private val installer: PluginInstaller,
    private val nuvio: NuvioPluginInstaller,
    private val config: PluginConfigStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginSyncHost {
    override fun installed(id: String): InstalledPlugin? = registry.find(id)
    override fun installedAll(): List<InstalledPlugin> = registry.plugins.value

    override suspend fun sharedSettings(plugin: InstalledPlugin): Map<String, Any> =
        withContext(io) { config.sharedValues(plugin.id, plugin.manifest.settings) }

    override suspend fun preview(row: PluginInstallEntity): InstallPreview = withContext(io) {
        val repo = row.nuvioRepo
        val scraper = row.nuvioScraperId
        if (repo != null && scraper != null) nuvio.previewScraper(repo, scraper) else installer.preview(row.address)
    }

    override suspend fun install(preview: InstallPreview) = admin.install(preview)
    override fun setEnabled(id: String, enabled: Boolean) = admin.setEnabled(id, enabled)
    override fun uninstall(id: String) = admin.uninstall(id)
    override suspend fun checkUpdate(id: String): UpdateOutcome = admin.checkUpdate(id)
    override fun applyGrants(id: String, reach: PluginReach) = registry.applyPeerGrants(id, reach.hosts, reach.rejectedHosts, reach.anyVideoHost)
    override suspend fun applySharedSettings(id: String, values: Map<String, Any>) { admin.applySharedSettings(id, values) }
}
