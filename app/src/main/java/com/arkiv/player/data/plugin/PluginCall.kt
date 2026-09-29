package com.arkiv.player.data.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * One plugin call while it runs, as the rest of its runtime sees it: [PluginRuntime.call] creates it
 * and ends it (returned, failed, timed out, or its caller gave up), and [PluginHttp] reads it through
 * the runtime's [PluginCallTracker] to know whether anyone is still waiting for an answer.
 *
 * [function] is the export being called; [interactive] is false under [BackgroundPluginCall] (the
 * download queue: nobody on screen). [clock] is the call's own time budget (see [PluginCallClock]).
 */
class PluginCall internal constructor(
    val function: String,
    val interactive: Boolean,
    val clock: PluginCallClock,
) {
    private val ended = Job()

    /** False once the call is over, whatever way it ended. */
    val isAlive: Boolean get() = ended.isActive

    /**
     * Whether a `kino.fetch` of this call may put an undeclared host to the person: only while the
     * call is alive, someone is on screen for it, and it is one the person started by choosing a
     * title ([ASKING_FUNCTIONS]). `search`, `home`, `browse` and the live lists run for many sources
     * at once while the person types or scrolls; a dialog per source there would bury the screen, so
     * their misses fail silently (logged) as they did before reactive approval existed.
     */
    val asksAboutHosts: Boolean get() = isAlive && interactive && function in ASKING_FUNCTIONS

    internal fun end() {
        ended.complete()
    }

    companion object {
        /** `resolve` (the person pressed play) and `episodes` (they opened a series). */
        val ASKING_FUNCTIONS = setOf("resolve", "episodes")
    }

    /**
     * Runs [block] (putting a question to the person) with [clock] paused, for as long as this call
     * is alive: null without running it when the call is already over, and null -- [block]
     * cancelled, so a dialog it put up comes down -- the moment the call ends while it waits. Only
     * the caller's OWN cancellation propagates.
     */
    suspend fun <T> askWhileAlive(block: suspend () -> T): T? {
        if (!isAlive) return null
        return clock.pausedWhile {
            coroutineScope {
                val answer = async { block() }
                val watch = launch {
                    ended.join()
                    answer.cancel()
                }
                try {
                    answer.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    watch.cancel()
                }
            }
        }
    }
}

/**
 * Which call one runtime is running right now, if any: shared by that runtime (the only writer) and
 * its [PluginHttp] (a reader), which `AppGraph` builds before the runtime opens.
 */
class PluginCallTracker {
    @Volatile var current: PluginCall? = null
        private set

    internal fun begin(call: PluginCall) {
        current = call
    }

    internal fun end(call: PluginCall) {
        call.end()
        if (current === call) current = null
    }
}
