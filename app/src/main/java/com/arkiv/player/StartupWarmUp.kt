package com.arkiv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * How long each step of [AppGraph.warmUpCredentials] took, for the `KinoStartup` log line and the
 * "warm-up slow" telemetry (one `phase_<name>_ms` extra per phase). Thread-safe: independent phases
 * run in parallel. A phase that throws is still timed (and the exception goes on to the caller).
 */
internal class WarmUpTimeline(private val clock: () -> Long) {
    private val started = clock()
    private val phases = ConcurrentHashMap<String, Long>()
    private val order = java.util.concurrent.ConcurrentLinkedQueue<String>()

    inline fun <T> phase(name: String, block: () -> T): T {
        val t0 = now()
        try {
            return block()
        } finally {
            record(name, now() - t0)
        }
    }

    fun now(): Long = clock()

    fun record(name: String, ms: Long) {
        if (phases.put(name, ms) == null) order.add(name)
    }

    fun elapsed(): Long = clock() - started

    fun durations(): Map<String, Long> = order.associateWith { phases.getValue(it) }

    /** Telemetry extras: `phase_<name>_ms` for each recorded phase, in the order they finished. */
    fun extras(): Map<String, String> = durations().entries.associate { (k, v) -> "phase_${k}_ms" to v.toString() }

    /** One readable line: `magis=4210 local=380 plugins=900`. */
    fun summary(): String = durations().entries.joinToString(" ") { (k, v) -> "$k=$v" }
}

/** What the activity shows while the app starts. See [startupContent]. */
internal enum class StartupContent {
    /** Nothing behind the splash yet: the intro still has the main thread to itself. */
    NONE,

    /** Waiting for the warm-up (the splash stays up; after a while a "Preparando…" note shows on it). */
    PREPARING,

    /** No credentials: the activation screen. Composing it never touches the Magis chain. */
    ACTIVATION,

    /** The app's root (home). Only once the warm-up has built every heavy lazy it reads. */
    APP,
}

/**
 * The startup gate (ERRORES-7I7/78K/9OA/1T/13/7I4): the root is composed ONLY once the warm-up has
 * finished building, off the main thread, the heavy `by lazy` chain the root reads (Magis portal and
 * session: native 3DES + Keystore). Composing it any earlier made the UI thread wait on the
 * warm-up's lazy lock, or build the chain itself, which ANRs a slow device. There is deliberately no
 * timeout that composes the root anyway: the warm-up's critical part is local work only (no network
 * step is on it), and composing before it finishes is exactly what froze the app.
 *
 * [credentialsRead] is false until the credentials were read off the main thread; [hasCredentials]
 * says whether there were any then.
 */
internal fun startupContent(
    introDone: Boolean,
    warmedUp: Boolean,
    credentialsRead: Boolean,
    hasCredentials: Boolean,
): StartupContent = when {
    !introDone -> StartupContent.NONE
    !warmedUp || !credentialsRead -> StartupContent.PREPARING
    !hasCredentials -> StartupContent.ACTIVATION
    else -> StartupContent.APP
}

/**
 * Reports a heavy lazy (Keystore, native 3DES, Room) that got built on the main thread anyway: the
 * StrictMode-style tripwire behind the startup gate, so a new screen that reads one before the
 * warm-up built it shows up in logcat (debug) and on the error board (release) instead of as an ANR.
 * Each name is reported once per process.
 */
internal class MainThreadInitGuard(
    private val isMainThread: () -> Boolean,
    private val onViolation: (name: String) -> Unit,
) {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun check(name: String) {
        if (!isMainThread()) return
        if (reported.add(name)) onViolation(name)
    }

    fun violations(): Set<String> = reported.toSet()
}
