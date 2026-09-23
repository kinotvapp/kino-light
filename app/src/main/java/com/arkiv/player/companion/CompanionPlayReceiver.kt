package com.arkiv.player.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * TV side: turns an incoming `play` envelope into playback + a `play_ack`. Pure coordination -- the
 * Android work (re-resolve the descriptor against this device's session, open the player) sits
 * behind [PlayResolver]/[onOpen], so the collect/decode/ack flow is unit-testable without a device.
 */
class CompanionPlayReceiver(
    private val scope: CoroutineScope,
    private val incoming: SharedFlow<Envelope>,
    private val send: (Envelope) -> Unit,
    private val resolver: PlayResolver,
    private val onOpen: (episodeId: String) -> Unit,
) {
    interface PlayResolver {
        /** Re-resolve [item] against THIS device and either produce a local episodeId to open, or a
         *  reason it can't. Never opens the player itself -- the receiver calls [onOpen]. */
        suspend fun resolve(item: CompanionPlayItem): PlayOutcome
    }

    sealed class PlayOutcome {
        data class Open(val episodeId: String, val title: String) : PlayOutcome()
        data class Fail(val reason: String) : PlayOutcome()
    }

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            incoming.collect { env ->
                if (env.type != TYPE_PLAY) return@collect
                val item = CompanionPlayItem.fromPayload(env.payload) ?: return@collect
                val ack = try {
                    when (val r = resolver.resolve(item)) {
                        is PlayOutcome.Open -> {
                            onOpen(r.episodeId)
                            CompanionPlayAck(ok = true, title = r.title)
                        }
                        is PlayOutcome.Fail -> CompanionPlayAck(ok = false, reason = r.reason, title = item.title)
                    }
                } catch (ce: CancellationException) {
                    throw ce // structured cancellation must propagate, not be swallowed as a failure
                } catch (t: Throwable) {
                    // ANY resolver failure (credentials not ready on the TV, a portal/DB error, a
                    // bad descriptor) must still ack and must NOT fail this collector: it runs on a
                    // SupervisorJob with no exception handler, so an uncaught throw would crash the
                    // app and leave the receiver permanently dead (job stays set, start() no-ops).
                    CompanionPlayAck(ok = false, reason = "resolve_failed", title = item.title)
                }
                // Reply on the SAME envelope id so the controller can match the ack to its play.
                send(Envelope(CompanionProtocol.VERSION, TYPE_PLAY_ACK, env.id, ack.toPayload()))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
