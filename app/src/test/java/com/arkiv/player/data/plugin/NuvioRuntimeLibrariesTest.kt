package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The libraries Nuvio's own runtime gives a scraper (its README, "Available Modules":
 * `cheerio-without-node-native`, `crypto-js`, `axios`, plus the `Buffer` real scrapers use) are the
 * REAL ones, vendored under `resources/plugin/nuvio-vendor/`, not imitations: every test here runs a
 * converted scraper through the real QuickJS sandbox and checks what the real library answers.
 * Each scraper reports what it computed by encoding it into the stream URL `resolve` returns.
 */
class NuvioRuntimeLibrariesTest {
    private val scraper = NuvioScraperEntry(
        id = "libsrc", name = "LibSrc", filename = "providers/libsrc.js", enabled = true,
        contentLanguage = listOf("en"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun convert(source: String) = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "k")

    private fun resolvedUrl(source: String, host: PluginHost = NuvioRoutingHost(emptyMap())): String = runBlocking {
        val runtime = PluginRuntime.open("libs", convert(source).script, host, PluginEnv(appVersion = "1.0"))
        try {
            JSONObject(runtime.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""), 10_000)).getString("url")
        } finally {
            runtime.close()
        }
    }

    /** A scraper whose `getStreams` returns one stream whose URL is `https://cdn.example/` + the JSON of [expr]. */
    private fun scraperReturning(prelude: String, expr: String) = """
        $prelude
        function getStreams(tmdbId, mediaType, season, episode) {
          return Promise.resolve().then(function () {
            return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify($expr)) }];
          });
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private fun answer(url: String): String = java.net.URLDecoder.decode(url.removePrefix("https://cdn.example/"), "UTF-8")

    // ---- cheerio: the real tree, so traversal works ----

    private val page = """
        <div class="post">
          <h4 class="title">Links</h4>
          <p>intro</p>
          <h5><a href="/dl/720">720p</a></h5>
          <h5><a href="/dl/1080">1080p</a></h5>
          <ul id="list"><li class="a">one</li><li class="b">two</li><li class="c">three</li></ul>
        </div>
    """.trimIndent()

    @Test fun `real cheerio walks the tree - nextAll, parent, next, closest, children and map-get`() {
        val source = scraperReturning(
            """var cheerio = require("cheerio-without-node-native"); var ${'$'} = cheerio.load(${JSONObject.quote(page)});""",
            """[
              ${'$'}("h4.title").nextAll("h5").map(function (i, el) { return ${'$'}(el).find("a").attr("href"); }).get(),
              ${'$'}("li.b").parent().attr("id"),
              ${'$'}("li.a").next().text(),
              ${'$'}("a[href='/dl/720']").closest("div").attr("class"),
              ${'$'}("#list").children().length,
              ${'$'}("#list").children().map(function (i, el) { return ${'$'}(el).text(); }).get().join(",")
            ]""",
        )
        assertEquals("""[["/dl/720","/dl/1080"],"list","two","post",3,"one,two,three"]""", answer(resolvedUrl(source)))
    }

    @Test fun `the cheerio and react-native-cheerio aliases load the same real library`() {
        val source = scraperReturning(
            """var a = require("cheerio"); var b = require("react-native-cheerio"); var c = require("cheerio-without-node-native");""",
            """[a === c, b === c, typeof c.load, a.load("<p><b>x</b></p>")("b").parent().is("p")]""",
        )
        assertEquals("""[true,true,"function",true]""", answer(resolvedUrl(source)))
    }

    // ---- crypto-js: the real library ----

    @Test fun `crypto-js enc Utf8 parse, AES with key and iv, AES with a passphrase, MD5 and SHA256`() {
        val source = scraperReturning(
            """var CryptoJS = require("crypto-js");""",
            """[
              CryptoJS.enc.Utf8.parse("abc").sigBytes,
              CryptoJS.AES.decrypt("5MVkDavvRfRprtsb3CBV8Q==", CryptoJS.enc.Utf8.parse("0123456789abcdef0123456789abcdef"),
                { iv: CryptoJS.enc.Utf8.parse("abcdef9876543210") }).toString(CryptoJS.enc.Utf8),
              CryptoJS.AES.decrypt("U2FsdGVkX18j1T8jxMvNyolInkE/R8omFip1eDdCGyVPsBzNjfc26H9bNPUjz8e/", "s3cret").toString(CryptoJS.enc.Utf8),
              CryptoJS.MD5("nuvio").toString(),
              CryptoJS.SHA256("nuvio").toString(),
              CryptoJS.AES.decrypt(CryptoJS.AES.encrypt("round trip", "pw").toString(), "pw").toString(CryptoJS.enc.Utf8)
            ]""",
        )
        assertEquals(
            """[3,"key and iv ok","nuvio passphrase ok","fd7322f5574d48acc8ca537d3a58a32f",""" +
                """"83f41b253eebe749bd32512d0985d2562207126cf1c10b1f8dabac552ed7f061","round trip"]""",
            answer(resolvedUrl(source)),
        )
    }

    // ---- Buffer: feross/buffer as a global, like Node's ----

    @Test fun `Buffer from base64 and hex, and back`() {
        val source = scraperReturning(
            "",
            """[Buffer.from("aG9sYSBudXZpbw==", "base64").toString(), Buffer.from("hola").toString("hex"),
               Buffer.from("686f6c61", "hex").toString("utf8"), Buffer.from("hola").toString("base64")]""",
        )
        assertEquals("""["hola nuvio","686f6c61","hola","aG9sYQ=="]""", answer(resolvedUrl(source)))
    }

    // ---- axios, on top of the shim's fetch ----

    @Test fun `axios get auto-parses JSON, sends params as the query string and lowercases headers`() {
        val host = NuvioRoutingHost(
            mapOf("api.example/items" to NuvioRoutingHost.Reply(body = """{"value":"json-ok"}""", headers = mapOf("X-Thing" to "yes"))),
        )
        val source = """
            var axios = require("axios");
            function getStreams() {
              return axios.get("https://api.example/items", { params: { q: "a b", page: 2 }, headers: { Accept: "application/json" } }).then(function (res) {
                var out = [res.data.value, res.status, res.headers["x-thing"], typeof res.config];
                return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify(out)) }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        assertEquals("""["json-ok",200,"yes","object"]""", answer(resolvedUrl(source, host)))
        assertEquals("https://api.example/items?q=a+b&page=2", host.requests.single().url)
        assertEquals("GET", host.requests.single().method)
        assertEquals("application/json", host.requests.single().headers["Accept"])
    }

    @Test fun `axios create merges baseURL and default headers, and post sends data as JSON`() {
        val host = NuvioRoutingHost(mapOf("api.example/v1/search" to NuvioRoutingHost.Reply(body = "plain text, not json")))
        val source = """
            var axios = require("axios");
            var api = axios.create({ baseURL: "https://api.example/v1/", headers: { "X-Api": "k1" }, timeout: 8000 });
            function getStreams() {
              return api.post("/search", { title: "Matrix" }, { headers: { "X-Extra": "e" } }).then(function (res) {
                return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify([res.data, res.status])) }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        assertEquals("""["plain text, not json",200]""", answer(resolvedUrl(source, host)))
        val req = host.requests.single()
        assertEquals("https://api.example/v1/search", req.url)
        assertEquals("POST", req.method)
        assertEquals("k1", req.headers["X-Api"])
        assertEquals("e", req.headers["X-Extra"])
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("""{"title":"Matrix"}""", req.body?.getString("value"))
    }

    @Test fun `axios rejects a non-2xx answer with an error carrying response status, unless validateStatus says otherwise`() {
        val host = NuvioRoutingHost(mapOf("api.example/missing" to NuvioRoutingHost.Reply(status = 404, body = """{"error":"nope"}""")))
        val source = """
            var axios = require("axios");
            function getStreams() {
              return axios.get("https://api.example/missing").then(function () { return "resolved"; }, function (e) {
                return [e.isAxiosError === true, e.response.status, e.response.data.error, /404/.test(e.message)];
              }).then(function (first) {
                return axios.get("https://api.example/missing", { validateStatus: function (s) { return s < 500; } }).then(function (res) {
                  return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify([first, res.status])) }];
                });
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        assertEquals("""[[true,404,"nope",true],404]""", answer(resolvedUrl(source, host)))
    }

    /** dvdplay and mallumv (yoruix/nuvio-providers) start with `global.URL_VALIDATION_ENABLED = true;`: React Native's `global`. */
    @Test fun `React Native's global is the global object, so a scraper can write to it at its top level`() {
        val source = scraperReturning(
            "global.URL_VALIDATION_ENABLED = true;",
            "[global === globalThis, URL_VALIDATION_ENABLED]",
        )
        assertEquals("[true,true]", answer(resolvedUrl(source)))
    }

    @Test fun `an unknown module still throws the clear unsupported-require error`() {
        val source = scraperReturning(
            """var out; try { require("ws"); out = "loaded"; } catch (e) { out = e.message; }""",
            "out",
        )
        assertEquals("\"Nuvio compat: unsupported require('ws')\"", answer(resolvedUrl(source)))
    }

    // ---- only what a scraper can need ships in its script ----

    @Test fun `a scraper using none of the libraries gets no vendored code in its script`() {
        val plain = convert(scraperReturning("", "1")).script
        assertFalse(plain.contains("__nuvioLibCheerio="))
        assertFalse(plain.contains("__nuvioLibCryptoJs="))
        assertFalse(plain.contains("__nuvioLibBuffer="))
        val withCheerio = convert(scraperReturning("""var c = require("cheerio-without-node-native");""", "1")).script
        assertTrue(withCheerio.contains("__nuvioLibCheerio="))
        assertFalse(withCheerio.contains("__nuvioLibCryptoJs="))
        assertFalse(withCheerio.contains("__nuvioLibBuffer="))
        val withCryptoAndBuffer = convert(scraperReturning("""var C = require("crypto-js"); var b = Buffer.from("x");""", "1")).script
        assertFalse(withCryptoAndBuffer.contains("__nuvioLibCheerio="))
        assertTrue(withCryptoAndBuffer.contains("__nuvioLibCryptoJs="))
        assertTrue(withCryptoAndBuffer.contains("__nuvioLibBuffer="))
        // "ArrayBuffer"/"messageBuffer" are not the Buffer global.
        assertFalse(convert(scraperReturning("var a = new ArrayBuffer(1); var messageBuffer = '';", "1")).script.contains("__nuvioLibBuffer="))
    }
}
