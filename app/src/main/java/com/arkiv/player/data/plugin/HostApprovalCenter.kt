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

/**
 * What the person chose in a host dialog. [ALLOW_ANY_VIDEO_HOST] is the broad video permission
 * ([InstalledRecord.anyVideoHost]): only ever an answer to a dialog that offered it
 * ([HostApprovalRequest.offersAnyVideoHost]).
 */
enum class HostApprovalAnswer { REJECT, ALLOW_HOST, ALLOW_ANY_VIDEO_HOST }

/** One pending dialog. [answer] resumes the coroutine that's waiting inside [HostApprovalCenter]. */
data class HostApprovalRequest(
    val pluginId: String,
    val pluginName: String,
    val host: String,
    val reason: HostApprovalReason = HostApprovalReason.FETCH,
    /** How long the dialog ignores every answer after it appears (see [HostApprovalCenter.SUCCESSOR_ARM_DELAY_MS]). */
    val armDelayMs: Long = HostApprovalCenter.ARM_DELAY_MS,
    /**
     * The dialog also offers the broad video permission ("Permitir video de cualquier servidor"):
     * only for a VOD stream's video, subtitle or audio host, at resolve or playback time -- never a
     * `kino.fetch`, a DRM license or a live channel (see [StreamHostApproval]).
     */
    val offersAnyVideoHost: Boolean = false,
    /** "Permitir este servidor" is possible: false when the plugin's 20 approved hosts are all taken. */
    val allowsThisHost: Boolean = true,
    private val onAnswer: (HostApprovalAnswer) -> Unit,
) {
    /**
     * The person's choice. One the dialog does not offer ([ALLOW_ANY_VIDEO_HOST][HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST]
     * without [offersAnyVideoHost], [ALLOW_HOST][HostApprovalAnswer.ALLOW_HOST] without [allowsThisHost])
     * is ignored: the question stays up.
     */
    fun answer(answer: HostApprovalAnswer) {
        if (answer == HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST && !offersAnyVideoHost) return
        if (answer == HostApprovalAnswer.ALLOW_HOST && !allowsThisHost) return
        onAnswer(answer)
    }

    /** "Permitir" (this host) or "Rechazar"; BACK and a tap outside are `respond(false)`. */
    fun respond(approved: Boolean) = answer(if (approved) HostApprovalAnswer.ALLOW_HOST else HostApprovalAnswer.REJECT)

    /** What is on [host]: the head of [question]. */
    private val what: String get() = when (reason) {
        HostApprovalReason.FETCH -> "Quiere conectarse a $host"
        HostApprovalReason.VIDEO -> "El video está en $host"
        HostApprovalReason.LICENSE -> "La licencia del video está en $host"
        HostApprovalReason.SUBTITLE -> "Los subtítulos están en $host"
        HostApprovalReason.AUDIO -> "Un audio del video está en $host"
    }

    /**
     * The sentence under the plugin's name in the dialog. A fetch keeps the sentence it always had;
     * a Stream's URL says what is on that host, since by then the plugin already answered and it is
     * the video (or its subtitles, audio or license) that would come from there. With no room for
     * one more host it says so, and only the broad permission (or "Rechazar") is offered.
     */
    val question: String get() = when {
        reason == HostApprovalReason.FETCH -> "Quiere conectarse por primera vez a $host. ¿Permitir?"
        !allowsThisHost -> "$what, pero $pluginName ya tiene el máximo de ${ManifestParser.MAX_HOSTS} servidores aprobados."
        else -> "$what, un servidor nuevo para este plugin. ¿Permitir?"
    }

    /** The "allow this host" button's label: plain "Permitir" unless the broad choice sits next to it. */
    val allowHostLabel: String get() = if (offersAnyVideoHost) "Permitir este servidor" else "Permitir"

    /** Under [question] when [offersAnyVideoHost]: what the third button does, and what it never does. */
    val anyVideoHostNote: String get() =
        "Con \u201cvideo de cualquier servidor\u201d no te volveremos a preguntar por el video de $pluginName. " +
            "Nunca incluye tu red local, y puedes quitarlo en Plugins."

    companion object {
        /** The third button (see [offersAnyVideoHost]). */
        const val ANY_VIDEO_HOST_LABEL = "Permitir video de cualquier servidor"
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
    private val log: (String) -> Unit = { android.util.Log.i("KinoPlugin", it) },
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
        ask(pluginId, pluginName, host, HostApprovalReason.FETCH) == HostApprovalAnswer.ALLOW_HOST

    /**
     * A URL of the Stream a `resolve` returned, or a host the player met mid-playback, is on a host
     * the plugin never declared (see [StreamHostApproval]): the same dialog, worded by [reason].
     * BACK or a tap outside answers false (`MainActivity`), and cancelling the caller -- the person
     * leaving the player clears its ViewModel -- takes the dialog down and returns nothing.
     */
    suspend fun requestUntilAnswered(pluginId: String, pluginName: String, host: String, reason: HostApprovalReason): Boolean =
        ask(pluginId, pluginName, host, reason) == HostApprovalAnswer.ALLOW_HOST

    /**
     * [requestUntilAnswered] for a VOD stream's host, whose dialog may also offer the broad video
     * permission ([offerAnyVideoHost]) and may have no room left for this one host ([allowsThisHost]
     * false: then only "Rechazar" and the broad permission are offered). The whole answer.
     */
    suspend fun askStreamHost(
        pluginId: String,
        pluginName: String,
        host: String,
        reason: HostApprovalReason,
        offerAnyVideoHost: Boolean,
        allowsThisHost: Boolean = true,
    ): HostApprovalAnswer = ask(pluginId, pluginName, host, reason, offerAnyVideoHost, allowsThisHost)

    private suspend fun ask(
        pluginId: String,
        pluginName: String,
        host: String,
        reason: HostApprovalReason,
        offerAnyVideoHost: Boolean = false,
        allowsThisHost: Boolean = true,
    ): HostApprovalAnswer =
        mutex.withLock {
            val shownAt = clock()
            var answer: HostApprovalAnswer? = null
            try {
                awaitAnswer(pluginId, pluginName, host, reason, offerAnyVideoHost, allowsThisHost).also { answer = it }
            } finally {
                lastClosedAt = clock()
                val ms = (lastClosedAt!! - shownAt) / 1_000_000
                log("[$pluginId] host dialog $reason $host " + when (answer) {
                    HostApprovalAnswer.ALLOW_HOST -> "answered Permitir after $ms ms"
                    HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST -> "answered Permitir video de cualquier servidor after $ms ms"
                    HostApprovalAnswer.REJECT -> "answered Rechazar (or Back) after $ms ms"
                    null -> "taken down unanswered after $ms ms (nobody waits for it any more)"
                })
            }
        }

    private fun armDelayMs(): Long {
        val closed = lastClosedAt ?: return ARM_DELAY_MS
        return if ((clock() - closed) / 1_000_000 < SUCCESSOR_WINDOW_MS) SUCCESSOR_ARM_DELAY_MS else ARM_DELAY_MS
    }

    private suspend fun awaitAnswer(
        pluginId: String,
        pluginName: String,
        host: String,
        reason: HostApprovalReason,
        offerAnyVideoHost: Boolean,
        allowsThisHost: Boolean,
    ): HostApprovalAnswer =
        suspendCancellableCoroutine { cont ->
            var req: HostApprovalRequest? = null
            req = HostApprovalRequest(pluginId, pluginName, host, reason, armDelayMs(), offerAnyVideoHost, allowsThisHost) { answer ->
                _pending.compareAndSet(req, null)
                if (cont.isActive) cont.resume(answer) {}
            }
            cont.invokeOnCancellation { _pending.compareAndSet(req, null) }
            if (cont.isActive) {
                shownCount++
                val extra = (if (offerAnyVideoHost) ", offers the broad video permission" else "") + (if (!allowsThisHost) ", no room for this host" else "")
                log("[$pluginId] host dialog $reason $host shown$extra, arms in ${req!!.armDelayMs} ms")
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
