package com.arkiv.player.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local error report's payload (see `CrashReport`'s KDoc: it's shaped like PocketBase's
 * `crash_logs` collection used to be, even though it no longer uploads anywhere).
 *
 * What matters here is the chained CAUSE: almost everything that crashes in the app arrives
 * wrapped (a `RuntimeException` around the real one), so a stacktrace that cuts off at the outer
 * one says nothing about why it actually failed.
 */
class CrashReportTest {
    private fun report(
        stacktrace: String = "java.lang.RuntimeException: algo",
        message: String = "java.lang.RuntimeException: algo",
        logcat: String = "",
    ) = CrashReport(
        kind = "phone",
        appVersion = "1.4.2 (142) release",
        system = "Android 14 (SDK 34) · samsung SM-S926B",
        fatal = true,
        context = "main",
        message = message,
        stacktrace = stacktrace,
        logcat = logcat,
        occurredAt = "2026-08-19T21:00:00Z",
    )

    @Test
    fun `the stacktrace carries the outer exception and its cause`() {
        val root = IllegalStateException("no habia stream")
        val wrapped = RuntimeException("se cayo el player", root)

        val text = CrashReport.stacktraceOf(wrapped)

        assertTrue(text, text.contains("java.lang.RuntimeException: se cayo el player"))
        assertTrue(text, text.contains("Caused by: java.lang.IllegalStateException: no habia stream"))
        assertTrue("missing stack frame", text.contains("CrashReportTest"))
    }

    @Test
    fun `the message summarizes the exception in one line`() {
        val t = IllegalArgumentException("tmdbId vacio")

        assertEquals("java.lang.IllegalArgumentException: tmdbId vacio", CrashReport.messageOf(t))
    }

    @Test
    fun `an exception with no text still says what class it is`() {
        assertEquals("java.lang.NullPointerException", CrashReport.messageOf(NullPointerException()))
    }

    @Test
    fun `toJson writes the fields with the collection's names`() {
        val json = JSONObject(report(logcat = "linea de log").toJson())

        assertEquals("phone", json.getString("kind"))
        assertEquals("1.4.2 (142) release", json.getString("app_version"))
        assertEquals("Android 14 (SDK 34) · samsung SM-S926B", json.getString("android"))
        assertEquals(true, json.getBoolean("fatal"))
        assertEquals("main", json.getString("contexto"))
        assertEquals("java.lang.RuntimeException: algo", json.getString("mensaje"))
        assertEquals("java.lang.RuntimeException: algo", json.getString("stacktrace"))
        assertEquals("linea de log", json.getString("logcat"))
        assertEquals("2026-08-19T21:00:00Z", json.getString("ocurrido_en"))
    }

    /**
     * The emergency cutback: if the whole report doesn't fit, the logcat is the first thing let
     * go. The stacktrace is the one thing that can't be reconstructed afterward.
     */
    @Test
    fun `withoutLogcat empties the logcat and leaves the rest intact`() {
        val trimmed = JSONObject(CrashReport.withoutLogcat(report(logcat = "cuarenta mil lineas").toJson()))

        assertEquals("", trimmed.getString("logcat"))
        assertEquals("java.lang.RuntimeException: algo", trimmed.getString("stacktrace"))
        assertEquals("phone", trimmed.getString("kind"))
    }

    @Test
    fun `withoutLogcat on something that isn't json returns it as-is`() {
        assertEquals("esto no es json", CrashReport.withoutLogcat("esto no es json"))
    }

    /**
     * Same cap PocketBase's `crash_logs` collection used to have (see `CrashReport.toJson`'s
     * KDoc). It already happened once, with the logcat untrimmed.
     */
    @Test
    fun `toJson trims the fields that wouldn't fit in the collection`() {
        val json = JSONObject(
            report(
                message = "x".repeat(9_000),
                stacktrace = "y".repeat(90_000),
                logcat = "z".repeat(400_000),
            ).toJson(),
        )

        assertEquals(1_000, json.getString("mensaje").length)
        assertEquals(40_000, json.getString("stacktrace").length)
        assertEquals(200_000, json.getString("logcat").length)
    }

    /** What matters about the logcat is the LAST thing that happened, not the first: it's trimmed from the front. */
    @Test
    fun `trimming the logcat keeps the end`() {
        val json = JSONObject(report(logcat = "viejo".padEnd(400_000, 'x') + "LO ULTIMO").toJson())

        assertTrue(json.getString("logcat").endsWith("LO ULTIMO"))
    }

    /** What matters about the stacktrace is the head: the exception and the top frames. */
    @Test
    fun `trimming the stacktrace keeps the beginning`() {
        val json = JSONObject(report(stacktrace = "LA EXCEPCION" + "y".repeat(90_000)).toJson())

        assertTrue(json.getString("stacktrace").startsWith("LA EXCEPCION"))
    }
}
