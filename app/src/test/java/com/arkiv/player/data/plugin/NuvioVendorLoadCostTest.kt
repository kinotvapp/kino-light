package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the vendored Nuvio libraries cost a converted plugin, per combination a scraper can need:
 * script size, the time [PluginRuntime.open] takes with the DEFAULT [PluginEnv] (the limits every
 * plugin gets: 64 MB heap, 10 s to load), and the smallest heap that still loads it and runs one call
 * that uses every included library. Prints a table (JUnit's system-out) and fails if a combination
 * stops fitting comfortably inside Kino's limits -- the fix for that is never raising the limits.
 */
class NuvioVendorLoadCostTest {
    private val scraper = NuvioScraperEntry(
        id = "costsrc", name = "CostSrc", filename = "providers/costsrc.js", enabled = true,
        contentLanguage = listOf("en"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun source(cheerio: Boolean, crypto: Boolean, buffer: Boolean): String {
        val requires = buildString {
            // A 2,000-item page (~110 KB of HTML) built at call time, so it doesn't count as script size.
            append("""function page() { var p = []; for (var i = 1; i <= 2000; i++) p.push('<li class="i"><a href="/v/' + i + '">Video ' + i + '</a></li>'); return "<ul>" + p.join("") + "</ul>"; }""" + "\n")
            if (cheerio) append("var cheerio = require(\"cheerio-without-node-native\");\n")
            if (crypto) append("var CryptoJS = require(\"crypto-js\");\n")
        }
        val work = buildList {
            if (cheerio) add("cheerio.load(page())(\"li.i\").last().prev().find(\"a\").attr(\"href\")")
            if (crypto) add("CryptoJS.SHA256(\"x\").toString().length")
            if (buffer) add("Buffer.from(\"aGk=\", \"base64\").toString()")
            add("\"done\"")
        }.joinToString(" + \"|\" + ")
        return requires + """
            function getStreams() {
              return Promise.resolve([{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent($work) }]);
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
    }

    /**
     * QuickJS's own count of the bytes its runtime holds (`JS_ComputeMemoryUsage`'s malloc size), read
     * while the runtime is idle. Reflection because [PluginRuntime] rightly exposes no engine handle.
     * Bisecting `memoryLimitBytes` instead is NOT safe: measured, an out-of-memory while QuickJS
     * compiles a large module (all three libraries at a 4 MB limit) aborts the whole JVM natively.
     */
    private fun heapBytes(rt: PluginRuntime): Long {
        val field = PluginRuntime::class.java.getDeclaredField("js").apply { isAccessible = true }
        return (field.get(rt) as com.dokar.quickjs.QuickJs).memoryUsage.mallocSize
    }

    @Test fun `every library combination loads well inside the default sandbox limits`() {
        val combos = listOf(
            Triple(false, false, false), Triple(true, false, false), Triple(false, true, false),
            Triple(false, false, true), Triple(true, true, true),
        )
        val rows = mutableListOf("libraries | script bytes | open ms (median of 5, default PluginEnv) | QuickJS heap after open | after one call using them")
        for ((cheerio, crypto, buffer) in combos) {
            val script = NuvioPluginConverter.convert(scraper, source(cheerio, crypto, buffer), repoSlug = "o/r").script
            val env = PluginEnv(appVersion = "1.0")
            val times = (1..5).map {
                val t0 = System.nanoTime()
                val rt = runBlocking { PluginRuntime.open("cost", script, ProbePluginHost, env) }
                val ms = (System.nanoTime() - t0) / 1_000_000
                rt.close()
                ms
            }.sorted()
            val rt = runBlocking { PluginRuntime.open("cost", script, ProbePluginHost, env) }
            val (afterOpen, afterCall) = try {
                val opened = heapBytes(rt)
                // Also proves each combination really runs: the answer carries what every library computed.
                val url = runBlocking { JSONObject(rt.call("resolve", JSONObject.quote("""{"tmdbId":1,"type":"movie","season":0,"episode":0}"""), 20_000)).getString("url") }
                val answer = java.net.URLDecoder.decode(url.removePrefix("https://cdn.example/"), "UTF-8")
                assertTrue(answer, answer.endsWith("done"))
                if (cheerio) assertTrue(answer, answer.startsWith("/v/1999"))
                opened to heapBytes(rt)
            } finally {
                rt.close()
            }
            val name = listOfNotNull("cheerio".takeIf { cheerio }, "crypto-js".takeIf { crypto }, "buffer".takeIf { buffer }).ifEmpty { listOf("none") }.joinToString("+")
            rows += "$name | ${script.toByteArray().size} | ${times[2]} | ${"%.2f".format(java.util.Locale.ROOT, afterOpen / 1048576.0)} MB | ${"%.2f".format(java.util.Locale.ROOT, afterCall / 1048576.0)} MB"
            // Kino's limits are 10 s to load and 64 MB: stay far from both on a JVM, since a phone is slower.
            assertTrue("$name took ${times[2]} ms to open", times[2] < env.loadTimeoutMs / 5)
            assertTrue("$name holds ${afterCall / 1048576} MB", afterCall < env.memoryLimitBytes / 4)
        }
        println(rows.joinToString("\n"))
        assertEquals(combos.size + 1, rows.size)
    }
}
