package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A plugin's own `kino.log` lines reaching the error tracker: the ring, what is kept of it, and the rules (only for a
 * FAILED call, only for catalog plugins, scrubbed and capped).
 */
class PluginLogReportTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- the ring ---

    @Test fun `the ring keeps the last 30 lines, each cut at 300 characters`() {
        val b = PluginLogBuffer()
        repeat(40) { b.record("p", "line $it " + "x".repeat(500)) }
        val lines = b.since("p", 0)
        assertEquals(PluginLogBuffer.MAX_LINES, lines.size)
        assertTrue(lines.first().startsWith("line 10 "))
        assertTrue(lines.last().startsWith("line 39 "))
        assertTrue(lines.all { it.length == PluginLogBuffer.MAX_LINE_CHARS })
    }

    @Test fun `since gives only what was written after the mark, per plugin`() {
        val b = PluginLogBuffer()
        b.record("p", "before")
        b.record("q", "other plugin")
        val mark = b.mark("p")
        b.record("p", "during 1")
        b.record("p", "during 2")
        assertEquals(listOf("during 1", "during 2"), b.since("p", mark))
        assertEquals(emptyList<String>(), b.since("unknown", 0))
        assertEquals(emptyList<String>(), b.since("p", b.mark("p")))
    }

    @Test fun `the number of plugins held is bounded`() {
        val b = PluginLogBuffer(maxPlugins = 2)
        b.record("a", "1"); b.record("b", "2"); b.record("c", "3")
        assertEquals(emptyList<String>(), b.since("a", 0))
        assertEquals(listOf("3"), b.since("c", 0))
    }

    // --- cleaning ---

    @Test fun `urls, hosts, ids, secrets-shaped text and the person's words are scrubbed`() {
        val out = PluginTelemetry.cleanLog(
            listOf(
                "info: GET https://middleware.example.com/a/b?token=abc123 status 403",
                "info: user mail a@b.co ip 10.0.0.5 id 0123456789abcdef0123456789abcdef",
                "warn: base64 aGVsbG8gd29ybGQgdGhpcyBpcyBsb25nIGVub3VnaA== and Authorization: Bearer s3cr3tvalue",
                "info: search for casa de papel found 0",
                "info: my server nas.casa.example port",
            ),
            privateValues = listOf("casa de papel"),
            privateHosts = setOf("nas.casa.example"),
        )!!
        assertTrue(out, "status 403" in out)
        listOf("middleware.example.com", "abc123", "a@b.co", "10.0.0.5", "0123456789abcdef", "aGVsbG8", "s3cr3tvalue", "casa de papel", "nas.casa.example")
            .forEach { assertFalse("$it leaked: $out", it in out) }
    }

    @Test fun `the report is capped at 2 KB and keeps the newest lines`() {
        val lines = (1..30).map { "info: step $it " + "word ".repeat(50) }
        val out = PluginTelemetry.cleanLog(lines)!!
        assertTrue(out.length <= PluginLogBuffer.MAX_REPORT_CHARS)
        assertTrue(out.contains("step 30"))
        assertFalse(out.contains("step 1 "))
    }

    @Test fun `nothing readable left is null`() {
        assertNull(PluginTelemetry.cleanLog(emptyList()))
        assertNull(PluginTelemetry.cleanLog(listOf("   ")))
    }

    // --- what is attached, and when ---

    private val sent = CopyOnWriteArrayList<PluginTelemetry.Event>()

    private fun telemetry(origin: String, values: List<String> = emptyList()) = PluginTelemetry(
        facts = { PluginFacts(version = "1.0.1", apiVersion = 4, origin = origin) },
        sink = { sent += it },
        privateValues = { values },
    )

    private fun thrown() = PluginThrownException("Error: Caracol respondió 403")

    @Test fun `a failed call of a catalog plugin carries its log lines`() {
        telemetry("catalog").reportCall("caracol-tv", "home", thrown(), 600, argJson = null, logLines = listOf("info: GET catalog -> 403", "warn: giving up"))
        assertEquals("info: GET catalog -> 403\nwarn: giving up", sent.single().extras["plugin_log"])
    }

    @Test fun `no log for community, manual, nuvio or unknown plugins`() {
        for (origin in listOf("community_or_manual", "nuvio", "unknown")) {
            sent.clear()
            telemetry(origin).reportCall("p", "home", thrown(), 600, logLines = listOf("info: something"))
            assertNull(origin, sent.single().extras["plugin_log"])
        }
    }

    @Test fun `the person's setting values and query never ride in the log`() {
        telemetry("catalog", values = listOf("my-secret-password")).reportCall(
            "p", "search", thrown(), 10, argJson = """{"q":"la casa de papel"}""",
            logLines = listOf("info: password my-secret-password", "info: searching la casa de papel"),
        )
        val log = sent.single().extras["plugin_log"].orEmpty()
        assertFalse(log, "my-secret-password" in log)
        assertFalse(log, "casa de papel" in log)
    }

    @Test fun `a failure with no log lines has no plugin_log, and report() never has one`() {
        telemetry("catalog").reportCall("p", "home", thrown(), 10)
        assertFalse(sent.single().extras.containsKey("plugin_log"))
        sent.clear()
        telemetry("catalog").report(PluginFailure("p", "home", PluginFailureKind.THROWN, raw = "Error: x y z w"))
        assertFalse(sent.single().extras.containsKey("plugin_log"))
    }

    // --- the caller: only a failed call's own lines, never a success ---

    private class Calls(val body: (String) -> String) : PluginCaller {
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String = body(pluginId)
    }

    @Test fun `a successful call reports nothing and a failed one carries only what it logged`() = runBlocking {
        val buffer = PluginLogBuffer()
        val previous = PluginTelemetry.current
        PluginTelemetry.current = telemetry("catalog")
        try {
            buffer.record("p", "info: from an earlier call")
            val ok = ReportingPluginCaller(buffer, Calls { buffer.record(it, "info: logged during a success"); "[]" })
            ok.call("p", "home", "null", 1000)
            assertTrue(sent.isEmpty())
            val failing = ReportingPluginCaller(buffer, Calls { buffer.record(it, "warn: step two failed"); throw thrown() })
            runCatching { failing.call("p", "home", "null", 1000) }
            assertEquals("warn: step two failed", sent.single().extras["plugin_log"])
        } finally {
            PluginTelemetry.current = previous
        }
    }

    // --- the host: console.* and kino.log land in the buffer; Nuvio's console.error recording is untouched ---

    @Test fun `kino log and console reach the buffer, redacted, and the Nuvio console error reason still works`() {
        val buffer = PluginLogBuffer()
        val hosts = EffectiveHosts(listOf("localhost"))
        val http = PluginHttp(okhttp3.OkHttpClient(), "nuvio-x", hosts, "1.0", allowInsecureLocalhost = true, log = {})
        val host = DefaultPluginHost("nuvio-x", http, PluginStorage(tmp.root.resolve("s.json")), allowInsecureLocalhost = true, logger = {}, logBuffer = buffer)
        val scraper = NuvioScraperEntry("outsrc", "OutSrc", "providers/outsrc.js", true, listOf("es"), listOf("movie"), null, emptyList())
        val source = "async function getStreams() { console.error(\"[OutSrc] Error in getStreams: unexpected token\"); return []; }\nmodule.exports = { getStreams: getStreams };"
        val script = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo").script
        val result = runBlocking {
            val rt = PluginRuntime.open("nuvio-x", script, host, PluginEnv(appVersion = "1.0"))
            try { runCatching { rt.call("resolve", org.json.JSONObject.quote("""{"tmdbId":603,"type":"movie"}"""), 5_000) } } finally { rt.close() }
        }
        val e = result.exceptionOrNull() as PluginErrorException
        assertEquals(PluginErrors.UNAVAILABLE, e.code)
        assertEquals(NuvioPluginConverter.SCRAPER_ERROR_PREFIX + "unexpected token", e.message)
        assertTrue(buffer.since("nuvio-x", 0).toString(), buffer.since("nuvio-x", 0).any { it.startsWith("error: ") && "unexpected token" in it })
    }
}
