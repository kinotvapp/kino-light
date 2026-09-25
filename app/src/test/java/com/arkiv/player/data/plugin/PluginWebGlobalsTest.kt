package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The web globals Kino adds (web.js) as a plugin sees them, inside the real runtime:
 * - every case of `app/src/test/resources/plugin/web-corpus.json` gives Node's answer (the corpus
 *   is written by Node itself: `node app/src/test/resources/plugin/web-corpus.mjs`);
 * - they are frozen, can't be replaced, and hostile input fails as an ordinary error.
 */
class PluginWebGlobalsTest {
    private val host = object : PluginHost {
        override suspend fun fetch(requestJson: String) = "{}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }
    private val opened = mutableListOf<PluginRuntime>()
    private fun open(script: String) = runBlocking { PluginRuntime.open("web", script, host, PluginEnv(appVersion = "x")) }.also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    private fun home(body: String): String = runBlocking { open("export async function home() { $body }").call("home", "null", 10_000) }

    @Test fun `the prelude reads its two files from the class loader`() {
        assertTrue(PluginPrelude.web.contains("TextDecoder"))
        assertTrue(PluginPrelude.prelude.contains("__kinoCall"))
    }

    @Test fun `every corpus case gives Node's answer inside the runtime`() {
        val corpus = JSONArray(File("src/test/resources/plugin/web-corpus.json").readText())
        val runner = File("src/test/resources/plugin/web-runner.js").readText()
        val bodies = JSONArray((0 until corpus.length()).map { corpus.getJSONObject(it).getString("body") })
        // The runner answers ASCII-only JSON: on the JVM, quickjs-kt mangles a non-BMP character on its
        // way out of QuickJS (HotSpot's NewStringUTF; Android's runtime is fine, measured).
        val rt = open("export async function home(a) { const run = (0, eval)(a.runner); return a.bodies.map((b) => run(b)); }")
        val got = JSONArray(runBlocking { rt.call("home", JSONObject().put("runner", runner).put("bodies", bodies).toString(), 30_000) })
        val fails = (0 until corpus.length()).mapNotNull { i ->
            val want = corpus.getJSONObject(i).getJSONObject("result")
            val have = JSONObject(got.getString(i))
            if (have.similar(want)) null else "#$i ${bodies.getString(i)}\n  node:   $want\n  kino:   $have"
        }
        assertEquals(fails.joinToString("\n"), 0, fails.size)
        assertTrue(corpus.length() >= 100)
    }

    @Test fun `web globals are frozen and can't be replaced`() {
        assertEquals(
            "[true,true,true,true,true,true,true,true,true,true,\"object\",\"function\"]",
            home(
                """
                let replaced = 'no'; try { URL = 1 } catch (e) { replaced = 'refused' }
                return [
                  Object.isFrozen(URL), Object.isFrozen(URL.prototype),
                  Object.isFrozen(URLSearchParams), Object.isFrozen(URLSearchParams.prototype),
                  Object.isFrozen(atob), Object.isFrozen(btoa),
                  Object.isFrozen(TextEncoder), Object.isFrozen(TextEncoder.prototype),
                  Object.isFrozen(TextDecoder), Object.isFrozen(TextDecoder.prototype),
                  typeof new URL('https://a.example/').searchParams, typeof URL,
                ];
                """,
            ),
        )
    }

    @Test fun `hostile inputs to the web globals fail as ordinary errors`() {
        val out = home(
            """
            const r = [];
            for (const f of [
              () => new URL({ toString() { throw new Error('t') } }),
              () => atob({ toString() { return '*' } }),
              () => new TextDecoder().decode(5),
              () => btoa('Ā'),
              () => new URL('http://[::1'),
              () => new URLSearchParams([['a', 'b', 'c']]),
              () => new TextEncoder().encode('x'.repeat(5000000)),
              () => Object.defineProperty(URL.prototype.toString, 'name', { value: 'n'.repeat(20000000) }),
            ]) {
              try { f(); r.push('ok') } catch (e) { r.push(e.name) }
            }
            return r;
            """,
        )
        assertEquals(
            """["Error","InvalidCharacterError","TypeError","InvalidCharacterError","TypeError","TypeError","ok","TypeError"]""",
            out,
        )
    }

    @Test fun `a 1 MB base64 round trip stays inside the call time`() {
        assertEquals("[1048576,true]", home("const s = 'x'.repeat(1048576); const b = btoa(s); return [atob(b).length, atob(b) === s];"))
    }
}
