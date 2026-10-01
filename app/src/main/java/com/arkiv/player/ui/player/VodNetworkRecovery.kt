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

    /** The wait before attempt [attempt], counted from when the app is back in front. */
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 500L
        attempt == 2 -> 1_500L
        else -> 4_000L
    }

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
