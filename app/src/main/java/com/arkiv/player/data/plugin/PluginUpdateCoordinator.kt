package com.arkiv.player.data.plugin

/**
 * Routes a plugin's update check by its origin (`InstalledRecord.nuvioScraperId`): a normal plugin
 * re-fetches its own repo's manifest+entry ([PluginInstaller]); a Nuvio-converted one re-runs the
 * whole conversion ([NuvioPluginInstaller], spec §7) since there is no stable URL serving the wrapped
 * script to just re-download. The ONLY caller of either concrete installer's `checkUpdate` for both
 * the periodic worker and the manual "Buscar actualizaciones" button (`AppGraph`/`PluginAdmin`).
 */
class PluginUpdateCoordinator(
    private val store: PluginStore,
    private val kino: PluginInstaller,
    private val nuvio: NuvioPluginInstaller,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun checkUpdate(id: String): UpdateOutcome {
        val record = store.get(id)?.record ?: return UpdateOutcome.Failed("El plugin no está instalado")
        if (record.nuvioScraperId == null) return kino.checkUpdate(id)
        // Unlike PluginInstaller.checkUpdate's own touch() helper -- which patches lastUpdateCheckAt
        // on EVERY outcome -- NuvioPluginInstaller.checkUpdate never writes lastUpdateCheckAt itself
        // (it only patches the pending-update fields, on UpToDate/NeedsApproval, or commits a fresh
        // record on Applied; Failed writes nothing). Patched here instead, unconditionally and after the fact
        // (a fresh read/write via store.updateRecord, never a snapshot taken before the network call
        // above), so checkDueUpdates' "each plugin at most once per maxAgeMs" rule holds the same way
        // for a Nuvio-origin plugin as for any other -- without this, an already-current or
        // indefinitely-failing one would be re-checked (a full network re-conversion, not a cheap
        // re-fetch) on every single checkDueUpdates cycle instead of at most once per maxAgeMs.
        val outcome = nuvio.checkUpdate(id)
        store.updateRecord(id) { it.copy(lastUpdateCheckAt = clock()) }
        return outcome
    }

    /** [PluginInstaller.checkDueUpdates]'s replacement: the same "each plugin at most once per [maxAgeMs]" rule, across every origin. */
    suspend fun checkDueUpdates(maxAgeMs: Long = PluginInstaller.DAY_MS): List<Pair<String, UpdateOutcome>> =
        store.list()
            .filter { clock() - it.record.lastUpdateCheckAt >= maxAgeMs }
            .map { it.manifest.id to checkUpdate(it.manifest.id) }
}
