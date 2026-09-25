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
    private val runtimes: PluginRuntimePool,
    private val config: PluginConfigStore,
    private val forgetHomeCache: (pluginId: String) -> Unit = {},
    private val forgetSession: (pluginId: String) -> Unit = {},
    private val afterSessionClosed: (pluginId: String) -> Unit = {},
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
    }

    override suspend fun checkUpdate(id: String): UpdateOutcome = withContext(io) {
        val outcome = installer.checkUpdate(id)
        registry.reload()
        if (outcome is UpdateOutcome.Applied) runtimes.close(id)
        outcome
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
