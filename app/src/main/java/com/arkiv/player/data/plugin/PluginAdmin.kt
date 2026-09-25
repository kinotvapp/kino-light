package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The Configurar screen's data: the plugin (its manifest's settings) and the current values, passwords included. */
data class PluginSettingsForm(val plugin: InstalledPlugin, val values: Map<String, Any>)

/** Everything Ajustes ▸ Plugins does, behind one interface the ViewModel can fake. */
interface PluginAdmin {
    val plugins: StateFlow<List<InstalledPlugin>>
    suspend fun preview(input: String): InstallPreview
    suspend fun install(preview: InstallPreview)
    suspend fun checkUpdate(id: String): UpdateOutcome
    fun setEnabled(id: String, enabled: Boolean)
    fun uninstall(id: String)

    /** Null when the plugin isn't installed any more. */
    suspend fun settingsOf(id: String): PluginSettingsForm?

    /** Null when saved; otherwise the Spanish reason nothing was saved. */
    suspend fun saveSettings(id: String, values: Map<String, Any?>): String?
}

/**
 * [forgetSession] runs after a settings change: AppGraph retires the plugin's cookie jar and
 * deletes its cookies and Home cache. Config and Keystore work runs on [io], never on Main.
 */
class DefaultPluginAdmin(
    private val registry: PluginRegistry,
    private val installer: PluginInstaller,
    private val runtimes: PluginRuntimePool,
    private val config: PluginConfigStore,
    private val forgetSession: (pluginId: String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginAdmin {
    override val plugins: StateFlow<List<InstalledPlugin>> get() = registry.plugins

    override suspend fun preview(input: String): InstallPreview = withContext(io) { installer.preview(input) }

    override suspend fun install(preview: InstallPreview) {
        withContext(io) { installer.install(preview) }
        // Reload first: a call landing between these two would otherwise open the new script
        // against the old registry entry (old approved hosts) and keep it until idle close.
        registry.reload()
        runtimes.close(preview.manifest.id)
    }

    override suspend fun checkUpdate(id: String): UpdateOutcome {
        val outcome = withContext(io) { installer.checkUpdate(id) }
        registry.reload()
        if (outcome is UpdateOutcome.Applied) runtimes.close(id)
        return outcome
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        runtimes.close(id)
    }

    /** Also forgets its settings, passwords included: a reinstall starts from "Falta configurar". */
    override fun uninstall(id: String) {
        val settings = registry.find(id)?.manifest?.settings.orEmpty()
        registry.uninstall(id)
        config.clear(id, settings)
        runtimes.close(id)
    }

    override suspend fun settingsOf(id: String): PluginSettingsForm? = withContext(io) {
        val p = registry.find(id) ?: return@withContext null
        PluginSettingsForm(p, config.read(id, p.manifest.settings).values)
    }

    override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? = withContext(io) {
        val p = registry.find(id) ?: return@withContext "El plugin ya no está instalado"
        config.save(id, p.manifest.settings, values)?.let { return@withContext it }
        // A new user or server must not inherit the old session (spec §1.3): the runtime closes
        // (the next call opens one with the new config) and the cookies go.
        runtimes.close(id)
        forgetSession(id)
        registry.reload()
        null
    }
}
