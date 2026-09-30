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

    /**
     * The person granted the broad video permission ([InstalledRecord.anyVideoHost]), now or
     * before: this plugin's VOD video may come from any public server. No host was added.
     */
    APPROVED_ANY_VIDEO_HOST,
}

/**
 * Decides whether a Stream URL's undeclared [host] may be added to [pluginId]'s hosts; see
 * [StreamHostApproval]. `offerAnyVideoHost`: the dialog may also offer the broad video permission
 * (a VOD stream's video, subtitle or audio host; never a license or a live channel).
 */
fun interface StreamHostDecider {
    suspend fun decide(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason, offerAnyVideoHost: Boolean): StreamHostDecision
}

/** What the player does after [PlaybackHostPrompts.onRefused]. */
sealed interface PlaybackHostOutcome {
    /** The host is approved: rebuild the player's gated client with the plugin's hosts read afresh and resume. */
    data object Retry : PlaybackHostOutcome

    /** Show [message] (Spanish, names the host) as the playback error. */
    data class Fail(val message: String) : PlaybackHostOutcome
}

/**
 * Reactive host approval for what the PLAYER reaches while a plugin stream plays: the gated client
 * refused a request (an HLS playlist's segments or keys on another CDN, a redirect hop) only because
 * the host is undeclared, and it is askable ([UndeclaredPlaybackHostException]). Asked through the
 * same [StreamHostDecider] as a returned Stream's hosts ([StreamHostApproval]): the same dialog, no
 * time limit, the same per-plugin memory, the same registry writes (no cap on how many).
 *
 * One question per host per playback attempt ([newAttempt], called on every fresh resolve): a host
 * refused again after its "yes" -- the rebuilt player still can't use it -- ends in an error, never
 * the same question in a loop. Different hosts are asked one after the other, as they come.
 * Not thread-safe: the player's ViewModel drives it from one coroutine at a time.
 */
class PlaybackHostPrompts(private val decider: StreamHostDecider) {
    private val asked = HashSet<String>()

    /** A new playback attempt (a fresh resolve): every host may be asked about once more. */
    fun newAttempt() = asked.clear()

    /** [offerAnyVideoHost]: a VOD stream (the dialog may offer the broad video permission); false for a live channel. */
    suspend fun onRefused(pluginId: String, pluginName: String, host: String, offerAnyVideoHost: Boolean = false): PlaybackHostOutcome {
        if (!asked.add(host)) return PlaybackHostOutcome.Fail("$pluginName: el video no se pudo cargar desde $host")
        return when (decider.decide(pluginId, pluginName, host, HostApprovalReason.VIDEO, offerAnyVideoHost)) {
            StreamHostDecision.APPROVED, StreamHostDecision.APPROVED_ANY_VIDEO_HOST -> PlaybackHostOutcome.Retry
            StreamHostDecision.REJECTED -> PlaybackHostOutcome.Fail("$pluginName: el video usa otro servidor ($host) que no permitiste")
        }
    }
}

/**
 * Reactive host approval for what a plugin's `resolve` RETURNED, not only for what its `kino.fetch`
 * reached: the Stream's URL (or a subtitle, audio track or license URL) is on a host the plugin never
 * declared -- routine for converted Nuvio scrapers, which hand back CDN links they never fetched.
 *
 * Same dialog ([HostApprovalCenter]), same eligibility (`PluginOutput.undeclaredHosts` keeps only
 * what [PluginHostGate.isPromptableMiss] calls a promptable miss), same per-plugin memory and the
 * same persistence path as a fetch-time approval ([PluginRegistry.addApprovedHost],
 * [PluginRegistry.rejectHost]), with no cap on how many hosts the person approves:
 * - a host this plugin was told "no" for is refused without asking, until "Olvidar rechazos de host";
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
    override suspend fun decide(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason, offerAnyVideoHost: Boolean): StreamHostDecision {
        val record = registry.find(pluginId)?.record ?: return StreamHostDecision.REJECTED
        // Never for a fetch or a license, whatever the caller asks.
        val offer = offerAnyVideoHost && reason != HostApprovalReason.FETCH && reason != HostApprovalReason.LICENSE
        // Granted meanwhile (an earlier question of this same Stream, another title): nothing to ask.
        if (offer && record.videoFromAnyHost) return StreamHostDecision.APPROVED_ANY_VIDEO_HOST
        if (host in record.rejectedHosts) {
            log("[$pluginId] $reason host $host: refused before, not asked again")
            return StreamHostDecision.REJECTED
        }
        // An earlier question in the same Stream (or a fetch meanwhile) may already have added it.
        if (HostRules.matches(host, record.hosts)) return StreamHostDecision.APPROVED
        val answer = center.askStreamHost(pluginId, pluginName, host, reason, offer)
        // No suspension from here on (see the class KDoc).
        if (answer == HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST) {
            registry.setAnyVideoHost(pluginId, true)
            log("[$pluginId] $reason host $host: broad video permission granted")
            return StreamHostDecision.APPROVED_ANY_VIDEO_HOST
        }
        val approved = answer == HostApprovalAnswer.ALLOW_HOST
        if (!approved) {
            registry.rejectHost(pluginId, host)
            openRuntimeHttp(pluginId)?.recordDecision(host, approved = false)
            return StreamHostDecision.REJECTED
        }
        val added = registry.addApprovedHost(pluginId, host)
        val present = added || registry.find(pluginId)?.record?.hosts?.let { HostRules.matches(host, it) } == true
        if (!present) {
            // Only when the plugin was uninstalled while this dialog was up: nothing to add it to.
            log("[$pluginId] $reason host $host approved but not added: the plugin is gone")
            return StreamHostDecision.REJECTED
        }
        openRuntimeHttp(pluginId)?.recordDecision(host, approved = true)
        return StreamHostDecision.APPROVED
    }
}
