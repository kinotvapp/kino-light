package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        override suspend fun fetch(requestJson: String) = onFetch(requestJson)
        override fun select(html: String, css: String) = PluginHtml.selectJson(html, css)
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
}
