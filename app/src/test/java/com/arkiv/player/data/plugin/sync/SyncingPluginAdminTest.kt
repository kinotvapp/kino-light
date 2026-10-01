package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.PluginSettingsForm
import com.arkiv.player.data.plugin.UpdateOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncingPluginAdminTest {
    private val host = FakeSyncHost()
    private val dao = FakePluginInstallDao()

    /** The real work on [host]'s plugins, the way DefaultPluginAdmin does it on disk. */
    private inner class BaseAdmin : PluginAdmin {
        override val plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
        private fun refresh() { plugins.value = host.plugins.values.toList() }
        override suspend fun preview(input: String): InstallPreview = error("unused")
        override suspend fun install(preview: InstallPreview) { host.install(preview); refresh() }
        override suspend fun checkUpdate(id: String): UpdateOutcome = UpdateOutcome.Applied("1.1.0").also {
            host.plugins[id] = host.plugins.getValue(id).let { p -> p.copy(record = p.record.copy(version = "1.1.0")) }
        }
        override fun setEnabled(id: String, enabled: Boolean) { host.setEnabled(id, enabled); refresh() }
        override fun uninstall(id: String) { host.uninstall(id); refresh() }
        override fun forgetHostRejections(id: String) = Unit
        override fun revokeAnyVideoHost(id: String) = Unit
        override suspend fun settingsOf(id: String): PluginSettingsForm? = null
        override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? = null
    }

    @Test fun `the person's install, switch, update and uninstall are all recorded for their other devices`() = runTest {
        val after = mutableListOf<String>()
        val admin = SyncingPluginAdmin(BaseAdmin(), PluginSyncMirror(dao, host, backgroundScope, clock = { 100L }, log = {})) { after += it }
        admin.install(preview())
        runCurrent()
        assertTrue(dao.rows.getValue("archive").enabled)
        assertEquals(listOf("archive"), after)

        admin.setEnabled("archive", false)
        runCurrent()
        assertFalse(dao.rows.getValue("archive").enabled)

        admin.checkUpdate("archive")
        runCurrent()
        assertEquals("1.1.0", dao.rows.getValue("archive").version)

        admin.uninstall("archive")
        runCurrent()
        assertTrue(dao.rows.getValue("archive").deleted)
    }
}
