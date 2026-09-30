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

    /**
     * What this plugin may reach right now: approved hosts plus the servers typed in its settings,
     * and which approved hosts may be spoken to over plain http -- all from the INSTALLED record
     * (what the person approved on the consent sheet), never the manifest on disk.
     */
    val hosts: EffectiveHosts get() = EffectiveHosts(record.hosts, userHosts, record.insecureHosts.toSet())

    /**
     * [hosts] for a LIVE CHANNEL's stream: relaxed to any public host only when the INSTALLED
     * record says the person approved `liveStreamHosts: "any"` -- never from plugin output.
     */
    val liveHosts: EffectiveHosts get() = hosts.copy(anyPublicLiveHost = record.liveStreamHostsAny || record.streamHostsAny)

    /**
     * [hosts] for this plugin's VOD stream (a movie or an episode) in the PLAYER: relaxed to any
     * public host ([EffectiveHosts.anyPublicVideoHost]) only when the INSTALLED record says so
     * ([InstalledRecord.videoFromAnyHost]: the person granted the broad video permission, or approved
     * the manifest's `streamHosts: "any"` in red at install) -- never from plugin output.
     */
    val videoHosts: EffectiveHosts get() = hosts.copy(anyPublicVideoHost = record.videoFromAnyHost)

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
    /**
     * [hosts]: the ones the person APPROVED plus the servers they typed; the player gates the stream to them.
     * [xuper]: [XuperPrivilege.grants] on the INSTALLED record (never the manifest id, which any
     * repo can copy): only then does the player's gate honor the [XuperStreams] carve-out.
     */
    data class Ready(
        override val name: String,
        val hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        val xuper: Boolean = false,
        /** [hosts] for a live channel's stream (see [InstalledPlugin.liveHosts]); equal to [hosts] unless approved. */
        val liveHosts: EffectiveHosts = hosts,
        /** [hosts] for a VOD stream in the player (see [InstalledPlugin.videoHosts]); equal to [hosts] unless granted. */
        val videoHosts: EffectiveHosts = hosts,
    ) : PluginAccess {
        /**
         * The player's gate for the stream [ref] resolved to, decided from [ref] (never from the
         * episode id the player was opened with): [liveHosts] for a LIVE ref of [pluginId] itself,
         * [videoHosts] for any other ref of [pluginId] itself (the kinds `PluginContentSource.resolve`
         * relaxed them for), [hosts] for anything else.
         */
        fun streamHostsFor(pluginId: String, ref: String): EffectiveHosts {
            val decoded = PluginRef.decode(ref)?.takeIf { it.pluginId == pluginId } ?: return hosts
            return if (decoded.kind == PluginRef.LIVE) liveHosts else videoHosts
        }
    }
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
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("KinoPlugin", it) } },
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

    /**
     * Whether the installed plugin with manifest id [id] is the recognized Xuper install:
     * [XuperPrivilege.grants] on its RECORD, whatever its state. The id only locates the record
     * (ids are unique in the store); it never decides the answer, so another repo's plugin that
     * claims `xuper` as its id is still `false`.
     */
    fun isXuper(id: String): Boolean = find(id)?.let { XuperPrivilege.grants(it.record) } == true

    /**
     * Whether the installed plugin [id]'s titles get offline downloads: it declared the `download`
     * capability (apiVersion 2; the manifest on disk is the one the person approved, an update that
     * adds it waits for approval), it is usable, and it is not waiting for its settings (it could not
     * resolve anything, the same rule Home rows apply). Decides the button on the info page and in
     * the library, and is re-checked when the download runs (`PluginDownloadStrategy`). Independent
     * of [isXuper]: `DownloadSource` asks that first, so the Xuper install keeps its own path.
     */
    fun offersDownloads(id: String): Boolean =
        find(id)?.let { it.isUsable && !it.needsSetup && DOWNLOAD_CAPABILITY in it.manifest.capabilities } == true

    /**
     * True when [id] carries the store's removal tombstone (see [PluginStore.removedName]):
     * [uninstall] writes it, and only committing a fresh install for the same id clears it. A
     * caller that would otherwise install over a plugin with no live record must check this
     * first -- an absent record alone can't tell "never installed" from "explicitly uninstalled"
     * apart (see `autoInstallXuperPluginIfNeeded` in `AppGraph.kt`).
     */
    fun wasExplicitlyRemoved(id: String): Boolean = store.removedName(id) != null

    fun setEnabled(id: String, enabled: Boolean) =
        update(id) { it.copy(enabled = enabled, unresponsive = if (enabled) false else it.unresponsive) }

    fun markUnresponsive(id: String) = update(id) { it.copy(unresponsive = true) }

    fun markDamaged(id: String) = update(id) { it.copy(damaged = true) }

    /**
     * Adds [host] to [id]'s approved hosts if it isn't there already. Every one of them is the
     * person's own approval, so there is no product limit ([ManifestParser.MAX_HOSTS] limits only the
     * manifest's own list) -- only the safety cap [MAX_APPROVED_HOSTS], far past any real use, so a
     * runaway plugin can't grow installed.json without bound: past it the host is not stored (it
     * still works for the runtime that asked) and the refusal is logged. Returns whether it
     * actually changed anything.
     */
    fun addApprovedHost(id: String, host: String): Boolean {
        var added = false
        var full = false
        update(id) {
            when {
                host in it.hosts -> it
                it.hosts.size >= MAX_APPROVED_HOSTS -> { full = true; it }
                else -> { added = true; it.copy(hosts = it.hosts + host) }
            }
        }
        if (full) log("[$id] approved host $host not stored: already $MAX_APPROVED_HOSTS approved hosts (safety cap)")
        return added
    }

    /**
     * Remembers that the person said no to [host] for plugin [id]: never prompted again for it. At
     * most [MAX_REJECTED_HOSTS] are kept, the oldest dropped first (asked about again, at worst).
     */
    fun rejectHost(id: String, host: String) = update(id) {
        if (host in it.rejectedHosts) it else it.copy(rejectedHosts = (it.rejectedHosts + host).takeLast(MAX_REJECTED_HOSTS))
    }

    /** Clears every remembered "no" for [id], from Ajustes ▸ Plugins. */
    /**
     * Grants or revokes [id]'s broad video permission ([InstalledRecord.anyVideoHost]). Only ever
     * the person's own choice: the video-host dialog's third button, or "Quitar permiso de video
     * amplio" in Plugins. Logged (plugin id only, no URL).
     */
    fun setAnyVideoHost(id: String, granted: Boolean) {
        var changed = false
        update(id) { if (it.anyVideoHost == granted) it else { changed = true; it.copy(anyVideoHost = granted) } }
        if (changed) log("[$id] broad video permission " + if (granted) "granted by the person" else "revoked by the person")
    }

    fun forgetRejections(id: String) = update(id) { it.copy(rejectedHosts = emptyList()) }

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
            else -> PluginAccess.Ready(p.manifest.name, p.hosts, xuper = XuperPrivilege.grants(p.record), liveHosts = p.liveHosts, videoHosts = p.videoHosts)
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

    companion object {
        /** The declarative capability of `contract.json` that turns downloads on (apiVersion 2). */
        private const val DOWNLOAD_CAPABILITY = "download"

        /** Safety cap on a record's `hosts` (declared + approved): see [addApprovedHost]. */
        const val MAX_APPROVED_HOSTS = 500

        /** A record's remembered "no"s: see [rejectHost]. */
        const val MAX_REJECTED_HOSTS = 200
    }
}
