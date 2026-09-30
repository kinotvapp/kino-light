package com.arkiv.player.ui.player

import com.arkiv.player.data.InProgressMark
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The player's play attempts and the early history mark each one writes.
 *
 * `load()` marks the chapter "in progress" before the source resolves (see
 * `ArkivRepository.markInProgress`), and that mark must not outlive an attempt that never really
 * started. An attempt STARTS when the player is playing with a known duration ([started]); until
 * then its mark is pending. It ENDS when the next [begin] comes (another chapter, a retry, a live
 * channel), when it [failed] (nothing could be published), or when the player goes away ([close]).
 * An attempt that ends without having started has its mark undone ([undo]); one that started keeps
 * it, and from then on it's the player's normal saves that write the row.
 *
 * Every mark and every undo runs under one lock and in call order, so two quick loads can't
 * interleave: the second one's mark is always written after the first one's undo, even for the same
 * chapter (a retry). [open] is synchronous, called on the main thread by `load()` itself, so an
 * attempt that got superseded before its coroutine ran never writes a mark at all.
 */
internal class PlaybackAttempts(
    private val mark: suspend (episodeId: String) -> InProgressMark?,
    private val undo: suspend (InProgressMark) -> Unit,
) {
    /** One `load()`. Compared by identity: the same chapter loaded twice is two attempts. */
    class Attempt internal constructor(val episodeId: String) {
        @Volatile
        var started: Boolean = false
            internal set
    }

    private val lock = Mutex()

    @Volatile
    private var current: Attempt? = null

    /** The one attempt whose mark is written and not yet settled. Guarded by [lock]. */
    private var pending: Pair<Attempt, InProgressMark>? = null

    /** A new attempt, current from this instant on. */
    fun open(episodeId: String): Attempt = Attempt(episodeId).also { current = it }

    /** Whether [attempt] is still the player's latest one (no other load came after it). */
    fun isCurrent(attempt: Attempt): Boolean = attempt === current

    /**
     * Settles the attempt before [attempt] (undoing its mark if it never started) and then, if
     * [writeMark] and [attempt] is still the current one, writes [attempt]'s mark.
     */
    suspend fun begin(attempt: Attempt, writeMark: Boolean) = lock.withLock {
        settlePending()
        if (writeMark && attempt === current) mark(attempt.episodeId)?.let { pending = attempt to it }
    }

    /** The player is playing [episodeId] with a known duration: its attempt keeps its mark. */
    fun started(episodeId: String) {
        current?.takeIf { it.episodeId == episodeId }?.started = true
    }

    /** [attempt] couldn't publish anything to play: its mark goes now, not when the player closes. */
    suspend fun failed(attempt: Attempt) = lock.withLock {
        if (pending?.first === attempt) settlePending()
    }

    /** The player is gone: nothing is current any more and the pending attempt is settled. */
    suspend fun close() {
        current = null
        lock.withLock { settlePending() }
    }

    private suspend fun settlePending() {
        val (attempt, written) = pending ?: return
        pending = null
        if (!attempt.started) undo(written)
    }
}
