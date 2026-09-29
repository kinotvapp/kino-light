package com.arkiv.player.data.plugin

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the person, in the moment, whether a plugin may reach a host its manifest never declared. */
fun interface HostApprovalRequester {
    /**
     * true = allowed, false = refused (remembered), null = no answer (NOT remembered: the next miss
     * asks again). [HostApprovalCenter] never answers null: it waits for the person, and its caller
     * cancels it when nobody is waiting any more (see [PluginCall.askWhileAlive]).
     */
    suspend fun request(pluginId: String, pluginName: String, host: String): Boolean?
}

/** The default for anywhere with no UI to ask (the install-time probe, tests): never grants a host it wasn't told to. */
object NoHostApprovalRequester : HostApprovalRequester {
    override suspend fun request(pluginId: String, pluginName: String, host: String): Boolean = false
}

/**
 * Why a host is being asked about: a `kino.fetch` of the plugin's own ([FETCH]), or one URL of a
 * Stream the plugin's `resolve` returned ([VIDEO], [LICENSE], [SUBTITLE], [AUDIO]; see
 * [StreamHostApproval]). It only changes the sentence the dialog shows ([HostApprovalRequest.question]).
 */
enum class HostApprovalReason { FETCH, VIDEO, LICENSE, SUBTITLE, AUDIO }

/** One pending dialog. [respond] resumes the coroutine that's waiting inside [HostApprovalCenter.request]. */
data class HostApprovalRequest(
    val pluginId: String,
    val pluginName: String,
    val host: String,
    val reason: HostApprovalReason = HostApprovalReason.FETCH,
    /** How long the dialog ignores every answer after it appears (see [HostApprovalCenter.SUCCESSOR_ARM_DELAY_MS]). */
    val armDelayMs: Long = HostApprovalCenter.ARM_DELAY_MS,
    val respond: (Boolean) -> Unit,
) {
    /**
     * The sentence under the plugin's name in the dialog. A fetch keeps the sentence it always had;
     * a Stream's URL says what is on that host, since by then the plugin already answered and it is
     * the video (or its subtitles, audio or license) that would come from there.
     */
    val question: String get() = when (reason) {
        HostApprovalReason.FETCH -> "Quiere conectarse por primera vez a $host. ¿Permitir?"
        HostApprovalReason.VIDEO -> "El video está en $host, un servidor nuevo para este plugin. ¿Permitir?"
        HostApprovalReason.LICENSE -> "La licencia del video está en $host, un servidor nuevo para este plugin. ¿Permitir?"
        HostApprovalReason.SUBTITLE -> "Los subtítulos están en $host, un servidor nuevo para este plugin. ¿Permitir?"
        HostApprovalReason.AUDIO -> "Un audio del video está en $host, un servidor nuevo para este plugin. ¿Permitir?"
    }
}

/**
 * Bridges the coroutine inside [PluginHttp] (running on [kotlinx.coroutines.Dispatchers.IO], deep in
 * a plugin call) to the Compose dialog collected from [pending] at the app's root. [mutex] means a
 * second request simply waits its turn instead of showing a second dialog on top of the first (or
 * REPLACING it under the person's finger): one dialog at a time, the next one only once the person
 * answered the current one or its caller went away.
 *
 * No request has a time limit of its own. A `kino.fetch` prompt used to close itself after 12 s;
 * now the plugin call's clock is paused for as long as its question is queued or on screen
 * ([PluginCall.askWhileAlive], [PluginCallClock]), and the question is cancelled -- taken down,
 * nothing recorded -- the moment that call ends. So a dialog only ever leaves the screen because the
 * person answered it or nobody is waiting for the answer any more.
 *
 * A dialog that appears within [SUCCESSOR_WINDOW_MS] of the previous one closing arms later
 * ([HostApprovalRequest.armDelayMs]): on the TV a "RIGHT, CENTER" meant for one prompt landed on the
 * next, whose focus was back on "Rechazar", and rejected the host the person meant to allow.
 */
class HostApprovalCenter(
    /** Monotonic nanoseconds (`System.nanoTime`): when the last dialog closed. */
    private val clock: () -> Long = System::nanoTime,
) : HostApprovalRequester {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<HostApprovalRequest?>(null)
    val pending: StateFlow<HostApprovalRequest?> = _pending.asStateFlow()

    /** [clock] when the last dialog left the screen; null before the first one. */
    @Volatile private var lastClosedAt: Long? = null

    /** How many dialogs were ever put on screen: lets a test prove a path never asked. */
    @Volatile internal var shownCount = 0
        private set

    /** A `kino.fetch` of the plugin's own reached a host it never declared; never null (see [HostApprovalRequester]). */
    override suspend fun request(pluginId: String, pluginName: String, host: String): Boolean =
        ask(pluginId, pluginName, host, HostApprovalReason.FETCH)

    /**
     * A URL of the Stream a `resolve` returned, or a host the player met mid-playback, is on a host
     * the plugin never declared (see [StreamHostApproval]): the same dialog, worded by [reason].
     * BACK or a tap outside answers false (`MainActivity`), and cancelling the caller -- the person
     * leaving the player clears its ViewModel -- takes the dialog down and returns nothing.
     */
    suspend fun requestUntilAnswered(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): Boolean =
        ask(pluginId, pluginName, host, reason)

    private suspend fun ask(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): Boolean =
        mutex.withLock {
            try {
                awaitAnswer(pluginId, pluginName, host, reason)
            } finally {
                lastClosedAt = clock()
            }
        }

    private fun armDelayMs(): Long {
        val closed = lastClosedAt ?: return ARM_DELAY_MS
        return if ((clock() - closed) / 1_000_000 < SUCCESSOR_WINDOW_MS) SUCCESSOR_ARM_DELAY_MS else ARM_DELAY_MS
    }

    private suspend fun awaitAnswer(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): Boolean =
        suspendCancellableCoroutine { cont ->
            var req: HostApprovalRequest? = null
            req = HostApprovalRequest(pluginId, pluginName, host, reason, armDelayMs()) { approved ->
                _pending.compareAndSet(req, null)
                if (cont.isActive) cont.resume(approved) {}
            }
            cont.invokeOnCancellation { _pending.compareAndSet(req, null) }
            if (cont.isActive) {
                shownCount++
                _pending.value = req
                // A cancellation landing between the check above and the publish ran
                // its handler BEFORE `req` was published, so that handler's CAS found
                // nothing to clear: re-check, or the dialog would stay up, orphaned,
                // until some unrelated later request overwrote it.
                if (!cont.isActive) _pending.compareAndSet(req, null)
            }
        }

    companion object {
        /** How long a dialog ignores every answer after it appears: a double tap meant for the screen behind it. */
        const val ARM_DELAY_MS = 400L

        /** The same, for a dialog that follows another one closely (see the class KDoc). */
        const val SUCCESSOR_ARM_DELAY_MS = 1_200L

        /** "Closely": appearing less than this after the previous dialog closed. */
        const val SUCCESSOR_WINDOW_MS = 3_000L
    }
}
