package com.arkiv.player.data.plugin

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One plugin call's time budget ([budgetMs]: 15 s for `search`, 20 s for the rest), which runs only
 * while nobody is being asked something on the call's behalf. [PluginRuntime.call] waits on it
 * ([awaitWithin]); [PluginHttp] pauses it ([pausedWhile]) for as long as a host prompt of that call
 * is queued or on screen, so the seconds a person spends reading "¿Permitir?" never come out of the
 * plugin's own limit. Everything else keeps its guarantee: a plugin that loops or hangs while NOT
 * waiting for a person spends the budget exactly as before and is stopped when it runs out.
 *
 * Pauses nest: two prompts of the same call (two hosts asked one after the other while both
 * requests are waiting) keep the clock stopped until the last one ends.
 */
class PluginCallClock(val budgetMs: Long, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private var spentMs = 0L
    private var runningSince = nowMs()
    private var pauses = 0

    /** Bumped on every pause, resume and (inside [awaitWithin]) job completion: what the waiter wakes on. */
    private val changes = MutableStateFlow(0L)

    fun pause() {
        synchronized(lock) { if (pauses++ == 0) spentMs += nowMs() - runningSince }
        changes.update { it + 1 }
    }

    fun resume() {
        synchronized(lock) {
            check(pauses > 0) { "resume() without pause()" }
            if (--pauses == 0) runningSince = nowMs()
        }
        changes.update { it + 1 }
    }

    /** What is left of the budget right now; null while paused. */
    fun remainingMs(): Long? = synchronized(lock) {
        if (pauses > 0) null else budgetMs - spentMs - (nowMs() - runningSince)
    }

    /** Runs [block] with the clock stopped, however [block] ends. */
    suspend fun <T> pausedWhile(block: suspend () -> T): T {
        pause()
        try {
            return block()
        } finally {
            resume()
        }
    }

    /**
     * Waits for [job]: true once it completed, false as soon as the unpaused time reached [budgetMs]
     * first. While paused it waits for as long as the pause lasts.
     */
    suspend fun awaitWithin(job: Job): Boolean {
        val handle = job.invokeOnCompletion { changes.update { it + 1 } }
        try {
            while (true) {
                // Read BEFORE checking: a change landing after this read wakes the wait below.
                val seen = changes.value
                if (job.isCompleted) return true
                val left = remainingMs()
                if (left != null && left <= 0) return false
                withTimeoutOrNull(left ?: Long.MAX_VALUE) { changes.first { it != seen } }
            }
        } finally {
            handle.dispose()
        }
    }
}
