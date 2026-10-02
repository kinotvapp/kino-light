package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import com.arkiv.player.playback.SourceKind

/**
 * A VOD stream that lost its connection after it had already played, recovered without the person
 * seeing an error (`PlayerErrorRoute.NETWORK_RETRY`). Pure, so the decisions can be pinned down.
 *
 * Found on a real phone (2026-10-01): a Xuper movie (progressive MPEG-TS from a CDN, with headers),
 * paused, Kino in the background ~40 s, back -> "Xuper: No se pudo conectar con el servidor del
 * video" (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`) and nothing more. A paused progressive load
 * keeps its HTTP connection open and idle; the CDN closes it, and ExoPlayer's own load retries run
 * out while the app is in the background (where some phones cut an app's network outright). Nothing
 * was wrong with the title: a `prepare()` once the app is back in front reopened it at the same spot.
 * The stream's URL/token may also have expired meanwhile, which only a fresh `resolve` fixes, so the
 * second attempt of a plugin title goes back through its source ([NetworkRetryStep.RE_RESOLVE]).
 *
 * Only for an error that is the network's ([isNetworkError]), on a VOD (a live channel has its own
 * reopen budget, `PluginLiveReopens`), and only once the stream had been READY: a stream that never
 * opened is an error at once, as before -- retrying a wrong URL would only delay it.
 */
internal object VodNetworkRecovery {
    /** Attempts per player before the error takes its usual route (side audio blamed, then the error dialog). */
    const val MAX_RETRIES = 3

    /**
     * The connection failed or timed out (an idle connection the CDN closed, no network for a
     * moment), or the server answered an HTTP error (an expired token's 403/410, a 5xx). Every other
     * IO error -- a content type nothing reads, a local file, cleartext refused -- would fail the
     * same way again.
     */
    fun isNetworkError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> true
        else -> false
    }

    /**
     * Whether this error is recovered here: a network error ([isNetworkError]) of a VOD ([live]
     * false) whose player had been READY at least once ([everReady]), with attempts left
     * ([retriesSpent] of [MAX_RETRIES] already used since it was last READY).
     */
    fun shouldRetry(errorCode: Int, live: Boolean, everReady: Boolean, retriesSpent: Int): Boolean =
        !live && everReady && retriesSpent < MAX_RETRIES && isNetworkError(errorCode)

    /**
     * What attempt number [attempt] (1-based) does: the first one re-prepares the same URL in place
     * (enough when the CDN only closed an idle connection); the second resolves the stream again
     * through its source when there is one to ask ([canReResolve]) -- a fresh URL/token -- and
     * re-prepares otherwise; the third re-prepares. A re-resolve rebuilds the player, whose own
     * count starts over ([NetworkReResolveBudget] is what bounds that); a rebuilt stream that never
     * opens is an error at once, like any stream that never opened. The rebuilt player also decides
     * the audio language again, which is why the first attempt keeps the same player.
     */
    fun step(attempt: Int, canReResolve: Boolean): NetworkRetryStep =
        if (attempt == 2 && canReResolve) NetworkRetryStep.RE_RESOLVE else NetworkRetryStep.REPREPARE

    /**
     * The wait before attempt [attempt], counted from when the app is back in front, plus a
     * backoff on the [recent] recoveries of this player overall ([NetworkRecoveryBudget]): a CDN
     * that opens, reaches READY and dies again resets [attempt] every time, and was retried every
     * 0.5-4 s for as long as it kept doing it (review 2026-10-01).
     */
    fun delayMs(attempt: Int, recent: Int = 0): Long {
        val base = when {
            attempt <= 1 -> 500L
            attempt == 2 -> 1_500L
            else -> 4_000L
        }
        val extra = if (recent <= MAX_RETRIES) 0L else minOf(MAX_BACKOFF_MS, 2_000L shl (recent - MAX_RETRIES - 1).coerceAtMost(4))
        return base + extra
    }

    /** The longest backoff [delayMs] adds. */
    const val MAX_BACKOFF_MS = 30_000L

    /**
     * How long, overall since the stream was last READY, a recovery waits for the device to have a
     * network again ([offlineWaitMs]) before it tries anyway and lets the attempts decide.
     */
    const val MAX_OFFLINE_WAIT_MS = 150_000L

    /**
     * How long the next attempt waits for a validated network first: none when there is one
     * ([networkUp]); otherwise what is left of [MAX_OFFLINE_WAIT_MS] after [waitedMs]. Found on the
     * Redmi (2026-10-01): the Wi-Fi dropped on its own after a resume, the three attempts (one a
     * re-resolve that failed on DNS) ran out in ~15 s and the error stayed on screen although the
     * Wi-Fi was back 16 s later. An attempt without a network only burns the budget.
     */
    fun offlineWaitMs(networkUp: Boolean, waitedMs: Long): Long =
        if (networkUp) 0L else (MAX_OFFLINE_WAIT_MS - waitedMs).coerceAtLeast(0L)

    /**
     * Whether a VOD's final error offers "Reintentar" (and retries once by itself when the network
     * comes back, [NetworkReturn]): a network error ([isNetworkError]) not of a live channel, which
     * has its own reopen path. Opened or not: a stream that failed while the device was offline
     * is worth one more try either way.
     */
    fun offersRetry(errorCode: Int, live: Boolean): Boolean = !live && isNetworkError(errorCode)

    /**
     * Whether a title may be resolved again after a network error: a plugin's VOD title (the only
     * kind with a source to ask for a fresh URL), not while the person is being asked about a host
     * ([hostQuestionOpen]: their answer rebuilds the player already). Never asked while casting: the
     * player defers its recovery until the cast ends, because the cast follows the item and
     * republishing it would cast it again over what the TV is playing.
     */
    fun canReResolve(kind: SourceKind?, live: Boolean, hostQuestionOpen: Boolean): Boolean =
        kind == SourceKind.PLUGIN && !live && !hostQuestionOpen
}

/**
 * Says when the network has come back, once: the first "up" after a "down". A network that is up
 * when the error shows (the server failed, not the device) only counts after it drops and returns.
 */
internal class NetworkReturn {
    private var sawDown = false
    private var fired = false

    /** Feeds the current state; true exactly once, on the first [up] that follows a down. */
    fun onState(up: Boolean): Boolean {
        if (fired) return false
        if (!up) {
            sawDown = true
            return false
        }
        if (!sawDown) return false
        fired = true
        return true
    }
}

/**
 * What the screen shows about a VOD's network recovery, shared by the player that recovers
 * (StreamExoPlayer) and the screen that draws it: [waiting] while an attempt waits for the network
 * ("Sin conexión, esperando la red…"), and [canRetry] while a network error is on screen and its
 * player can be reopened ("Reintentar"). Every write names its [owner] (the player's prepared
 * source): a player rebuilt by a re-resolve takes over, and the old one's late reset is ignored.
 */
internal class VodNetworkUi(private val onRetry: () -> Unit = {}) {
    private val _waiting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val waiting: kotlinx.coroutines.flow.StateFlow<Boolean> = _waiting
    private val _canRetry = kotlinx.coroutines.flow.MutableStateFlow(false)
    val canRetry: kotlinx.coroutines.flow.StateFlow<Boolean> = _canRetry
    private var owner: Any? = null
    private var retry: (() -> Unit)? = null

    fun setWaiting(owner: Any, waiting: Boolean) {
        take(owner)
        _waiting.value = waiting
    }

    /** The player that failed on a network error: [reopen] reopens it where it was. */
    fun offerRetry(owner: Any, reopen: () -> Unit) {
        take(owner)
        _waiting.value = false
        retry = reopen
        _canRetry.value = true
    }

    /** "Reintentar", or the network coming back: clears the error ([onRetry]) and reopens, once. */
    fun retry(): Boolean {
        val reopen = retry ?: return false
        retry = null
        _canRetry.value = false
        onRetry()
        reopen()
        return true
    }

    /** The player [owner] went away: nothing of it stays on screen. */
    fun reset(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        retry = null
        _waiting.value = false
        _canRetry.value = false
    }

    private fun take(owner: Any) {
        if (this.owner === owner) return
        this.owner = owner
        retry = null
        _canRetry.value = false
    }
}

/**
 * How many network recoveries one player gets overall: [max] within [windowMs], READY or not in
 * between. [VodNetworkRecovery.MAX_RETRIES] counts since the last READY only, so a stream that
 * opens and dies over and over never ran out of them; past this the error takes its usual route.
 */
internal class NetworkRecoveryBudget(private val max: Int = 8, private val windowMs: Long = 10 * 60_000L) {
    private val taken = ArrayDeque<Long>()

    /** Recoveries spent within the window, as of [nowMs]. */
    fun recent(nowMs: Long): Int {
        while (taken.isNotEmpty() && nowMs - taken.first() >= windowMs) taken.removeFirst()
        return taken.size
    }

    /** Spends one recovery at [nowMs] if the window has one left. */
    fun tryTake(nowMs: Long): Boolean {
        if (recent(nowMs) >= max) return false
        taken.addLast(nowMs)
        return true
    }
}

/** What one network recovery attempt does. See [VodNetworkRecovery.step]. */
internal enum class NetworkRetryStep {
    /** `prepare()` again on the same player: same URL, same position, same play/pause. */
    REPREPARE,

    /** Ask the source for the stream again and rebuild the player at the same position, paused if it was. */
    RE_RESOLVE,
}

/**
 * How many times a title may be resolved again after network errors: [max] within [windowMs].
 * A re-resolve builds a new player, whose own attempts start over ([VodNetworkRecovery.MAX_RETRIES]),
 * so without this cap a stream that never comes back would loop through resolves forever; with it,
 * a long movie paused again an hour later still gets its fresh URL. Fresh on every `load`.
 */
internal class NetworkReResolveBudget(private val max: Int = 2, private val windowMs: Long = 10 * 60_000L) {
    private val taken = ArrayDeque<Long>()

    /** Spends one re-resolve at [nowMs] if the window has one left. */
    fun tryTake(nowMs: Long): Boolean {
        while (taken.isNotEmpty() && nowMs - taken.first() >= windowMs) taken.removeFirst()
        if (taken.size >= max) return false
        taken.addLast(nowMs)
        return true
    }

    fun reset() = taken.clear()
}
