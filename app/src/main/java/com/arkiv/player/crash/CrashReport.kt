package com.arkiv.player.crash

import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * An error report, shaped like PocketBase's `crash_logs` collection used to be -Task 9
 * (sub-project 2B) took the upload away (see `Crash`'s KDoc), but the names stayed that way
 * because the consumer is still the same: reading the JSON by hand-.
 *
 * Pure data on purpose: nothing in here touches Android or the network, so it can be built inside
 * the uncaught-exception handler (where the process is already dying and there's no room to
 * initialize anything).
 */
data class CrashReport(
    val kind: String,
    val appVersion: String,
    /** Android version + brand and model. Goes to the collection's `android` field. */
    val system: String,
    /** `true` if the error killed the process; `false` if it was reported by hand while alive. */
    val fatal: Boolean,
    /** Thread where it crashed, or the tag whoever reported it by hand passed in. */
    val context: String,
    val message: String,
    val stacktrace: String,
    val logcat: String,
    val occurredAt: String,
) {
    /**
     * The fields are trimmed with the same cap PocketBase's `crash_logs` collection used to have
     * -even though the report no longer uploads anywhere (see `Crash`'s KDoc), the cap still keeps
     * a giant local JSON from happening. It already happened once, with the logcat untrimmed.
     */
    fun toJson(): String = JSONObject()
        .put("kind", kind.take(50))
        .put("app_version", appVersion.take(200))
        .put("android", system.take(300))
        .put("fatal", fatal)
        .put("contexto", context.take(400))
        .put("mensaje", message.take(MESSAGE_CAP))
        // The stacktrace's head: the exception and the top frames.
        .put("stacktrace", stacktrace.take(STACKTRACE_CAP))
        // The logcat's tail: what matters is the LAST thing that happened before it crashed.
        .put("logcat", logcat.takeLast(LOGCAT_CAP))
        .put("ocurrido_en", occurredAt.take(60))
        .toString()

    companion object {
        private const val MESSAGE_CAP = 1_000
        private const val STACKTRACE_CAP = 40_000
        private const val LOGCAT_CAP = 200_000

        /**
         * The full stack WITH the chained causes ("Caused by:").
         *
         * `printStackTrace` and not `Log.getStackTraceString`: the second is Android's, so on a
         * JVM test it returns empty and there'd be no honest way to prove the cause travels.
         */
        fun stacktraceOf(t: Throwable): String {
            val sw = StringWriter()
            PrintWriter(sw).use { t.printStackTrace(it) }
            return sw.toString()
        }

        /**
         * The same report with the logcat emptied out. Written for retrying an upload that was too
         * big to fit -- that upload path (`CrashUploader`, PocketBase) was removed in Task 9
         * (sub-project 2B, see `Crash`'s KDoc), so this function currently has no caller outside
         * its own test. If it's ever wired to something new, keep the non-JSON passthrough: losing
         * the retry is worse than passing along something the new consumer can't use either.
         */
        fun withoutLogcat(json: String): String =
            runCatching { JSONObject(json).put("logcat", "").toString() }.getOrDefault(json)

        /** One line summarizing the error -- meant for scanning a list of reports at a glance
         *  without opening each one (originally the PocketBase admin's list, now `adb logcat`). */
        fun messageOf(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() }
                ?.let { "${t.javaClass.name}: $it" }
                ?: t.javaClass.name
    }
}
