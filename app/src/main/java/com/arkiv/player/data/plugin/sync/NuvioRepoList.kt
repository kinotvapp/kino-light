package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.NuvioRepoDao
import com.arkiv.player.data.db.NuvioRepoEntity
import com.arkiv.player.data.sync.isSyncableNuvioRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * "Tus repositorios de Nuvio": the Nuvio provider repos the person opened on any of their devices
 * (the `nuvio_repos` table, carried by companion sync). Opening one again shows its scraper picker
 * without typing the address. Only the person's own actions write here ([add] when a repo's picker
 * opens, [remove] for "Quitar", [backfill] once per start for repos of installed Nuvio plugins);
 * what sync brings is applied by `SyncApply`. Disk IO: call off the main thread.
 */
class NuvioRepoList(
    private val dao: NuvioRepoDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The listed repos, by address. */
    val addresses: Flow<List<String>> = dao.flowAll().map { rows -> rows.filter { !it.deleted }.map { it.address }.sortedBy { it.lowercase() } }

    /** The person opened [address]'s scraper picker: listed (again, if they had removed it). */
    suspend fun add(address: String) {
        if (!isSyncableNuvioRepo(address)) return
        val old = dao.get(address)
        if (old != null && !old.deleted) return
        put(NuvioRepoEntity(address), old)
    }

    /** "Quitar": a tombstone, so the person's other devices drop it too. Installed scrapers stay installed. */
    suspend fun remove(address: String) {
        val old = dao.get(address) ?: return
        if (old.deleted) return
        put(old.copy(deleted = true), old)
    }

    /** The repos of the Nuvio plugins installed here that have no row at all (installed before this list existed). */
    suspend fun backfill(repos: Collection<String>) {
        for (address in repos.distinct()) if (isSyncableNuvioRepo(address) && dao.get(address) == null) put(NuvioRepoEntity(address), null)
    }

    /** The clock always moves forward past [old]'s. */
    private suspend fun put(row: NuvioRepoEntity, old: NuvioRepoEntity?) {
        dao.save(row.copy(updatedAt = maxOf(clock(), (old?.updatedAt ?: 0L) + 1)))
    }
}
