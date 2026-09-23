package com.arkiv.player.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many times the SAME stream is re-primed before asking Caracol for a new URL. */
internal const val MAX_DITU_REPREPARES = 3

/** How many times a new URL is asked from Caracol for what's playing before giving up. */
internal const val MAX_DITU_RELOADS = 2

/**
 * How much the position has to advance IN A ROW, since the last recovery, for the two caps
 * ([MAX_DITU_REPREPARES] and [MAX_DITU_RELOADS]) to be replenished.
 *
 * A CHOSEN number, not a measured one. What it has to separate is "recovered and playing" from
 * "reached READY and died". Too short, and a stream that starts and drops after a few seconds
 * would replenish the caps on every round and recover forever: it would hammer Caracol's API and
 * the error would never reach the person. Too long, and two legitimate cuts separated by less
 * than this (ads show up several times in a program) would spend the same cap, and the error
 * would arrive too early in a movie that was actually fine.
 */
internal const val STABLE_PLAYBACK_MS = 30_000L

/**
 * The largest advance between two position reads that still counts as playing in a row.
 *
 * [DituExoPlayer]'s clock reads every 500 ms, so playing at 1x each read advances around half a
 * second; this leaves margin for a read that arrives late or a higher speed. A bigger advance
 * isn't playing anymore: it's a jump (a seek), and it cuts the streak.
 */
internal const val MAX_ADVANCE_PER_READ_MS = 3_000L

/**
 * What [PlayerViewModel] decides about Caracol, kept separate and Android-free so it can be
 * tested on the JVM.
 *
 * Takes care of three things:
 *
 * - **That a late resolution doesn't override the current request.** `PlayerViewModel.load`
 *   doesn't cancel the previous load: if a Caracol resolution comes back when another episode
 *   (from any source) has already been requested, publishing it would leave `PlayerScreen`
 *   composing two players at once. [publish] discards it if its episode is no longer the last one
 *   requested ([newRequest]).
 * - **The two recovery steps.** On a recoverable error, [DituExoPlayer] re-primes the same stream
 *   while [requestReprepare] allows it. Once those run out, the `playback_token` carried in the
 *   cookies has most likely expired, and no `prepare()` fixes that: [requestReload] says whether
 *   there's a new URL left to ask for or whether the error has to reach the person. A new URL is a
 *   new stream, so its re-primes start fresh; reloads don't. Even in the worst case (every round
 *   drops right away) everything ends in an error: 3 re-primes, 1 reload, 3, 1, and 3.
 * - **When the caps get replenished.** Never on reaching READY —that would let a stream that
 *   arrives and dies recover forever— but when the position has advanced [STABLE_PLAYBACK_MS] in
 *   a row since the last recovery. The player reports every clock read in [advanced].
 */
internal class DituState(
    private val maxReprepares: Int = MAX_DITU_REPREPARES,
    private val maxReloads: Int = MAX_DITU_RELOADS,
) {

    private val _current = MutableStateFlow<DituReproducible?>(null)

    /** What Caracol has for the screen to play, or `null`. */
    val current: StateFlow<DituReproducible?> = _current.asStateFlow()

    /** The episode requested last, from any source. */
    private var active: String? = null

    private var reprepares = 0
    private var reloads = 0

    /** How much the position has advanced in a row since the last recovery. */
    private var streak = 0L

    /** The last position read, or `null` when a fresh baseline needs to be taken. */
    private var lastPosition: Long? = null

    /** How many things got published. See [DituReproducible.generation]. */
    private var publications = 0

    /** A new request came in: whatever Caracol had stops being valid and the caps are replenished. */
    fun newRequest(episodeId: String) {
        active = episodeId
        reprepares = 0
        reloads = 0
        resetStreak()
        _current.value = null
    }

    /** Drops whatever's there without changing the active request (the live stream, on opening a channel). */
    fun clear() {
        _current.value = null
    }

    fun isActive(episodeId: String): Boolean = episodeId == active

    /** Publishes [r] if its episode is still the active one. Returns whether it published it. */
    fun publish(r: DituReproducible): Boolean {
        if (!isActive(r.episodeId)) return false
        publications++
        _current.value = r.copy(generation = publications)
        return true
    }

    /**
     * An error fixed by re-priming the same stream. `true` = prime it again; `false` = none left,
     * and the error moves on to [requestReload].
     */
    fun requestReprepare(): Boolean {
        if (reprepares >= maxReprepares) return false
        reprepares++
        resetStreak()
        return true
    }

    /**
     * The player gave up on what's playing. Returns the episode a new URL needs to be requested
     * for, or `null` if there are no reloads left (or nothing active is playing): there the error
     * has to reach the person.
     */
    fun requestReload(): String? {
        val playing = _current.value?.episodeId ?: return null
        if (!isActive(playing)) return null
        if (reloads >= maxReloads) return null
        reloads++
        reprepares = 0
        resetStreak()
        return playing
    }

    /**
     * A read of the player's clock. If the position has been advancing in a row for
     * [STABLE_PLAYBACK_MS] since the last recovery, both caps get replenished.
     *
     * "In a row": a pause, a rebuffer, a read with no advance, or a jump (see
     * [MAX_ADVANCE_PER_READ_MS]) reset the streak to zero. Stopping at 20 s and continuing doesn't
     * add 20 + whatever comes next: the full 30 s have to be gathered again.
     */
    fun advanced(positionMs: Long, playing: Boolean) {
        val previous = lastPosition
        lastPosition = positionMs
        val advance = if (previous == null) 0L else positionMs - previous
        if (!playing || advance <= 0L || advance > MAX_ADVANCE_PER_READ_MS) {
            streak = 0L
            return
        }
        streak += advance
        if (streak >= STABLE_PLAYBACK_MS) {
            reprepares = 0
            reloads = 0
        }
    }

    private fun resetStreak() {
        streak = 0L
        lastPosition = null
    }
}
