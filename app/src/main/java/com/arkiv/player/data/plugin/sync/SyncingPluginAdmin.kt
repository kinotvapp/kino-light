package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.UpdateOutcome

/**
 * The [PluginAdmin] every screen uses: [base] does the work, then the person's action is recorded in
 * `plugin_installs` ([PluginSyncMirror]) for their other devices. Sync's own changes go through [base]
 * directly ([DefaultPluginSyncHost]), so they are never recorded back.
 *
 * [afterInstall] runs once the install is recorded: plugin sync re-applies what the person's other
 * devices said about the plugin (approvals, shared settings) when it came from the
 * "Plugins de tus otros aparatos" list.
 */
class SyncingPluginAdmin(
    private val base: PluginAdmin,
    private val mirror: PluginSyncMirror,
    private val afterInstall: (String) -> Unit = {},
) : PluginAdmin by base {
    override suspend fun install(preview: InstallPreview) {
        base.install(preview)
        mirror.recordInstall(preview.manifest.id, afterInstall)
    }

    override suspend fun checkUpdate(id: String): UpdateOutcome {
        val outcome = base.checkUpdate(id)
        if (outcome is UpdateOutcome.Applied) mirror.recordVersion(id, withHash = true)
        return outcome
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        base.setEnabled(id, enabled)
        mirror.recordEnabled(id)
    }

    override fun uninstall(id: String) {
        val before = base.plugins.value.firstOrNull { it.id == id }
        base.uninstall(id)
        mirror.recordUninstall(id, before)
    }

    override fun forgetHostRejections(id: String) {
        base.forgetHostRejections(id)
        mirror.recordApprovals(id)
    }

    override fun revokeAnyVideoHost(id: String) {
        base.revokeAnyVideoHost(id)
        mirror.recordApprovals(id)
    }

    override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? {
        val error = base.saveSettings(id, values)
        if (error == null) mirror.recordSettings(id)
        return error
    }
}
