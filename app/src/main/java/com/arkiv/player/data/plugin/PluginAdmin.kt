package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The Configurar screen's data: the plugin (its manifest's settings) and the current values, passwords included. */
data class PluginSettingsForm(val plugin: InstalledPlugin, val values: Map<String, Any>)

/** Everything the Plugins screen does, behind one interface the ViewModel can fake. */
interface PluginAdmin {
    val plugins: StateFlow<List<InstalledPlugin>>
    suspend fun preview(input: String): InstallPreview
    suspend fun install(preview: InstallPreview)
    suspend fun checkUpdate(id: String): UpdateOutcome
    fun setEnabled(id: String, enabled: Boolean)
    fun uninstall(id: String)

    /** "Olvidar rechazos de host": clears every remembered "no" for [id] ([PluginRegistry.forgetRejections]). */
    fun forgetHostRejections(id: String)

    /** "Quitar permiso de video amplio": revokes [id]'s broad video permission ([PluginRegistry.setAnyVideoHost]). */
    fun revokeAnyVideoHost(id: String)

    /** Null when the plugin isn't installed any more. */
    suspend fun settingsOf(id: String): PluginSettingsForm?

    /** Null when saved; otherwise the Spanish reason nothing was saved. */
    suspend fun saveSettings(id: String, values: Map<String, Any?>): String?
}

/**
 * [forgetHomeCache] and [forgetSession] both run on a settings change, but at DIFFERENT points —
 * see [saveSettings]'s own comment for exactly why (fix round 3, "new breakage 1"):
 * [forgetHomeCache] (bumps the Home-refresh session revision, deletes `home.json`) runs FIRST,
 * before [PluginRegistry.reload]; [forgetSession] (retires the cookie jar, deletes `cookies.json`)
 * keeps running where fix round 1 put it, after `reload()` and before `runtimes.close`.
 * [afterSessionClosed] runs once more, right after the OLD runtime's pool slot is actually gone
 * (fix round 2, finding 3b). Config and Keystore work runs on [io], never on Main.
 */
class DefaultPluginAdmin(
    private val registry: PluginRegistry,
    private val installer: PluginInstaller,
    /** Routes [checkUpdate] by the plugin's origin (Task 6): normal plugins through [installer] itself, Nuvio-origin ones through [NuvioPluginInstaller]. */
    private val coordinator: PluginUpdateCoordinator,
    private val runtimes: PluginRuntimePool,
    private val config: PluginConfigStore,
    private val forgetHomeCache: (pluginId: String) -> Unit = {},
    private val forgetSession: (pluginId: String) -> Unit = {},
    private val afterSessionClosed: (pluginId: String) -> Unit = {},
    /** Uninstall only: the plugin's rows in the En vivo channel cache go, so search never lists them. */
    private val forgetLiveChannels: (pluginId: String) -> Unit = {},
    /** Uninstall only, FIRST: closes the plugin's En vivo provider while its data dir still exists (`LiveCatalog.forget`). */
    private val closeLive: (pluginId: String) -> Unit = {},
    /** Install, update and uninstall: the plugin's compiled bytecode goes ([PluginBytecodeCache.discard]). */
    private val forgetCompiledCode: (pluginId: String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginAdmin {
    override val plugins: StateFlow<List<InstalledPlugin>> get() = registry.plugins

    override suspend fun preview(input: String): InstallPreview = withContext(io) { installer.preview(input) }

    // install/checkUpdate: the WHOLE body runs on [io], registry.reload() included (fix round 1,
    // finding 6) -- reload() now reads every plugin's config.json (PluginRegistry's setup lambda),
    // so leaving it outside withContext ran it on whatever dispatcher the ViewModel called from
    // (Main, via busy()/viewModelScope). Order inside stays reload-then-close (comment below):
    // wrapping it in withContext doesn't change that, only which thread it happens on.
    override suspend fun install(preview: InstallPreview): Unit = withContext(io) {
        installer.install(preview)
        // Reload first: a call landing between these two would otherwise open the new script
        // against the old registry entry (old approved hosts) and keep it until idle close.
        registry.reload()
        runtimes.close(preview.manifest.id)
        forgetCompiledCode(preview.manifest.id)
    }

    override suspend fun checkUpdate(id: String): UpdateOutcome = withContext(io) {
        val outcome = coordinator.checkUpdate(id)
        registry.reload()
        if (outcome is UpdateOutcome.Applied) {
            runtimes.close(id)
            forgetCompiledCode(id)
        }
        outcome
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        runtimes.close(id)
    }

    /**
     * Clears every remembered host rejection for [id] and closes its runtime, the same way
     * [setEnabled] does: an already-open runtime's `PluginHttp.ReactiveApproval` keeps its own
     * in-memory snapshot of the rejected hosts (taken when it opened) and would otherwise keep
     * treating them as rejected until it next closes on its own.
     */
    override fun forgetHostRejections(id: String) {
        registry.forgetRejections(id)
        runtimes.close(id)
    }

    /**
     * The next resolve and the next player built read the record afresh, so nothing else needs
     * closing: `kino.fetch` never had the permission. A title already playing keeps its client
     * until it is rebuilt (a new title, a re-resolve, a host approval).
     */
    override fun revokeAnyVideoHost(id: String) = registry.setAnyVideoHost(id, false)

    /**
     * Also forgets its settings, passwords included: a reinstall starts from "Falta configurar".
     * Forgets its session the way [saveSettings] does, in the same order: without it, a call
     * still in flight could write `cookies.json`/`home.json` back into the data dir just deleted,
     * and a reinstall of the same id would inherit the old session. [forgetSession] runs AFTER
     * the data dir is gone: its own delete catches a write that landed before the jar retired.
     */
    override fun uninstall(id: String) {
        // Before the store deletes the data dir: a live download cancelled only after it would re-create `live/`.
        closeLive(id)
        val settings = registry.find(id)?.manifest?.settings.orEmpty()
        forgetHomeCache(id)
        registry.uninstall(id)
        forgetSession(id)
        config.clear(id, settings)
        runtimes.close(id)
        afterSessionClosed(id)
        forgetLiveChannels(id)
        forgetCompiledCode(id)
    }

    override suspend fun settingsOf(id: String): PluginSettingsForm? = withContext(io) {
        val p = registry.find(id) ?: return@withContext null
        PluginSettingsForm(p, config.read(id, p.manifest.settings).values)
    }

    /**
     * Plugin sync: the passwords another of the person's devices sent, already opened end-to-end.
     * Installed here: stored and listed ([PluginConfigStore.applySecrets]) and, when anything changed,
     * the plugin refreshed like a settings save. Not installed (yet): kept for [adoptSyncedSecrets].
     */
    suspend fun applySyncedSecrets(id: String, values: Map<String, String>): Boolean = withContext(io) {
        val p = registry.find(id)
        if (p == null) {
            config.storeSecrets(id, values)
            return@withContext false
        }
        if (!config.applySecrets(id, p.manifest.settings, values)) return@withContext false
        refreshAfterSyncedSettings(id)
        true
    }

    /** Plugin sync: just installed, the passwords another device sent before it was are now its own. */
    suspend fun adoptSyncedSecrets(id: String): Boolean = withContext(io) {
        val p = registry.find(id) ?: return@withContext false
        if (!config.adoptSecrets(id, p.manifest.settings)) return@withContext false
        refreshAfterSyncedSettings(id)
        true
    }

    private fun refreshAfterSyncedSettings(id: String) {
        forgetHomeCache(id)
        registry.reload()
        forgetSession(id)
        runtimes.close(id)
        afterSessionClosed(id)
    }

    /**
     * Plugin sync: overlays the shared settings another of the person's devices sent
     * ([PluginConfigStore.applyShared]: never a password) and, when anything
     * changed, refreshes the plugin in the same order as [saveSettings]. Returns whether it changed.
     */
    suspend fun applySharedSettings(id: String, values: Map<String, Any>): Boolean = withContext(io) {
        val p = registry.find(id) ?: return@withContext false
        if (!config.applyShared(id, p.manifest.settings, values)) return@withContext false
        forgetHomeCache(id)
        registry.reload()
        forgetSession(id)
        runtimes.close(id)
        afterSessionClosed(id)
        true
    }

    override suspend fun saveSettings(id: String, values: Map<String, Any?>): String? = withContext(io) {
        val p = registry.find(id) ?: return@withContext "El plugin ya no está instalado"
        config.save(id, p.manifest.settings, values)?.let { return@withContext it }
        // A new user or server must not inherit the old session (spec §1.3). Every step's ORDER is
        // load-bearing, and the two "forget" steps are split and placed on OPPOSITE sides of
        // reload() for two UNRELATED reasons -- folding them back into one call reopens either
        // finding 2 (round 1) or "new breakage 1" (round 3):
        //  1. forgetHomeCache() FIRST, before reload(): reload() is what makes `registry.plugins`
        //     (a StateFlow) actually emit when only the account changed (fix round 2, finding 4 --
        //     configRevision is part of InstalledPlugin's own equality). That emission is what
        //     drives `pluginsChanged`, which is what makes Home re-fetch. If the Home-cache revision
        //     bump and the home.json delete happened AFTER reload(), a re-fetch that reload()
        //     itself triggered could run and read the PRE-forget home.json/revision before this
        //     line ever got to clean it up -- a real window on EVERY save, not just a host change
        //     (fix round 3, "new breakage 1"). Putting it first means the state reload() causes
        //     anyone to observe is ALREADY post-forget, by construction: the observation literally
        //     cannot happen before the statement that precedes it in the same coroutine.
        //  2. registry.reload() SECOND: runtimes.close() below discards the pool's slot, so the
        //     very next call for this plugin can open a brand-new runtime. That runtime reads its
        //     hosts from the registry -- if reload() hadn't run yet, it would open with the OLD
        //     (pre-save) hosts and keep them until the next idle close, same bug install() already
        //     guards against.
        //  3. forgetSession() THIRD, while the OLD runtime (if any) is still the only one open:
        //     retires the OLD jar. This one genuinely needs reload() to have already happened
        //     first (finding 2, round 1) -- unlike forgetHomeCache, which has nothing to do with
        //     jars/hosts and so isn't bound by that same constraint.
        //  4. runtimes.close() FOURTH: only once the registry is current and the old session is
        //     fully forgotten does the pool's slot come down, so any runtime opened after this
        //     point is unambiguously the new session's, with a fresh jar nothing above could have
        //     touched.
        //  5. afterSessionClosed() bumps the Home-refresh session revision a SECOND time (fix round
        //     2, finding 3b): a call that read the revision forgetHomeCache() already bumped, but
        //     reached the runtime pool BEFORE runtimes.close() above actually removed the slot,
        //     still runs against the OLD runtime -- and since the revision hadn't moved again
        //     during that call, its own in-flight check alone wouldn't catch it. This second bump,
        //     guaranteed to land after the slot is gone, makes sure nothing that could have started
        //     against the pre-close runtime can ever pass a POST-close revision check again.
        forgetHomeCache(id)
        registry.reload()
        forgetSession(id)
        runtimes.close(id)
        afterSessionClosed(id)
        null
    }
}
