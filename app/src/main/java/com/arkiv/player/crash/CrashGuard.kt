package com.arkiv.player.crash

import java.time.Instant

/**
 * What goes in every report besides the error itself: whether it's a phone or TV, and the app
 * and system version. Empty if it couldn't be figured out.
 */
data class DeviceData(
    val kind: String,
    val appVersion: String,
    val system: String,
) {
    companion object {
        val UNKNOWN = DeviceData(kind = "", appVersion = "", system = "")
    }
}

/**
 * Builds the report and leaves it in the queue.
 *
 * House rule: **nothing in here can stop the stacktrace from being saved**. If reading the
 * identity fails, or the logcat, or the clock, the report still goes out with whatever's
 * available -- because the stacktrace is the one thing that can't be reconstructed afterward.
 *
 * No Android dependencies on purpose: what's system-specific comes in through [data] and [logcat]
 * (see `CrashAndroid.kt`), so all of this is tested on the JVM.
 */
class CrashGuard(
    private val store: CrashStore,
    private val data: () -> DeviceData,
    private val logcat: () -> String,
    private val now: () -> String = { Instant.now().toString() },
) {
    /** An error that killed the process. Called by [CrashHandler]. Returns the queued file. */
    fun catch(thread: Thread, error: Throwable): java.io.File =
        write(error, context = thread.name, fatal = true)

    /**
     * An error caught by hand, with the process alive. For the `runCatching`s that today swallow
     * the exception silently. Never throws: turning an already-caught error into a new crash
     * would be exactly the opposite of what this came here to do.
     */
    fun report(error: Throwable, tag: String) {
        runCatching { write(error, context = tag, fatal = false) }
    }

    private fun write(error: Throwable, context: String, fatal: Boolean): java.io.File {
        val device = runCatching { data() }.getOrDefault(DeviceData.UNKNOWN)
        val report = CrashReport(
            kind = device.kind,
            appVersion = device.appVersion,
            system = device.system,
            fatal = fatal,
            context = context,
            message = CrashReport.messageOf(error),
            stacktrace = CrashReport.stacktraceOf(error),
            logcat = runCatching { logcat() }.getOrDefault(""),
            occurredAt = runCatching { now() }.getOrDefault(""),
        )
        return store.save(report.toJson())
    }
}

/**
 * The uncaught-exception handler.
 *
 * Saves the report and **passes the ball to the previous handler**: without that, the app would
 * stop dying the way it dies today and a crash would turn into a silent hang. If saving fails,
 * it's ignored and delegated anyway -- the original exception wins.
 */
class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val guard: CrashGuard,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { guard.catch(thread, error) }
        previous?.uncaughtException(thread, error)
    }
}
