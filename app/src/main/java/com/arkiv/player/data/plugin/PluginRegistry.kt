package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PluginStatus { ACTIVE, DISABLED, UNRESPONSIVE, UPDATE_PENDING, DAMAGED, NEEDS_SETUP }

/**
 * [userHosts], [missingSettings] and [configRevision] come from the plugin's `config.json` (never
 * the Keystore — see `PluginConfigStore.missing`'s KDoc), read when the registry reloads: every
 * screen and call reads them from here, with no IO of its own.
 *
 * [configRevision] exists ONLY so this data class's own structural equality changes on every
 * settings save (fix round 2, finding 4): `PluginRegistry.reload()` sets a brand-new
 * `List<InstalledPlugin>` on a `MutableStateFlow`, and `StateFlow` silently drops an assignment
 * that's `.equals()` the current one — a save that only changes the user/password left [record],
 * [userHosts] and [missingSettings] ALL bit-for-bit equal, so nothing downstream (`pluginsChanged`,
 * Home) ever saw it change. The in-memory, non-persisted counter fix round 1 tried instead
 * (`AppGraph.pluginSessionRevision`) can't fix this: a value that lives OUTSIDE the compared data
 * class can never make the comparison itself come out different.
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val record: InstalledRecord,
    val iconFile: File?,
    val userHosts: List<UserHost> = emptyList(),
    val missingSettings: List<String> = emptyList(),
    val configRevision: Int = 0,
) {
    val id: String get() = manifest.id

    /** What this plugin may reach right now: approved hosts plus the servers typed in its settings. */
    val hosts: EffectiveHosts get() = EffectiveHosts(record.hosts, userHosts)

    /** A required setting has no value: its calls fail with `auth_required` without running. */
    val needsSetup: Boolean get() = missingSettings.isNotEmpty()

    val status: PluginStatus
        get() = when {
            record.damaged -> PluginStatus.DAMAGED
            record.unresponsive -> PluginStatus.UNRESPONSIVE
            !record.enabled -> PluginStatus.DISABLED
            needsSetup -> PluginStatus.NEEDS_SETUP
            record.pendingVersion != null -> PluginStatus.UPDATE_PENDING
            else -> PluginStatus.ACTIVE
        }

    /** Takes part in search, Home and playback. A pending update doesn't stop the current version. */
    val isUsable: Boolean get() = record.enabled && !record.damaged && !record.unresponsive

    /**
     * The key AppGraph's `pluginsChanged` uses with `distinctUntilChanged`, mapped from whatever
     * `registry.plugins` actually emits. [configRevision] is what makes this differ on ANY settings
     * save, not just one that changes hosts (fix round 2, finding 4) — see this class's own KDoc
     * for why that has to be a field of `InstalledPlugin` itself, not a side-channel value.
     */
    fun changeKey(): String = "${record.version}|$needsSetup|$userHosts|$configRevision"
}

/** Whether a saved plugin title can play now, and the plugin's name for the message if not. */
sealed interface PluginAccess {
    val name: String
    /** [hosts]: the ones the person APPROVED plus the servers they typed; the player gates the stream to them. */
    data class Ready(override val name: String, val hosts: EffectiveHosts = EffectiveHosts(emptyList())) : PluginAccess
    data class Disabled(override val name: String) : PluginAccess
    data class Uninstalled(override val name: String) : PluginAccess
    data class Damaged(override val name: String) : PluginAccess
}

fun PluginAccess.blockedMessage(): String? = when (this) {
    is PluginAccess.Ready -> null
    is PluginAccess.Disabled -> "Activa el plugin $name para ver esto"
    is PluginAccess.Uninstalled -> "Esto venía del plugin $name, que ya no está instalado"
    is PluginAccess.Damaged -> "El plugin $name tiene archivos dañados, reinstálalo"
}

/** What the player needs to know about plugins; [PluginRegistry] implements it. */
interface PluginPlayback {
    fun accessFor(pluginId: String?): PluginAccess
    fun nameOf(pluginId: String?): String?
}

/**
 * The installed plugins as a [StateFlow], rebuilt from disk on every change: search, Home and
 * Settings all react to it without a restart.
 */
/** What a plugin's `config.json` says about it: its typed servers, the required settings still
 *  empty, and the save-revision (bumped by `PluginConfigStore.save`; see `InstalledPlugin`'s KDoc
 *  on [InstalledPlugin.configRevision] for why this field exists). */
data class PluginSetupState(val userHosts: List<UserHost> = emptyList(), val missing: List<String> = emptyList(), val revision: Int = 0)

class PluginRegistry(
    private val store: PluginStore,
    /** Reads `config.json` only (a small file, like `installed.json`): never the Keystore. */
    private val setup: (StoredPlugin) -> PluginSetupState = { PluginSetupState() },
) : PluginPlayback {
    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    @Synchronized fun reload() {
        _plugins.value = store.list()
            .map { stored ->
                val state = runCatching { setup(stored) }.getOrDefault(PluginSetupState())
                InstalledPlugin(stored.manifest, stored.record, stored.iconFile, state.userHosts, state.missing, state.revision)
            }
            .sortedBy { it.manifest.name.lowercase() }
    }

    fun usable(): List<InstalledPlugin> = plugins.value.filter { it.isUsable }

    fun find(id: String): InstalledPlugin? = plugins.value.firstOrNull { it.id == id }

    fun setEnabled(id: String, enabled: Boolean) =
        update(id) { it.copy(enabled = enabled, unresponsive = if (enabled) false else it.unresponsive) }

    fun markUnresponsive(id: String) = update(id) { it.copy(unresponsive = true) }

    fun markDamaged(id: String) = update(id) { it.copy(damaged = true) }

    fun uninstall(id: String) {
        val p = store.get(id) ?: return
        store.remove(id, p.manifest.name)
        reload()
    }

    override fun accessFor(pluginId: String?): PluginAccess {
        val p = pluginId?.let(::find)
        return when {
            p == null -> PluginAccess.Uninstalled(pluginId?.let(store::removedName) ?: pluginId ?: "desconocido")
            p.record.damaged -> PluginAccess.Damaged(p.manifest.name)
            !p.isUsable -> PluginAccess.Disabled(p.manifest.name)
            else -> PluginAccess.Ready(p.manifest.name, p.hosts)
        }
    }

    override fun nameOf(pluginId: String?): String? =
        pluginId?.let { find(it)?.manifest?.name ?: store.removedName(it) }

    /** [PluginStore.updateRecord] re-reads the record fresh under its own lock right before
     *  writing, so this never clobbers a concurrent write (e.g. PluginInstaller.checkUpdate
     *  landing mid-fetch) with a value read here before that write happened. */
    private fun update(id: String, change: (InstalledRecord) -> InstalledRecord) {
        store.updateRecord(id, change)
        reload()
    }
}
