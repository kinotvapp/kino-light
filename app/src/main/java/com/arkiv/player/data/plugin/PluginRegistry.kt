package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PluginStatus { ACTIVE, DISABLED, UNRESPONSIVE, UPDATE_PENDING, DAMAGED }

data class InstalledPlugin(val manifest: PluginManifest, val record: InstalledRecord, val iconFile: File?) {
    val id: String get() = manifest.id

    val status: PluginStatus
        get() = when {
            record.damaged -> PluginStatus.DAMAGED
            record.unresponsive -> PluginStatus.UNRESPONSIVE
            !record.enabled -> PluginStatus.DISABLED
            record.pendingVersion != null -> PluginStatus.UPDATE_PENDING
            else -> PluginStatus.ACTIVE
        }

    /** Takes part in search, Home and playback. A pending update doesn't stop the current version. */
    val isUsable: Boolean get() = record.enabled && !record.damaged && !record.unresponsive
}

/** Whether a saved plugin title can play now, and the plugin's name for the message if not. */
sealed interface PluginAccess {
    val name: String
    /** [hosts] are the ones the person APPROVED (the installed record): the player gates the stream to them. */
    data class Ready(override val name: String, val hosts: List<String> = emptyList()) : PluginAccess
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
class PluginRegistry(private val store: PluginStore) : PluginPlayback {
    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    @Synchronized fun reload() {
        _plugins.value = store.list()
            .map { InstalledPlugin(it.manifest, it.record, it.iconFile) }
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
            else -> PluginAccess.Ready(p.manifest.name, p.record.hosts)
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
