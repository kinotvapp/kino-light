package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.PluginInstallDao
import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.NuvioOrigin
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.UpdateOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun manifest(
    id: String = "archive",
    hosts: List<String> = listOf("archive.org"),
    caps: Set<String> = setOf("search", "resolve"),
    version: String = "1.0.0",
    streamHostsAny: Boolean = false,
    fetchHostsAny: Boolean = false,
    secrets: Map<String, String> = emptyMap(),
) = PluginManifest(
    id, id.replaceFirstChar { it.uppercase() }, version, 4, "plugin.js", "", "a", "", hosts, caps, null, null,
    streamHostsAny = streamHostsAny, fetchHostsAny = fetchHostsAny, secrets = secrets,
)

internal fun installed(
    id: String = "archive",
    address: String = "kinotvapp/kino-plugin-archive",
    hosts: List<String> = listOf("archive.org"),
    enabled: Boolean = true,
    version: String = "1.0.0",
    nuvioScraperId: String? = null,
    sha: String = "a".repeat(64),
) = InstalledPlugin(
    manifest(id, hosts, version = version),
    InstalledRecord(
        address, version, sha, hosts, 1L, enabled = enabled,
        nuvioRepo = if (nuvioScraperId != null) address else null, nuvioScraperId = nuvioScraperId,
    ),
    null,
)

internal fun preview(
    m: PluginManifest = manifest(),
    address: String = "kinotvapp/kino-plugin-archive",
    nuvio: Boolean = false,
) = InstallPreview(
    PluginAddress.parse(address)!!, m, "{}", false, m.hosts,
    nuvioOrigin = if (nuvio) NuvioOrigin(address, "s", ByteArray(0)) else null,
)

internal fun row(
    id: String = "archive",
    address: String = "kinotvapp/kino-plugin-archive",
    reach: PluginReach = PluginReach(hosts = listOf("archive.org")),
    enabled: Boolean = true,
    deleted: Boolean = false,
    version: String = "1.0.0",
    settings: String = "{}",
    updatedAt: Long = 10,
    nuvioScraperId: String? = null,
    sha: String = "",
) = PluginInstallEntity(
    id, address, id, if (nuvioScraperId != null) address else null, nuvioScraperId, version, sha, enabled,
    reach.toJson().toString(), settings, updatedAt, deleted,
)

internal class FakePluginInstallDao : PluginInstallDao {
    val rows = LinkedHashMap<String, PluginInstallEntity>()
    private val flow = MutableStateFlow<List<PluginInstallEntity>>(emptyList())
    override fun flowAll() = flow
    override suspend fun all() = rows.values.toList()
    override suspend fun get(id: String) = rows[id]
    override suspend fun save(row: PluginInstallEntity) { rows[row.id] = row; flow.value = rows.values.toList() }
    override suspend fun getSince(cursor: Long) = rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}

/** A [PluginSyncHost] over in-memory plugins, recording what sync did. */
internal class FakeSyncHost : PluginSyncHost {
    val plugins = LinkedHashMap<String, InstalledPlugin>()
    val settings = HashMap<String, Map<String, Any>>()
    val previews = HashMap<String, InstallPreview>()
    var previewError: Exception? = null
    var updateOutcome: UpdateOutcome = UpdateOutcome.UpToDate
    val actions = mutableListOf<String>()
    val grants = mutableListOf<Pair<String, PluginReach>>()
    val pluginsFlow: StateFlow<List<InstalledPlugin>> get() = MutableStateFlow(plugins.values.toList())

    override fun installed(id: String) = plugins[id]
    override fun installedAll() = plugins.values.toList()
    override suspend fun sharedSettings(plugin: InstalledPlugin) = settings[plugin.id].orEmpty()
    override suspend fun preview(row: PluginInstallEntity): InstallPreview {
        previewError?.let { throw it }
        return previews[row.id] ?: error("no preview for ${row.id}")
    }
    override suspend fun install(preview: InstallPreview) {
        actions += "install:${preview.manifest.id}"
        val m = preview.manifest
        plugins[m.id] = InstalledPlugin(m, InstalledRecord(preview.address.canonical, m.version, "b".repeat(64), m.hosts, 1L), null)
    }
    override fun setEnabled(id: String, enabled: Boolean) {
        actions += "enabled:$id=$enabled"
        plugins[id]?.let { plugins[id] = it.copy(record = it.record.copy(enabled = enabled)) }
    }
    override fun uninstall(id: String) { actions += "uninstall:$id"; plugins.remove(id) }
    override suspend fun checkUpdate(id: String): UpdateOutcome { actions += "check:$id"; return updateOutcome }
    override fun applyGrants(id: String, reach: PluginReach) { grants += id to reach }
    override suspend fun applySharedSettings(id: String, values: Map<String, Any>) {
        actions += "settings:$id"
        settings[id] = settings[id].orEmpty() + values
    }
}
