package com.arkiv.player.data.plugin

import kotlin.coroutines.CoroutineContext

/**
 * Marks a plugin call made while a person is on screen waiting for its answer: the player resolving
 * the title they just opened (`PlayerViewModel.loadPlugin`). Only then may a Stream URL on a host the
 * plugin never declared be asked about ([StreamHostApproval], through `PluginContentSource.resolve`);
 * without it -- a download in the queue, anything in the background -- the Stream is refused exactly
 * as before, since nobody is there to answer. [BackgroundPluginCall] always wins over it. Set with
 * `withContext(InteractivePluginCall)`.
 */
object InteractivePluginCall : CoroutineContext.Element, CoroutineContext.Key<InteractivePluginCall> {
    override val key: CoroutineContext.Key<*> get() = this
}

/** What [StreamHostDecider.decide] concluded about one undeclared host of a returned Stream. */
enum class StreamHostDecision {
    /** The host is (now) one of the plugin's approved hosts. */
    APPROVED,

    /** The person said no, now or before (remembered per plugin), or the plugin is gone. */
    REJECTED,

    /** The plugin already has [ManifestParser.MAX_HOSTS] approved hosts: nothing more can be added. */
    LIMIT_REACHED,
}

/** Decides whether a Stream URL's undeclared [host] may be added to [pluginId]'s hosts; see [StreamHostApproval]. */
fun interface StreamHostDecider {
    suspend fun decide(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): StreamHostDecision
}

/**
 * Reactive host approval for what a plugin's `resolve` RETURNED, not only for what its `kino.fetch`
 * reached: the Stream's URL (or a subtitle, audio track or license URL) is on a host the plugin never
 * declared -- routine for converted Nuvio scrapers, which hand back CDN links they never fetched.
 *
 * Same dialog ([HostApprovalCenter]), same eligibility (`PluginOutput.undeclaredHosts` keeps only
 * what [PluginHostGate.isPromptableMiss] calls a promptable miss), same per-plugin memory and the
 * same persistence path as a fetch-time approval ([PluginRegistry.addApprovedHost] with its 20-host
 * cap, [PluginRegistry.rejectHost]):
 * - a host this plugin was told "no" for is refused without asking, until "Olvidar rechazos de host";
 * - with 20 approved hosts already there is nothing to ask: [StreamHostDecision.LIMIT_REACHED];
 * - otherwise the person is asked, with NO time limit ([HostApprovalCenter.requestUntilAnswered]):
 *   the plugin's call is over, nothing runs against a clock, and they are waiting for their video.
 *
 * Nothing is written until the answer arrives, and from then on nothing suspends: a cancellation
 * (the person leaving the player) lands either before any write -- no host, no rejection -- or not
 * at all. The open runtime's own gate learns the answer too ([PluginHttp.recordDecision]), so the
 * plugin's next `kino.fetch` to that host, in the same runtime, never asks a second time.
 */
class StreamHostApproval(
    private val center: HostApprovalCenter,
    private val registry: PluginRegistry,
    /** The [PluginHttp] of [pluginId]'s open runtime, if one is open (`AppGraph.pluginHttps`). */
    private val openRuntimeHttp: (pluginId: String) -> PluginHttp? = { null },
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) : StreamHostDecider {
    override suspend fun decide(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): StreamHostDecision {
        val record = registry.find(pluginId)?.record ?: return StreamHostDecision.REJECTED
        if (host in record.rejectedHosts) {
            log("[$pluginId] $reason host $host: refused before, not asked again")
            return StreamHostDecision.REJECTED
        }
        // An earlier question in the same Stream (or a fetch meanwhile) may already have added it.
        if (HostRules.matches(host, record.hosts)) return StreamHostDecision.APPROVED
        if (record.hosts.size >= ManifestParser.MAX_HOSTS) {
            log("[$pluginId] $reason host $host: not asked, already at the ${ManifestParser.MAX_HOSTS}-host limit")
            return StreamHostDecision.LIMIT_REACHED
        }
        val approved = center.requestUntilAnswered(pluginId, pluginName, host, reason)
        // No suspension from here on (see the class KDoc).
        if (!approved) {
            registry.rejectHost(pluginId, host)
            openRuntimeHttp(pluginId)?.recordDecision(host, approved = false)
            return StreamHostDecision.REJECTED
        }
        val added = registry.addApprovedHost(pluginId, host)
        val present = added || registry.find(pluginId)?.record?.hosts?.let { HostRules.matches(host, it) } == true
        if (!present) {
            // Only when ANOTHER host (a fetch-time approval) filled the last slot while this dialog was up.
            log("[$pluginId] $reason host $host approved but not added: already at the ${ManifestParser.MAX_HOSTS}-host limit")
            return StreamHostDecision.LIMIT_REACHED
        }
        openRuntimeHttp(pluginId)?.recordDecision(host, approved = true)
        return StreamHostDecision.APPROVED
    }
}
