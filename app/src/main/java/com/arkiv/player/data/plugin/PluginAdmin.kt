package com.arkiv.player.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Everything Ajustes ▸ Plugins does, behind one interface the ViewModel can fake. */
interface PluginAdmin {
    val plugins: StateFlow<List<InstalledPlugin>>
    suspend fun preview(input: String): InstallPreview
    suspend fun install(preview: InstallPreview)
    suspend fun checkUpdate(id: String): UpdateOutcome
    fun setEnabled(id: String, enabled: Boolean)
    fun uninstall(id: String)
}

class DefaultPluginAdmin(
    private val registry: PluginRegistry,
    private val installer: PluginInstaller,
    private val runtimes: PluginRuntimePool,
) : PluginAdmin {
    override val plugins: StateFlow<List<InstalledPlugin>> get() = registry.plugins

    override suspend fun preview(input: String): InstallPreview = withContext(Dispatchers.IO) { installer.preview(input) }

    override suspend fun install(preview: InstallPreview) {
        withContext(Dispatchers.IO) { installer.install(preview) }
        // Reload first: a call landing between these two would otherwise open the new script
        // against the old registry entry (old approved hosts) and keep it until idle close.
        registry.reload()
        runtimes.close(preview.manifest.id)
    }

    override suspend fun checkUpdate(id: String): UpdateOutcome {
        val outcome = withContext(Dispatchers.IO) { installer.checkUpdate(id) }
        registry.reload()
        if (outcome is UpdateOutcome.Applied) runtimes.close(id)
        return outcome
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        runtimes.close(id)
    }

    override fun uninstall(id: String) {
        registry.uninstall(id)
        runtimes.close(id)
    }
}
