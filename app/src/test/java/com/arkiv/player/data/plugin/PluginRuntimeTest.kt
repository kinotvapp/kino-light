package com.arkiv.player.data.plugin

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PluginRuntimeTest {
    private class FakeHost : PluginHost {
        val logs = mutableListOf<String>()
        val storage = mutableMapOf<String, String>()
        var onFetch: suspend (String) -> String = { req ->
            JSONObject().put("ok", true).put("status", 200).put("url", JSONObject(req).getString("url"))
                .put("headers", JSONObject()).put("body", "{\"hello\":\"world\"}").toString()
        }
        var onSelect: (String, String) -> String = { html, css -> PluginHtml.selectJson(html, css) }
        override suspend fun fetch(requestJson: String) = onFetch(requestJson)
        override fun select(html: String, css: String) = onSelect(html, css)
        override fun storageGet(key: String) = storage[key]
        override fun storageSet(key: String, value: String) { storage[key] = value }
        override fun storageRemove(key: String) { storage.remove(key) }
        override fun log(level: String, message: String) { logs += "$level:$message" }
    }

    private val env = PluginEnv(appVersion = "9.9.9")
    private val opened = mutableListOf<PluginRuntime>()

    private suspend fun open(script: String, host: PluginHost = FakeHost(), e: PluginEnv = env) =
        PluginRuntime.open("test", script, host, e).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    @Test fun `lists exported functions only`() = runBlocking {
        val rt = open("export async function search(q) { return [] }\nexport const notAFunction = 1\nexport function resolve(r) { return {} }")
        assertEquals(setOf("search", "resolve"), rt.exports)
    }

    @Test fun `call passes JSON in and JSON out`() = runBlocking {
        val rt = open("export async function search(q) { return [{ title: q.q.toUpperCase() }] }")
        assertEquals("[{\"title\":\"METROPOLIS\"}]", rt.call("search", "{\"q\":\"metropolis\"}", 5_000))
    }

    @Test fun `undefined result becomes null`() = runBlocking {
        val rt = open("export async function home() { }")
        assertEquals("null", rt.call("home", "null", 5_000))
    }

    @Test fun `kino globals carry the environment`() = runBlocking {
        val rt = open("export async function home() { return [kino.apiVersion, kino.appVersion, kino.lang] }")
        assertEquals("[1,\"9.9.9\",\"es-CO\"]", rt.call("home", "null", 5_000))
    }

    @Test fun `kino fetch is async and exposes json`() = runBlocking {
        val rt = open("export async function search(q) { const r = await kino.fetch('https://x.example/a', { headers: { A: 'b' } }); return [r.ok, r.status, r.json().hello, r.text().length] }")
        assertEquals("[true,200,\"world\",17]", rt.call("search", "{}", 5_000))
    }

    @Test fun `a refused fetch is catchable with the host message`() = runBlocking {
        val host = FakeHost().apply { onFetch = { throw HostNotAllowedException("evil.example") } }
        val rt = open("export async function search(q) { try { await kino.fetch('https://evil.example/') } catch (e) { return [e.message] } }", host)
        assertEquals("[\"host no permitido: evil.example\"]", rt.call("search", "{}", 5_000))
    }

    @Test fun `an uncaught refused fetch fails the call with that message`() {
        val host = FakeHost().apply { onFetch = { throw HostNotAllowedException("evil.example") } }
        val rt = runBlocking { open("export async function search(q) { await kino.fetch('https://evil.example/'); return [] }", host) }
        val e = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("search", "{}", 5_000) } }
        assertTrue(e.message!!.contains("host no permitido"))
    }

    @Test fun `console and kino log reach the host`() = runBlocking {
        val host = FakeHost()
        val rt = open("export async function home() { console.log('a', {b: 1}); console.warn('w'); kino.log('k'); return [] }", host)
        rt.call("home", "null", 5_000)
        assertEquals(listOf("info:a {\"b\":1}", "warn:w", "info:k"), host.logs)
    }

    @Test fun `storage round-trips`() = runBlocking {
        val host = FakeHost()
        val rt = open("export async function home() { kino.storage.set('k', 'v'); const a = kino.storage.get('k'); kino.storage.remove('k'); return [a, kino.storage.get('k')] }", host)
        assertEquals("[\"v\",null]", rt.call("home", "null", 5_000))
    }

    @Test fun `html select goes through Jsoup`() = runBlocking {
        val rt = open("export async function home() { return kino.html.select('<a href=\"/x\">X</a>', 'a').map(e => e.attrs.href) }")
        assertEquals("[\"/x\"]", rt.call("home", "null", 5_000))
    }

    @Test fun `Promise reject is deferred so a caught rejection does not abort the call`() = runBlocking {
        val rt = open("export async function home() { try { await Promise.reject(new Error('x')) } catch (e) { return ['caught ' + e.message] } }")
        assertEquals("[\"caught x\"]", rt.call("home", "null", 5_000))
    }

    // The rest of the "Known engine limits" of the authoring guide. Each test pins one sentence of
    // it; if a library bump changes an outcome, the failing test names the sentence to update.

    private fun failsWith(script: String, fragment: String) {
        val rt = runBlocking { open(script) }
        val e = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("home", "null", 5_000) } }
        assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
    }

    @Test fun `guide - a throw before the first await aborts the call even with catch, Promise all or allSettled`() {
        val helper = "async function bad() { throw new Error('sync') }\n"
        failsWith(helper + "export async function home() { try { await bad() } catch (e) { return ['caught'] } }", "sync")
        failsWith(helper + "export async function home() { return [await bad().catch(e => 'caught')] }", "sync")
        failsWith(helper + "export async function home() { try { await Promise.all([bad()]) } catch (e) { return ['caught'] } }", "sync")
        failsWith(helper + "export async function home() { return (await Promise.allSettled([bad()])).map(r => r.status) }", "sync")
    }

    @Test fun `guide - a throw after any await is caught, even await null`() = runBlocking {
        val rt = open(
            "async function late() { await null; throw new Error('late') }\n" +
                "export async function home() { try { await late() } catch (e) { return ['caught ' + e.message] } }",
        )
        assertEquals("[\"caught late\"]", rt.call("home", "null", 5_000))
    }

    @Test fun `guide - a promise rejected as soon as it is created aborts the call`() {
        failsWith(
            "export async function home() { const p = new Promise((_, reject) => reject(new Error('x'))); try { await p } catch (e) { return ['caught'] } }",
            "x",
        )
        failsWith(
            "async function later() { await kino.fetch('https://x.example/'); return Promise.reject(new Error('r')) }\n" +
                "export async function home() { try { await later() } catch (e) { return ['caught'] } }",
            "r",
        )
    }

    @Test fun `guide - Promise reject is safe when it is awaited or caught right away`() = runBlocking {
        val rt = open(
            "function refuse() { return Promise.reject(new Error('no')) }\n" +
                "export async function home() {\n" +
                "  const a = await refuse().catch(e => 'a ' + e.message);\n" +
                "  try { await refuse() } catch (e) { return [a, 'b ' + e.message] }\n" +
                "}",
        )
        assertEquals("[\"a no\",\"b no\"]", rt.call("home", "null", 5_000))
    }

    @Test fun `guide - globals a Node author might reach for do not exist`() = runBlocking {
        val names = listOf(
            "fetch", "require", "process", "Buffer", "TextEncoder", "TextDecoder", "btoa", "atob", "structuredClone",
            "queueMicrotask", "Intl", "URL", "URLSearchParams", "AbortController", "performance", "crypto",
            "setTimeout", "setInterval", "setImmediate", "WeakRef",
        )
        val rt = open("export async function home() { return [${names.joinToString(", ") { "typeof $it" }}, typeof console, typeof encodeURIComponent] }")
        val expected = names.map { "undefined" } + listOf("object", "function")
        assertEquals(expected, JSONArray(rt.call("home", "null", 5_000)).let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun `guide - modern syntax and the built-ins the guide lists work`() = runBlocking {
        val rt = open(
            "class Box { n = 1 }\n" +
                "export async function home() {\n" +
                "  const o = { a: { b: 2 } };\n" +
                "  const settled = await Promise.allSettled([Promise.resolve(1)]);\n" +
                "  return [o?.a?.b, o.z ?? 'dflt', /(?<y>\\d{4})/.exec('in 2024').groups.y, /(?<=a)b/.test('ab'),\n" +
                "    /\\p{L}+/u.exec('ñandú')[0], new Box().n, 'a-b'.replaceAll('-', '+'), [1, 2, 3].at(-1), [1, [2]].flat().length,\n" +
                "    JSON.stringify(Object.fromEntries([['k', 1]])), settled[0].status, `t\${1 + 1}`, encodeURIComponent('ñ a'),\n" +
                "    [...new Set([1, 1, 2])].length, typeof 1n] }",
        )
        assertEquals(
            """[2,"dflt","2024",true,"ñandú",1,"a+b",3,2,"{\"k\":1}","fulfilled","t2","%C3%B1%20a",2,"bigint"]""",
            rt.call("home", "null", 5_000),
        )
    }

    @Test fun `guide - localeCompare and toLocaleString ignore their locale and options`() = runBlocking {
        val rt = open(
            "export async function home() { return [" +
                "['a10', 'a2'].sort((x, y) => x.localeCompare(y, undefined, { numeric: true })).join()," +
                "['b', 'a', 'B', 'A'].sort((x, y) => x.localeCompare(y, 'es', { sensitivity: 'base' })).join()," +
                "(1234.5).toLocaleString('es-CO')] }",
        )
        assertEquals("[\"a10,a2\",\"A,B,a,b\",\"1234.5\"]", rt.call("home", "null", 5_000))
    }

    // --- What crosses into Kotlin is capped BEFORE it crosses (the 64 MB limit only bounds the JS heap).

    @Test fun `an oversized answer fails the call with a normal plugin error`() {
        val rt = runBlocking { open("export async function home() { return 'x'.repeat(${PluginRuntime.MAX_RESULT_CHARS}) }") }
        val e = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("home", "null", 10_000) } }
        assertTrue(e.message, e.message!!.contains("demasiado grande"))
        // The runtime is still fine: the refusal happened in JS, nothing large reached Kotlin.
        assertEquals(false, rt.isDiscarded)
    }

    @Test fun `an answer right at the cap still goes through`() = runBlocking {
        // JSON.stringify adds the two quotes.
        val rt = open("export async function home() { return 'x'.repeat(${PluginRuntime.MAX_RESULT_CHARS - 2}) }")
        assertEquals(PluginRuntime.MAX_RESULT_CHARS, rt.call("home", "null", 10_000).length)
    }

    @Test fun `a plugin cannot replace __kinoCall or JSON stringify to smuggle a huge answer`() = runBlocking {
        val rt = open(
            "try { globalThis.__kinoCall = async () => 'hijacked' } catch (e) {}\n" +
                "try { Object.defineProperty(globalThis, '__kinoCall', { value: async () => 'hijacked' }) } catch (e) {}\n" +
                "JSON.stringify = () => 'x'.repeat(${PluginRuntime.MAX_RESULT_CHARS + 10})\n" +
                "export async function home() { try { globalThis.__kinoExports = { home: async () => 'evil' } } catch (e) {} return [1] }",
        )
        assertEquals("[1]", rt.call("home", "null", 5_000))
        assertEquals("[1]", rt.call("home", "null", 5_000))
    }

    @Test fun `the native bridge is not reachable from plugin code`() = runBlocking {
        // quickjs-kt makes the global itself non-configurable: it stays, emptied and frozen.
        val rt = open(
            "export async function home() { const n = globalThis.__kinoNative; try { n.fetch = () => 1 } catch (e) {} " +
                "return [typeof n.fetch, typeof n.log, typeof n.storageSet, Object.getOwnPropertyNames(n).length, Object.isFrozen(n)] }",
        )
        assertEquals("[\"undefined\",\"undefined\",\"undefined\",0,true]", rt.call("home", "null", 5_000))
    }

    @Test fun `log lines are cut before they reach the host`() = runBlocking {
        val host = FakeHost()
        val rt = open("export async function home() { console.log('x'.repeat(100000)); kino.log('y'.repeat(100000)); return [] }", host)
        rt.call("home", "null", 5_000)
        assertEquals(listOf(PluginRuntime.MAX_LOG_CHARS, PluginRuntime.MAX_LOG_CHARS), host.logs.map { it.substringAfter(':').length })
    }

    @Test fun `an oversized storage value is refused in JS and never reaches the host`() = runBlocking {
        val host = FakeHost()
        val rt = open(
            "export async function home() { try { kino.storage.set('k', 'x'.repeat(70000)) } catch (e) { return [e.message] } return ['stored'] }",
            host,
        )
        assertEquals("[\"almacenamiento del plugin lleno (64 KB)\"]", rt.call("home", "null", 5_000))
        assertEquals(emptyMap<String, String>(), host.storage)
    }

    @Test fun `an oversized fetch request is refused in JS and never reaches the host`() = runBlocking {
        val host = FakeHost()
        var fetched = false
        host.onFetch = { fetched = true; "{}" }
        val rt = open(
            "export async function search() { try { await kino.fetch('https://x.example/', { method: 'POST', body: 'x'.repeat(${PluginRuntime.MAX_REQUEST_CHARS}) }) } catch (e) { return [e.message] } return ['sent'] }",
            host,
        )
        assertTrue(rt.call("search", "{}", 5_000).contains("demasiado grande"))
        assertEquals(false, fetched)
    }

    @Test fun `html select reads only the first 2 million characters, cut before crossing`() = runBlocking {
        val host = FakeHost()
        var seen = -1
        host.onSelect = { html, _ -> seen = html.length; "[]" }
        val rt = open("export async function home() { return kino.html.select('x'.repeat(${PluginHtml.MAX_HTML_CHARS + 1000}), 'a') }", host)
        assertEquals("[]", rt.call("home", "null", 10_000))
        assertEquals(PluginHtml.MAX_HTML_CHARS, seen)
    }

    @Test fun `an oversized css selector is refused in JS`() = runBlocking {
        val host = FakeHost()
        var selected = false
        host.onSelect = { _, _ -> selected = true; "[]" }
        val rt = open("export async function home() { try { kino.html.select('<a/>', 'a'.repeat(20000)) } catch (e) { return [e.message] } return ['ran'] }", host)
        assertTrue(rt.call("home", "null", 5_000).contains("selector"))
        assertEquals(false, selected)
    }

    @Test fun `a synchronous busy loop times out, returns control and discards the runtime`() {
        val rt = runBlocking { open("export async function search(q) { let i = 0; while (i < 150000000) i++; return [] }") }
        val t0 = System.currentTimeMillis()
        assertThrows(PluginTimeoutException::class.java) { runBlocking { rt.call("search", "{}", 300) } }
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
        assertTrue(rt.isDiscarded)
        assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("search", "{}", 300) } }
    }

    @Test fun `memory limit fails the call, and leaves the runtime healthy or discarded, never half-broken`() {
        val rt = runBlocking {
            open(
                "export async function home(big) { if (big) { let a = []; while (true) a.push(new Array(100000).fill(1)) } return [] }",
                e = env.copy(memoryLimitBytes = 16L * 1024 * 1024),
            )
        }
        // Only the type is asserted: the engine sometimes can't even allocate the error message.
        assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("home", "true", 10_000) } }
        if (!rt.isDiscarded) assertEquals("[]", runBlocking { rt.call("home", "false", 5_000) })
    }

    @Test fun `a syntax error fails open`() {
        assertThrows(PluginScriptException::class.java) { runBlocking { open("export async function (") } }
    }

    @Test fun `a throw at module top level fails open`() {
        assertThrows(PluginScriptException::class.java) { runBlocking { open("throw new Error('boom')") } }
    }

    @Test fun `end to end - kino fetch through PluginHttp against MockWebServer`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("{\"n\":42}"))
            val http = PluginHttp(OkHttpClient(), "test", listOf("localhost"), "9.9.9", allowInsecureLocalhost = true)
            val storage = PluginStorage(Files.createTempDirectory("ps").resolve("s.json").toFile())
            val host = DefaultPluginHost("test", http, storage, logger = {})
            val rt = open("export async function search(q) { const r = await kino.fetch(q.url); return [r.json().n] }", host)
            http.beginCall()
            assertEquals("[42]", rt.call("search", JSONObject().put("url", "http://localhost:${server.port}/n").toString(), 5_000))
            assertEquals("Kino/9.9.9 (plugin test)", server.takeRequest().getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a load that times out but keeps running is closed once it finishes, not leaked`() {
        // A unique label, not the shared "test" one every other case uses: the assertion below
        // checks this exact thread has gone, and must not see a different test's thread instead.
        val label = "slowload-${System.nanoTime()}"
        val host = FakeHost().apply { onSelect = { _, _ -> Thread.sleep(300); "[]" } }
        // A synchronous host call at module top level, before any export, stalls loading itself
        // (not a call) past loadTimeoutMs while still guaranteed to finish afterwards.
        val script = "kino.html.select('<a></a>', 'a');\nexport async function home() { return [] }"
        assertThrows(PluginTimeoutException::class.java) {
            runBlocking { PluginRuntime.open(label, script, host, env.copy(loadTimeoutMs = 50)) }
        }
        // Prefix match, not exact: kotlinx-coroutines' debug mode suffixes the live thread name
        // with " @coroutine#N", so `it.name == threadName` would never match and vacuously pass.
        val threadPrefix = "plugin-$label"
        fun stillRunning() = Thread.getAllStackTraces().keys.any { it.name.startsWith(threadPrefix) }
        assertTrue("expected the load's thread to still be running right after the timeout", stillRunning())
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && stillRunning()) Thread.sleep(20)
        assertTrue("expected the load's thread to be gone once loading finished, not leaked", !stillRunning())
    }

    @Test fun `cancelling the caller mid-call discards the runtime instead of leaving an orphaned evaluation`() {
        val rt = runBlocking { open("export async function search(q) { let i = 0; while (i < 150000000) i++; return [] }") }
        runBlocking {
            val caller = launch { rt.call("search", "{}", 10_000) }
            delay(100) // let the evaluation actually start running on the runtime's thread
            caller.cancelAndJoin()
        }
        assertTrue(rt.isDiscarded)
        val t0 = System.currentTimeMillis()
        assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("search", "{}", 5_000) } }
        assertTrue(
            "a later call must fail fast, not queue behind the orphaned evaluation: took ${System.currentTimeMillis() - t0} ms",
            System.currentTimeMillis() - t0 < 1_000,
        )
    }
}
