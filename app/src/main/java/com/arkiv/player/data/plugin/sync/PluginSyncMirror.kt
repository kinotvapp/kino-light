package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.PluginInstallDao
import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.SemVer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Writes the person's own plugin actions on THIS device into the `plugin_installs` table, which
 * companion sync carries to their other devices. Event-driven on purpose: it records an install, a
 * switch, an uninstall, an approval or a settings save when the person makes it
 * ([SyncingPluginAdmin], [com.arkiv.player.data.plugin.PluginRegistry.onPersonApproval]), and never
 * what sync itself did here ([PluginSyncReconciler] goes around it). Diffing the plugins against the
 * table instead would send a peer's change straight back with a newer clock, forever.
 *
 * Each event updates only its own fields of the row, so the row stays the merge of what every device
 * last said. A write that would change nothing is skipped (no new clock, nothing to push). Events run
 * one at a time, in order, on [scope].
 */
class PluginSyncMirror(
    private val dao: PluginInstallDao,
    private val host: PluginSyncHost,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { runCatching { android.util.Log.w("KinoPluginSync", it) } },
    /** Where the person's own password saves are stamped, so the same passwords coming back are not re-applied. */
    private val secretStamps: SecretStamps = SecretStamps.NONE,
) {
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    event()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("mirror: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun post(event: suspend () -> Unit) {
        events.trySend(event)
    }

    /**
     * The person installed (or re-installed, or approved an update of) [id] here: the whole row from
     * this device, keeping what the row already said for the same plugin (approvals joined, shared
     * settings overlaid with this device's). [then] runs after the write, in order.
     */
    fun recordInstall(id: String, then: (String) -> Unit = {}) = post {
        val local = host.installed(id)
        if (local != null) {
            val old = dao.get(id)
            val keep = old?.takeIf { !it.deleted && it.address == local.record.address }
            val reach = PluginReach.fromRecord(local.record).let { r -> keep?.let { PluginReach.fromJson(JSONObject(it.approvedJson)).union(r) } ?: r }
            val settings = (keep?.let { SharedSettings.fromJson(JSONObject(it.settingsJson)) }.orEmpty()) + host.sharedSettings(local)
            put(rowOf(local, reach, settings, enabled = local.record.enabled).copy(secretsAt = keep?.secretsAt ?: 0L), old)
        }
        then(id)
    }

    /** The person switched [id] on or off here. A plugin removed on another device is not brought back by a switch. */
    fun recordEnabled(id: String) = post {
        val local = host.installed(id) ?: return@post
        val old = dao.get(id)
        when {
            old == null -> put(rowOf(local, PluginReach.fromRecord(local.record), host.sharedSettings(local), local.record.enabled), null)
            old.deleted || old.address != local.record.address -> Unit
            else -> put(old.copy(enabled = local.record.enabled), old)
        }
    }

    /** The person uninstalled [id] here; [before] is the plugin as it was, for a plugin never recorded. */
    fun recordUninstall(id: String, before: InstalledPlugin?) = post {
        val old = dao.get(id)
        when {
            old != null -> if (before == null || old.address == before.record.address) put(old.copy(deleted = true), old)
            before != null -> put(rowOf(before, PluginReach.fromRecord(before.record), emptyMap(), before.record.enabled).copy(deleted = true), null)
        }
    }

    /**
     * The person approved or refused a host, or granted/revoked the broad video permission, or forgot
     * the "no"s: hosts are joined (an approval is never taken back elsewhere), the "no"s and the broad
     * permission are this device's.
     */
    fun recordApprovals(id: String) = post {
        val local = host.installed(id) ?: return@post
        val old = dao.get(id) ?: return@post put(rowOf(local, PluginReach.fromRecord(local.record), host.sharedSettings(local), local.record.enabled), null)
        if (old.deleted || old.address != local.record.address) return@post
        val mine = PluginReach.fromRecord(local.record)
        val reach = PluginReach.fromJson(JSONObject(old.approvedJson)).union(mine)
            .copy(rejectedHosts = mine.rejectedHosts, anyVideoHost = mine.anyVideoHost)
        put(old.copy(approvedJson = reach.toJson().toString()), old)
    }

    /**
     * The person saved [id]'s settings here: its shared ones replace the row's. A plugin with password
     * settings also gets a new [PluginInstallEntity.secretsAt], so its passwords are sealed again for
     * the person's other devices (never written in the row).
     */
    fun recordSettings(id: String) = post {
        val local = host.installed(id) ?: return@post
        val hasPasswords = local.manifest.settings.any { it.type == com.arkiv.player.data.plugin.SettingType.PASSWORD }
        val existing = dao.get(id)
        val old = existing?.takeIf { !it.deleted && it.address == local.record.address }
        if (existing != null && old == null) return@post
        val base = old ?: rowOf(local, PluginReach.fromRecord(local.record), host.sharedSettings(local), local.record.enabled)
        var row = base.copy(settingsJson = SharedSettings.toJson(host.sharedSettings(local)).toString())
        if (hasPasswords) {
            val stamp = maxOf(clock(), (old?.secretsAt ?: 0L) + 1)
            row = row.copy(secretsAt = stamp)
            secretStamps.set(id, stamp)
        }
        put(row, old)
    }

    /**
     * [id] was updated here: the row learns the newer version (never an older one), and with
     * [withHash] the new script's hash (how a Nuvio conversion, always "1.0.0", tells a change).
     */
    fun recordVersion(id: String, withHash: Boolean) = post { writeVersion(id, withHash) }

    /**
     * Once per start: a row for every plugin installed here that has none yet (a device that had
     * plugins before sync existed), and newer versions a background update installed. Never touches
     * a tombstone: a plugin the person removed on another device and that stays here (Xuper) must not
     * be resurrected there.
     */
    fun backfill() = post {
        for (p in host.installedAll()) {
            val old = dao.get(p.id)
            if (old == null) put(rowOf(p, PluginReach.fromRecord(p.record), host.sharedSettings(p), p.record.enabled), null)
            else writeVersion(p.id, withHash = false)
        }
    }

    private suspend fun writeVersion(id: String, withHash: Boolean) {
        val local = host.installed(id) ?: return
        val old = dao.get(id) ?: return
        if (old.deleted || old.address != local.record.address) return
        val newer = SemVer.isValid(local.record.version) && SemVer.isValid(old.version) && SemVer.compare(local.record.version, old.version) > 0
        val hashChanged = withHash && local.record.sha256 != old.sha256
        if (!newer && !hashChanged) return
        put(old.copy(version = if (newer) local.record.version else old.version, sha256 = local.record.sha256), old)
    }

    private fun rowOf(p: InstalledPlugin, reach: PluginReach, settings: Map<String, Any>, enabled: Boolean) = PluginInstallEntity(
        id = p.id,
        address = p.record.address,
        name = p.manifest.name,
        nuvioRepo = p.record.nuvioRepo,
        nuvioScraperId = p.record.nuvioScraperId,
        version = p.record.version,
        sha256 = p.record.sha256,
        enabled = enabled,
        approvedJson = reach.toJson().toString(),
        settingsJson = SharedSettings.toJson(settings).toString(),
    )

    /** Saves [row] unless it says the same as [old]; the clock always moves forward past [old]'s. */
    private suspend fun put(row: PluginInstallEntity, old: PluginInstallEntity?) {
        if (old != null && same(row, old)) return
        dao.save(row.copy(updatedAt = maxOf(clock(), (old?.updatedAt ?: 0L) + 1)))
    }

    private fun same(a: PluginInstallEntity, b: PluginInstallEntity): Boolean =
        a.copy(updatedAt = 0, approvedJson = "", settingsJson = "") == b.copy(updatedAt = 0, approvedJson = "", settingsJson = "") &&
            PluginReach.fromJson(JSONObject(a.approvedJson)) == PluginReach.fromJson(JSONObject(b.approvedJson)) &&
            SharedSettings.fromJson(JSONObject(a.settingsJson)) == SharedSettings.fromJson(JSONObject(b.settingsJson))
}
