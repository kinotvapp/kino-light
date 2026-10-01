package com.arkiv.player.cast

/**
 * Decides what the phone does when the receiver drops its media ON ITS OWN.
 *
 * Exists because media3's CastPlayer reports nothing for it: a receiver starved by a slow network
 * goes IDLE (reason ERROR, INTERRUPTED or an early FINISHED), the CastPlayer quietly reports an
 * empty idle player, and the phone kept saying "Reproduciendo en Chromecast" over a TV showing its
 * idle screen until the session itself died about five minutes later (measured 2026-10-01 on the
 * KALLEY: `receiver state: 1`, then `item=NONE` for minutes, no error anywhere).
 *
 * The answer is one automatic retry from the last position the receiver reported, then asking the
 * person ("Reintentar" / "Ver en el celular"). Never a loop: once asked, nothing is retried until
 * the person answers, and the automatic retry only comes back after the receiver has played
 * steadily again for [STEADY_MS].
 *
 * Pure and main-thread confined (the Cast SDK calls back on the main thread): no Android types.
 */
class CastIdleWatch(private val clock: () -> Long = System::currentTimeMillis) {

    /** `MediaStatus.IDLE_REASON_*`, by name. */
    enum class Idle {
        NONE, FINISHED, CANCELED, INTERRUPTED, ERROR;

        companion object {
            fun fromCast(code: Int): Idle = when (code) {
                1 -> FINISHED
                2 -> CANCELED
                3 -> INTERRUPTED
                4 -> ERROR
                else -> NONE
            }
        }
    }

    sealed interface Decision {
        /** Not a failure, or one the person is already being asked about. */
        data object Ignore : Decision

        /** Reload the same request from [fromMs] (null: from the request's own start). */
        data class Retry(val fromMs: Long?) : Decision

        /** Already retried: show the choice, resuming from [fromMs] if the person retries. */
        data class Ask(val fromMs: Long?) : Decision
    }

    private var episodeId: String? = null
    private var lastKnownMs: Long? = null
    private var retried = false
    private var retryFromMs = 0L
    private var asking = false
    private var lastOwnLoadAt = Long.MIN_VALUE / 2
    private var stoppedOnPurpose = false

    /**
     * A genuinely new request starting at [startMs]: full budget again, and the last known
     * position becomes that start -- the freshest one there is, since the request was built from
     * where the person was. Keeping the receiver's position from an EARLIER load of the same title
     * retried a new cast from where the previous session had been (`Retry(fromMs=511422)` for a
     * load sent from 642833 ms, 2026-10-01); a start of 0 knows nothing, and a retry then uses the
     * request's own start.
     */
    fun onNewMedia(episodeId: String, startMs: Long = 0L) {
        lastKnownMs = startMs.takeIf { it > 0L }
        this.episodeId = episodeId
        retried = false
        asking = false
        stoppedOnPurpose = false
    }

    /** We just handed the receiver a load: the INTERRUPTED it causes on the old media is ours. */
    fun onOwnLoad() {
        lastOwnLoadAt = clock()
        stoppedOnPurpose = false
    }

    /** The person pressed stop: the idle that follows is what they asked for. */
    fun onIntentionalStop() {
        stoppedOnPurpose = true
    }

    /** The receiver reports [positionMs] (its own timeline) for [episodeId]. */
    fun onPosition(episodeId: String?, positionMs: Long, playing: Boolean) {
        if (episodeId == null || episodeId != this.episodeId || positionMs <= 0L) return
        lastKnownMs = positionMs
        if (retried && playing && positionMs >= retryFromMs + STEADY_MS) {
            // The incident is over: a later, unrelated drop gets its automatic retry again.
            retried = false
        }
    }

    /** The last position the receiver reported for [episodeId] in this run, or null. */
    fun lastKnownMs(episodeId: String): Long? = lastKnownMs?.takeIf { episodeId == this.episodeId }

    /** The person chose "Reintentar": one load, and a new failure asks again. */
    fun onUserRetry() {
        asking = false
    }

    /**
     * A retry was sent and the receiver never got to play it (a load that fails without ever
     * leaving idle reports no new idle): ask, if not asked already.
     */
    fun onRetryStalled(): Decision {
        if (!retried || asking) return Decision.Ignore
        asking = true
        return Decision.Ask(lastKnownMs)
    }

    /** The receiver went IDLE for [reason] while [episodeId] was what we had asked of it. */
    fun onIdle(reason: Idle, episodeId: String?, durationMs: Long): Decision {
        if (episodeId == null || episodeId != this.episodeId || stoppedOnPurpose) return Decision.Ignore
        when (reason) {
            Idle.NONE, Idle.CANCELED -> return Decision.Ignore
            Idle.INTERRUPTED -> if (clock() - lastOwnLoadAt < LOAD_GRACE_MS) return Decision.Ignore
            Idle.FINISHED -> {
                val at = lastKnownMs ?: 0L
                if (durationMs > 0L && at >= durationMs - END_SLACK_MS) return Decision.Ignore
            }
            Idle.ERROR -> Unit
        }
        if (asking) return Decision.Ignore
        val from = lastKnownMs
        if (!retried) {
            retried = true
            retryFromMs = from ?: 0L
            return Decision.Retry(from)
        }
        asking = true
        return Decision.Ask(from)
    }

    companion object {
        /** After our own load, an INTERRUPTED on the receiver is the old media making way. */
        const val LOAD_GRACE_MS = 10_000L

        /** A FINISHED this close to the title's end is the end, not a drop. */
        const val END_SLACK_MS = 90_000L

        /** Played this far past the retry point, the drop is over and the auto-retry comes back. */
        const val STEADY_MS = 120_000L
    }
}
