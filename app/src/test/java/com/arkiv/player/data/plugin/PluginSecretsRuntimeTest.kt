package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `kino.secret` and a sealed `kino.fetch` inside the real QuickJS runtime, over the real [DefaultPluginHost]. */
class PluginSecretsRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val opened = mutableListOf<PluginRuntime>()

    @Before fun start() = server.start()
    @After fun stop() {
        opened.forEach { it.close() }
        server.shutdown()
    }

    private val secrets = PluginSecrets(
        mapOf("apiKey" to TestSealing.seal("k-123", "owner/repo", "apiKey")), "owner/repo", TestSealing.agreement,
        sealedHosts = listOf("localhost"), recipient = TestSealing.TEST_PUBLIC,
    )

    private fun home(body: String, secrets: PluginSecrets? = this.secrets): String = runBlocking {
        val hosts = EffectiveHosts(listOf("localhost"))
        val http = PluginHttp(OkHttpClient(), "test", hosts, "1.0", allowInsecureLocalhost = true)
        val host = DefaultPluginHost("test", http, PluginStorage(tmp.root.resolve("s-${System.nanoTime()}.json")), allowInsecureLocalhost = true, secrets = secrets, logger = {})
        val rt = PluginRuntime.open("test", "export async function home() { $body }", host, PluginEnv(appVersion = "1.0")).also { opened += it }
        http.beginCall()
        rt.call("home", "null", 10_000)
    }

    @Test fun `kino secret returns this runtime's marker`() {
        val out = JSONArray(home("return [kino.secret('apiKey'), kino.secret('apiKey'), Object.isFrozen(kino.secret)]"))
        assertEquals(secrets.marker("apiKey"), out.getString(0))
        assertEquals(out.getString(0), out.getString(1))
        assertTrue(out.getBoolean(2))
    }

    @Test fun `an undeclared name throws`() {
        val out = home("try { kino.secret('x'); return 'no throw' } catch (e) { return e.message }")
        assertEquals("\"este plugin no declara el secreto x\"", out)
        // A plugin with no secrets at all gets the same answer; a long name is cut to 40.
        val long = "n".repeat(60)
        assertEquals(
            "\"este plugin no declara el secreto ${"n".repeat(40)}\"",
            home("try { kino.secret('$long'); return 'no throw' } catch (e) { return e.message }", secrets = null),
        )
    }

    @Test fun `a sealed fetch carries the value to the host while the plugin never sees it`() {
        // The server echoes the value back in the body: what reaches the plugin has the marker instead.
        server.enqueue(MockResponse().setBody("your key is k-123"))
        val base = "http://localhost:${server.port}"
        val out = home(
            """
            const k = kino.secret('apiKey');
            const r = await kino.fetch('$base/search?api_key=' + encodeURIComponent(k), {
              method: 'POST', headers: { 'X-Api-Key': k }, body: { json: { key: k } },
            });
            return [k, r.url, r.text()];
            """,
        )
        val seen = server.takeRequest()
        assertEquals("/search?api_key=k-123", seen.path)
        assertEquals("k-123", seen.getHeader("X-Api-Key"))
        assertEquals("{\"key\":\"k-123\"}", seen.body.readUtf8())
        assertFalse(out, "k-123" in out)
        val m = secrets.marker("apiKey")!!
        assertEquals(JSONArray().put(m).put("$base/search?api_key=$m").put("your key is $m").toString(), out)
    }

    @Test fun `a sealed fetch to an undeclared host fails in the plugin with the sealed-host message`() {
        val other = PluginSecrets(
            mapOf("apiKey" to TestSealing.seal("k-123", "owner/repo", "apiKey")), "owner/repo", TestSealing.agreement,
            sealedHosts = listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val out = home(
            "try { await kino.fetch('http://localhost:${server.port}/x?k=' + kino.secret('apiKey')); return 'sent' } catch (e) { return [e.code, e.message] }",
            secrets = other,
        )
        assertEquals("[\"host_not_allowed\",\"este plugin no puede enviar datos sellados a localhost\"]", out)
        assertEquals(0, server.requestCount)
    }
}
