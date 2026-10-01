package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.PluginInstallDao
import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioPluginInstaller
import com.arkiv.player.data.plugin.PluginFacts
import com.arkiv.player.data.plugin.PluginFailure
import com.arkiv.player.data.plugin.PluginFailureKind
import com.arkiv.player.data.plugin.PluginTelemetry
import com.arkiv.player.data.plugin.SemVer
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.XuperPrivilege
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where a plugin from another device stands on this one, for the "Plugins de tus otros aparatos" list. */
enum class PeerOfferStatus {
    /** Not tried yet on this device (or waiting for the next try). */
    WAITING,
    INSTALLING,
    /** What this device fetched asks for more than was approved on the other one: the person decides here. */
    NEEDS_CONSENT,
    /** The silent install failed (no internet, GitHub down, the repo changed): one tap retries with consent. */
    FAILED,
}

/**
 * Turns the `plugin_installs` rows another of the person's devices sent into this device's plugins.
 * Rows never touch `plugins/`: everything goes through the same installer an install by hand uses,
 * fetching the plugin from GitHub (or re-converting the Nuvio scraper) on THIS device.
 *
 * Per row, in order:
 * - a tombstone uninstalls the plugin here, except Xuper (each device keeps its own) and a plugin of
 *   the same id installed from another address;
 * - a plugin not installed here is installed SILENTLY when what this device fetched asks for nothing
 *   beyond what the person approved there ([PluginReach.covers]); otherwise it waits in the list for
 *   the normal consent sheet ([PeerOfferStatus.NEEDS_CONSENT]);
 * - an installed one follows the row's switch (Xuper is never switched off from another device), gets
 *   the approvals and shared settings, and when the other device has a newer version it checks for
 *   its own update right away (an update that needs approval applies only within the row's approval).
 *
 * Work runs one row at a time on [scope]. Nothing here writes a row: what sync does is not the
 * person's action, so it is never sent back ([PluginSyncMirror]).
 */
class PluginSyncReconciler(
    private val dao: PluginInstallDao,
    private val host: PluginSyncHost,
    scope: CoroutineScope,
    private val report: (PluginFailure) -> Unit = { PluginTelemetry.current.report(it) },
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("KinoPluginSync", it) } },
    /** Suspends until this device's plugins are loaded (app warm-up); the queue waits for it. */
    private val awaitReady: suspend () -> Unit = {},
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val _statuses = MutableStateFlow<Map<String, PeerOfferStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerOfferStatus>> = _statuses.asStateFlow()

    /** `id|version-or-hash` already checked for an update this process: a peer's row is not a reason to check twice. */
    private val updateChecked = HashSet<String>()

    init {
        scope.launch {
            awaitReady()
            for (id in queue) {
                try {
                    reconcile(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("reconcile $id: ${e.javaClass.simpleName}")
                    setStatus(id, PeerOfferStatus.FAILED)
                }
            }
        }
    }

    /** A peer's newer row for [id] was just stored (`SyncApply`). */
    fun onIncoming(id: String) {
        queue.trySend(id)
    }

    /**
     * Retries every plugin from another device that is not installed here and was not left for the
     * person's consent: the last try may have run without internet. Called when sync starts.
     */
    fun retryPending() {
        // Read on the worker, in order with everything else queued.
        queue.trySend(RETRY)
    }

    internal suspend fun reconcile(id: String) {
        if (id == RETRY) {
            for (row in dao.all()) {
                if (row.deleted || host.installed(row.id) != null) continue
                if (_statuses.value[row.id] == PeerOfferStatus.NEEDS_CONSENT) continue
                install(row)
            }
            return
        }
        val row = dao.get(id) ?: return
        val local = host.installed(id)
        when {
            row.deleted -> {
                setStatus(id, null)
                if (local != null && sameOrigin(local, row) && !XuperPrivilege.grants(local.record)) {
                    host.uninstall(id)
                    log("[$id] uninstalled: removed on another device")
                }
            }
            local == null -> install(row)
            sameOrigin(local, row) -> follow(row, local)
        }
    }

    private suspend fun install(row: PluginInstallEntity) {
        val id = row.id
        setStatus(id, PeerOfferStatus.INSTALLING)
        val preview = try {
            host.preview(row)
        } catch (e: InstallException) {
            fail(row, "preview", e)
            return
        }
        if (preview.manifest.id != id || preview.address.canonical != row.address) {
            fail(row, "mismatch", null)
            return
        }
        val reach = reachOf(row)
        if (!reach.covers(preview)) {
            setStatus(id, PeerOfferStatus.NEEDS_CONSENT)
            log("[$id] waits for consent: asks for more than was approved on the other device")
            return
        }
        try {
            host.install(preview)
        } catch (e: InstallException) {
            fail(row, "install", e)
            return
        }
        setStatus(id, null)
        log("[$id] installed from another device")
        val local = host.installed(id) ?: return
        host.applyGrants(id, reach)
        if (!row.enabled && !XuperPrivilege.grants(local.record)) host.setEnabled(id, false)
        applySettings(row, local)
        host.adoptSecrets(id)
    }

    private suspend fun follow(row: PluginInstallEntity, local: InstalledPlugin) {
        val id = row.id
        setStatus(id, null)
        if (row.enabled != local.record.enabled && (row.enabled || !XuperPrivilege.grants(local.record))) {
            host.setEnabled(id, row.enabled)
        }
        val reach = reachOf(row)
        host.applyGrants(id, reach)
        applySettings(row, host.installed(id) ?: return)
        host.adoptSecrets(id)
        maybeUpdate(row, host.installed(id) ?: return, reach)
    }

    private suspend fun applySettings(row: PluginInstallEntity, local: InstalledPlugin) {
        val values = SharedSettings.fromJson(runCatching { JSONObject(row.settingsJson) }.getOrNull())
        if (values.isEmpty()) return
        host.applySharedSettings(local.id, values)
    }

    private suspend fun maybeUpdate(row: PluginInstallEntity, local: InstalledPlugin, reach: PluginReach) {
        val nuvio = local.record.nuvioScraperId != null
        val newer = if (nuvio) {
            row.sha256.isNotEmpty() && row.sha256 != local.record.sha256
        } else {
            SemVer.isValid(row.version) && SemVer.isValid(local.record.version) && SemVer.compare(row.version, local.record.version) > 0
        }
        if (!newer || !updateChecked.add("${row.id}|${if (nuvio) row.sha256 else row.version}")) return
        log("[${row.id}] the other device has a newer version: checking for an update")
        val outcome = host.checkUpdate(row.id)
        if (outcome is UpdateOutcome.NeedsApproval && reach.covers(outcome.preview)) {
            try {
                host.install(outcome.preview)
            } catch (e: InstallException) {
                fail(row, "update", e, keepStatus = true)
            }
        }
    }

    private fun sameOrigin(local: InstalledPlugin, row: PluginInstallEntity): Boolean =
        local.record.address == row.address || (XuperPrivilege.grants(local.record) && XuperPrivilege.isOfficial(row.address))

    private fun reachOf(row: PluginInstallEntity) = PluginReach.fromJson(runCatching { JSONObject(row.approvedJson) }.getOrNull())

    private fun setStatus(id: String, status: PeerOfferStatus?) =
        _statuses.update { if (status == null) it - id else it + (id to status) }

    /**
     * Telemetry: the stage and the kind of error only -- never the address, a setting or the
     * installer's sentence (it can name the repo). A Nuvio repo is named only when it is a well-known
     * public one, like every other Nuvio report.
     */
    private fun fail(row: PluginInstallEntity, stage: String, e: Exception?, keepStatus: Boolean = false) {
        if (!keepStatus) setStatus(row.id, PeerOfferStatus.FAILED)
        log("[${row.id}] sync $stage failed: ${e?.javaClass?.simpleName ?: "mismatch"}")
        val nuvio = row.nuvioRepo != null
        val publicRepo = PluginTelemetry.publicNuvioRepo(row.nuvioRepo)
        val pluginId = if (nuvio && publicRepo == null) NuvioPluginInstaller.NUVIO_IMPORT_ID else row.id
        runCatching {
            report(
                PluginFailure(
                    pluginId, "sync:$stage", if (nuvio) PluginFailureKind.CONVERSION else PluginFailureKind.INSTALL,
                    detail = mapOf("error" to (e?.let { "install_exception" } ?: "mismatch")),
                    facts = PluginFacts(row.version.takeIf(SemVer::isValid), null, if (nuvio) "nuvio" else "sync", publicRepo, row.nuvioScraperId.takeIf { publicRepo != null }),
                    privateText = listOfNotNull(row.address, row.nuvioRepo),
                ),
            )
        }
    }

    private companion object {
        /** Not a plugin id (ids never contain `#`): the queue's "retry everything pending" marker. */
        const val RETRY = "#retry"
    }
}
