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

    /** What this call's `kino.fetch`es met: the material for telling the person why it failed. */
    val trace = PluginCallTrace()

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

    private val questionsLock = Any()
    private var questionsAsked = 0
    private var declinedAHost = false

    /**
     * Takes one of this call's host questions: null when taken, else why not (for the log) -- ask
     * nothing, fail the host silently -- once [MAX_HOST_QUESTIONS] were put, or once the person said
     * "no" to one in this call. A scraper probing mirror after mirror would otherwise put dialog
     * after dialog while its clock is stopped.
     */
    fun takeHostQuestion(): String? = synchronized(questionsLock) {
        when {
            declinedAHost -> "the person already declined a host in this call"
            questionsAsked >= MAX_HOST_QUESTIONS -> "already $MAX_HOST_QUESTIONS host questions in this call"
            else -> { questionsAsked++; null }
        }
    }

    /** The person said "no" to a host of this call: nothing else is asked for the rest of it. */
    fun hostDeclined() = synchronized(questionsLock) { declinedAHost = true }

    companion object {
        /** `resolve` (the person pressed play) and `episodes` (they opened a series). */
        val ASKING_FUNCTIONS = setOf("resolve", "episodes")

        /** Host questions one call may put to the person; see [takeHostQuestion]. */
        const val MAX_HOST_QUESTIONS = 3
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

/**
 * What one call's `kino.fetch`es met, in order: hosts refused (and why), sites that failed to answer
 * (and how), HTTP errors, and which requests are still waiting for an answer. [PluginHttp] writes it;
 * [PluginRuntime] hands it to the call's failure ([PluginException.trace]) and [PluginFailureText]
 * reads it. Bounded: a runaway scraper can't grow it past [MAX_EVENTS].
 */
class PluginCallTrace {
    enum class Refusal { REJECTED_NOW, REJECTED_BEFORE, NOT_ASKED }
    enum class Failure { DNS, TIMEOUT, NETWORK }

    sealed interface Event { val host: String }
    data class Refused(override val host: String, val why: Refusal) : Event
    data class Failed(override val host: String, val how: Failure) : Event
    data class Answered(override val host: String, val status: Int) : Event

    private val lock = Any()
    private val list = mutableListOf<Event>()
    private val inFlight = LinkedHashMap<String, Int>()

    val events: List<Event> get() = synchronized(lock) { list.toList() }

    /** A request to [host] that went out and has not come back yet, if any. */
    val waitingFor: String? get() = synchronized(lock) { inFlight.keys.lastOrNull() }

    fun refused(host: String, why: Refusal) = add(Refused(host, why))
    fun failed(host: String, how: Failure) = add(Failed(host, how))
    fun answered(host: String, status: Int) = add(Answered(host, status))

    fun started(host: String) = synchronized(lock) { inFlight.merge(host, 1, Int::plus); Unit }
    fun finished(host: String) = synchronized(lock) { inFlight.computeIfPresent(host) { _, n -> (n - 1).takeIf { it > 0 } }; Unit }

    private fun add(e: Event) = synchronized(lock) { if (list.size < MAX_EVENTS) list += e }

    /** One line for the log: every event, compact. */
    fun summary(): String = synchronized(lock) {
        (list.map { e ->
            when (e) {
                is Refused -> "${e.host} refused ${e.why}"
                is Failed -> "${e.host} ${e.how}"
                is Answered -> "${e.host} ${e.status}"
            }
        } + inFlight.keys.map { "$it waiting" }).joinToString(", ")
    }

    companion object {
        const val MAX_EVENTS = 100
    }
}
