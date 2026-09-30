package com.arkiv.player.data.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shim's `AbortController`/`AbortSignal` (latino audit 2026-09-30: sololatino and cuevanaubd
 * construct one and pass `signal` to fetch/axios), run through the REAL QuickJS sandbox like
 * [NuvioShimCompatTest]. Each scraper reports what it saw in its stream URL's path.
 */
class NuvioAbortControllerTest {
    private val scraper = NuvioScraperEntry(
        id = "abortsrc", name = "AbortSrc", filename = "providers/abortsrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    /** Answers `{}` after [delayMs]; counts the requests that reached it. */
    private class SlowHost(private val delayMs: Long) : PluginHost {
        val requests = AtomicInteger(0)
        override suspend fun fetch(requestJson: String): String {
            requests.incrementAndGet()
            delay(delayMs)
            val url = JSONObject(requestJson).getString("url")
            return JSONObject().put("ok", true).put("status", 200).put("url", url).put("headers", JSONObject()).put("text", "{}").toString()
        }
        override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    /** Runs [body] as `async getStreams()`; whatever string it returns lands after `https://cdn.example/`. */
    private fun run(body: String, host: PluginHost, timeoutMs: Long = 5_000): String = runBlocking {
        val source = "async function getStreams() {\n$body\n}\nmodule.exports = { getStreams: getStreams };\n"
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo")
        val runtime = PluginRuntime.open("probe", result.script, host, PluginEnv(appVersion = "1.0"))
        try {
            val json = runtime.call("resolve", """{"tmdbId":1,"type":"movie","season":0,"episode":0}""", timeoutMs)
            JSONObject(json).getString("url").removePrefix("https://cdn.example/")
        } finally {
            runtime.close()
        }
    }

    private fun stream(expr: String) = """return [{ name: "x", title: "t", url: "https://cdn.example/" + ($expr) }];"""

    @Test fun `a controller's signal aborts once, with its listeners, onabort and throwIfAborted`() {
        val body = """
            var c = new AbortController();
            var s = c.signal;
            var seen = [];
            s.onabort = function (e) { seen.push("on:" + e.type); };
            s.addEventListener("abort", function () { seen.push("listener"); });
            var removed = function () { seen.push("removed"); };
            s.addEventListener("abort", removed);
            s.removeEventListener("abort", removed);
            var before = s.aborted + "," + s.reason;
            c.abort();
            c.abort();
            var thrown = "none";
            try { s.throwIfAborted(); } catch (e) { thrown = e.name; }
            var custom = new AbortController();
            custom.abort("mine");
            var ready = AbortSignal.abort();
            ${stream(""""[" + [before, s.aborted, s.reason.name, s.reason.code, thrown, seen.join("+"), custom.signal.reason, ready.aborted, ready.reason.name, s instanceof AbortSignal].join("|") + "]"""")}
        """.trimIndent()
        assertEquals("[false,undefined|true|AbortError|20|AbortError|on:abort+listener|mine|true|AbortError|true]", run(body, SlowHost(0)))
    }

    @Test fun `fetch with an already aborted signal rejects with AbortError and sends nothing`() {
        val host = SlowHost(0)
        val body = """
            var c = new AbortController();
            c.abort();
            try { await fetch("https://site.example/a", { signal: c.signal }); return []; }
            catch (e) { ${stream("e.name")} }
        """.trimIndent()
        assertEquals("AbortError", run(body, host))
        assertEquals(0, host.requests.get())
    }

    @Test fun `an abort while the request is in flight rejects right away`() {
        val body = """
            var c = new AbortController();
            var t0 = Date.now();
            setTimeout(function () { c.abort(); }, 100);
            try { await fetch("https://site.example/slow", { signal: c.signal }); return []; }
            catch (e) { ${stream("""e.name + "/" + (Date.now() - t0 < 1500 ? "fast" : "slow")""")} }
        """.trimIndent()
        assertEquals("AbortError/fast", run(body, SlowHost(2_500), timeoutMs = 10_000))
    }

    @Test fun `AbortSignal timeout rejects a slow fetch with TimeoutError`() {
        val body = """
            var t0 = Date.now();
            try { await fetch("https://site.example/slow", { signal: AbortSignal.timeout(150) }); return []; }
            catch (e) { ${stream("""e.name + "/" + (Date.now() - t0 < 1500 ? "fast" : "slow")""")} }
        """.trimIndent()
        assertEquals("TimeoutError/fast", run(body, SlowHost(2_500), timeoutMs = 10_000))
    }

    @Test fun `a long timeout signal on a fast fetch does not keep the call open`() {
        val body = """
            var s = AbortSignal.timeout(60000);
            var r = await fetch("https://site.example/fast", { signal: s });
            ${stream("""r.status + "/" + s.aborted""")}
        """.trimIndent()
        // A leftover 60 s timer would hold the call past its 5 s limit.
        assertEquals("200/false", run(body, SlowHost(0)))
    }

    @Test fun `a fetch whose signal never aborts answers normally`() {
        val body = """
            var c = new AbortController();
            var r = await fetch("https://site.example/ok", { signal: c.signal });
            var d = await r.json();
            ${stream("""r.status + "/" + JSON.stringify(d) + "/" + c.signal.aborted""")}
        """.trimIndent()
        assertEquals("200/{}/false", run(body, SlowHost(0)))
    }

    @Test fun `axios with an aborted signal rejects with a CanceledError`() {
        val host = SlowHost(0)
        val body = """
            var axios = require("axios");
            var c = new AbortController();
            c.abort();
            try { await axios.get("https://site.example/a", { signal: c.signal }); return []; }
            catch (e) { ${stream("""e.name + "/" + e.code + "/" + axios.isCancel(e)""")} }
        """.trimIndent()
        assertEquals("CanceledError/ERR_CANCELED/true", run(body, host))
        assertEquals(0, host.requests.get())
    }

    @Test fun `axios aborted mid-flight rejects right away`() {
        val body = """
            var axios = require("axios");
            var c = new AbortController();
            setTimeout(function () { c.abort(); }, 100);
            var t0 = Date.now();
            try { await axios.get("https://site.example/slow", { signal: c.signal }); return []; }
            catch (e) { ${stream("""e.code + "/" + (Date.now() - t0 < 1500 ? "fast" : "slow")""")} }
        """.trimIndent()
        assertTrue(run(body, SlowHost(2_500), timeoutMs = 10_000).startsWith("ERR_CANCELED/fast"))
    }
}
