package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two real-device gaps found in a phone test of the Nuvio importer (spec §5.2 follow-up): the
 * shim's `fetch` didn't give scraper code the browser Fetch API's PROMISE-returning
 * `response.json()`/`.text()` (MoviesDrive silently fell back to a stale domain instead of crashing:
 * `response.json().then(...)` threw "not a function"), and its flat cheerio subset had no `.find()`
 * or `$(el)` at all (AllWish: "cheerio .find() on a result set has no translation"). Both run the
 * generated `plugin.js` through the REAL QuickJS sandbox, same pattern as [NuvioPluginConverterTest].
 */
class NuvioShimCompatTest {
    private val scraper = NuvioScraperEntry(
        id = "fakesrc", name = "FakeSrc", filename = "providers/fakesrc.js", enabled = true,
        contentLanguage = listOf("en"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    /** Answers one canned response for every URL: body under `text` (kino.fetch's own dual-field response), plus headers. */
    private class FakeFetchHost(private val body: String, private val headers: Map<String, String> = emptyMap()) : PluginHost {
        override suspend fun fetch(requestJson: String): String {
            val url = JSONObject(requestJson).getString("url")
            val h = JSONObject()
            headers.forEach { (k, v) -> h.put(k, v) }
            return JSONObject().put("ok", true).put("status", 200).put("url", url).put("headers", h).put("text", body).toString()
        }
        override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    private fun resolvedUrl(source: String, host: PluginHost): String = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "k")
        val runtime = PluginRuntime.open("probe", result.script, host, PluginEnv(appVersion = "1.0"))
        try {
            JSONObject(runtime.call("resolve", """{"tmdbId":1,"type":"movie","season":0,"episode":0}""", 5_000)).getString("url")
        } finally {
            runtime.close()
        }
    }

    @Test fun `response json() itself returns a Promise, so then-chaining straight on it works`() {
        val source = """
            function getStreams() {
              return fetch("https://fetchcheck.example/json").then(function (r) {
                return r.json().then(function (d) { return [{ name: "x", title: "t", url: "https://cdn.example/" + d.value }]; });
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost("""{"value":"chained-ok"}"""))
        assertEquals("https://cdn.example/chained-ok", url)
    }

    @Test fun `await response json() also works, not just then-chaining`() {
        val source = """
            async function getStreams() {
              var r = await fetch("https://fetchcheck.example/json2");
              var d = await r.json();
              return [{ name: "x", title: "t", url: "https://cdn.example/" + d.value }];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost("""{"value":"await-ok"}"""))
        assertEquals("https://cdn.example/await-ok", url)
    }

    @Test fun `response text() itself returns a Promise too`() {
        val source = """
            function getStreams() {
              return fetch("https://fetchcheck.example/text").then(function (r) {
                return r.text().then(function (t) { return [{ name: "x", title: "t", url: "https://cdn.example/" + t }]; });
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost("plain-text-ok"))
        assertEquals("https://cdn.example/plain-text-ok", url)
    }

    @Test fun `a JSON parse error becomes a rejected promise the scraper can catch, not a synchronous throw`() {
        val source = """
            function getStreams() {
              return fetch("https://fetchcheck.example/badjson").then(function (r) {
                return r.json().catch(function (e) {
                  return [{ name: "x", title: "t", url: "https://cdn.example/caught/" + (e && e.message ? "has-message" : "no-message") }];
                });
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost("{not valid json"))
        assertEquals("https://cdn.example/caught/has-message", url)
    }

    @Test fun `headers get looks a header up case-insensitively, and answers null when absent`() {
        val source = """
            function getStreams() {
              return fetch("https://fetchcheck.example/headers").then(function (r) {
                var ct = r.headers.get("Content-Type");
                var missing = r.headers.get("X-Not-There");
                return [{ name: "x", title: "t", url: "https://cdn.example/" + ct + "/" + (missing === null ? "null" : missing) }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost("{}", headers = mapOf("content-type" to "application/json")))
        assertEquals("https://cdn.example/application/json/null", url)
    }

    // ---- Fix 2: cheerio's $(el) inside .each, .find() over a result set, .text()/.first()/.eq() ----

    private val listHtml = """<div class="item"><a href="/movie/1">One</a></div><div class="item"><a href="/movie/2">Two</a></div>"""

    @Test fun `dollar of el inside each, find, attr, text on a set, and first-eq all work over a real result set`() {
        val source = """
            var cheerio = require("cheerio");
            function getStreams() {
              return fetch("https://cheeriocheck.example/list").then(function (r) { return r.text(); }).then(function (html) {
                var ${'$'} = cheerio.load(html);
                var hrefs = [];
                ${'$'}(".item").each(function (i, el) { hrefs.push(${'$'}(el).find("a").attr("href")); });
                var text = ${'$'}(".item").text();
                var firstClass = ${'$'}(".item").first().attr("class");
                var secondHref = ${'$'}(".item").eq(1).find("a").attr("href");
                var findLen = ${'$'}(".item").find("a").length;
                return [{ name: "x", title: "t", url: [hrefs.join(","), text, firstClass, secondHref, findLen].join("|") }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost(listHtml))
        assertEquals("/movie/1,/movie/2|OneTwo|item|/movie/2|2", url)
    }

    @Test fun `find over a set where only one element matches still unions what does match, length reflects the union`() {
        val html = """<div class="item">no link here</div><div class="item"><a href="/movie/9">Nine</a></div>"""
        val source = """
            var cheerio = require("cheerio");
            function getStreams() {
              return fetch("https://cheeriocheck.example/sparse").then(function (r) { return r.text(); }).then(function (html) {
                var ${'$'} = cheerio.load(html);
                var found = ${'$'}(".item").find("a");
                return [{ name: "x", title: "t", url: "https://cdn.example/" + found.length + "/" + found.attr("href") }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val url = resolvedUrl(source, FakeFetchHost(html))
        assertEquals("https://cdn.example/1//movie/9", url)
    }

    @Test fun `a still-unsupported cheerio traversal method keeps throwing a clear error`() {
        val source = """
            var cheerio = require("cheerio");
            function getStreams() {
              var ${'$'} = cheerio.load("<div class=\"item\"><a href=\"/x\">x</a></div>");
              ${'$'}(".item").parent();
              return [];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val e = assertThrows(PluginScriptException::class.java) { resolvedUrl(source, FakeFetchHost("")) }
        assertTrue("expected the clear no-translation message, got: ${e.message}", e.message.orEmpty().contains("has no translation"))
    }
}
