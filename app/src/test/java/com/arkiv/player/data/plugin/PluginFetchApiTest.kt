package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `kino.fetch` v2 and `kino.cookies` inside the real QuickJS runtime. */
class PluginFetchApiTest {
    private class Host : PluginHost {
        val fetches = mutableListOf<String>()
        val cookieCalls = mutableListOf<Pair<Int, Int>>()
        var cleared = 0
        var onFetch: suspend (String) -> String = { req ->
            fetches += req
            JSONObject().put("ok", true).put("status", 200).put("url", JSONObject(req).getString("url"))
                .put("headers", JSONObject().put("content-type", "text/plain")).put("text", "añ").toString()
        }
        override suspend fun fetch(requestJson: String) = onFetch(requestJson)
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
        override fun cookieGet(url: String, name: String): String? { cookieCalls += url.length to name.length; return if (name == "sid") "abc" else null }
        override fun cookiesClear() { cleared++ }
    }

    private val opened = mutableListOf<PluginRuntime>()
    private suspend fun open(script: String, host: PluginHost = Host()) =
        PluginRuntime.open("api", script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    private fun home(body: String, host: PluginHost = Host()): String = runBlocking {
        open("export async function home() { $body }", host).call("home", "null", 10_000)
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `fetch and the cookie functions are frozen and can't be renamed`() {
        val out = home(
            """
            const fns = [kino.fetch, kino.cookies.get, kino.cookies.clear];
            const renamed = fns.map((f) => { try { Object.defineProperty(f, 'name', { value: 'x' }); return 'renamed'; } catch (e) { return 'refused'; } });
            return [renamed.every((r) => r === 'refused'), Object.isFrozen(kino.cookies), fns.every((f) => Object.isFrozen(f))];
            """,
        )
        assertEquals("[true,true,true]", out)
    }

    @Test fun `kino cookies get and clear go to the host, capped`() {
        val host = Host()
        val out = home("return [kino.cookies.get('https://x.example/', 'sid'), kino.cookies.get('u'.repeat(100000), 'n'.repeat(100000)), kino.cookies.clear()]", host)
        assertEquals("[\"abc\",null,null]", out)
        assertEquals(1, host.cleared)
        assertEquals(PluginRuntime.MAX_URL_CHARS to PluginRuntime.MAX_COOKIE_NAME_CHARS, host.cookieCalls[1])
    }

    @Test fun `kino fetch sends each body kind, redirect and cookies options`() {
        val host = Host()
        home(
            """
            await kino.fetch('https://x.example/a', { method: 'post', body: { json: { q: 1 } } });
            await kino.fetch('https://x.example/b', { method: 'POST', body: { form: { user: 'ana', n: 5 } }, redirect: 'manual', cookies: false });
            await kino.fetch('https://x.example/c', { method: 'PUT', body: { base64: 'AQID' } });
            await kino.fetch('https://x.example/d', { method: 'PATCH', body: 'texto', timeoutMs: 1000 });
            return [];
            """,
            host,
        )
        val (a, b, c, d) = host.fetches.map(::JSONObject)
        assertEquals("POST", a.getString("method"))
        assertEquals("""{"kind":"json","value":"{\"q\":1}"}""", a.getJSONObject("body").toString())
        assertEquals("form", b.getJSONObject("body").getString("kind"))
        assertEquals("""[["user","ana"],["n","5"]]""", b.getJSONObject("body").getJSONArray("value").toString())
        assertEquals("manual", b.getString("redirect"))
        assertEquals(false, b.getBoolean("cookies"))
        assertEquals(true, a.getBoolean("cookies"))
        assertEquals("""{"kind":"base64","value":"AQID"}""", c.getJSONObject("body").toString())
        assertEquals("""{"kind":"text","value":"texto"}""", d.getJSONObject("body").toString())
        assertEquals(1000, d.getInt("timeoutMs"))
    }

    @Test fun `kino fetch refuses a bad method, redirect or body before anything crosses`() {
        val host = Host()
        val out = home(
            """
            const codes = [];
            for (const o of [{ method: 'TRACE' }, { redirect: 'error' }, { body: { weird: 1 } }, { body: { form: 5 } }]) {
              try { await kino.fetch('https://x.example/', o) } catch (e) { codes.push(e.code) }
            }
            return codes;
            """,
            host,
        )
        assertEquals("""["invalid_request","invalid_request","invalid_request","invalid_request"]""", out)
        assertEquals(emptyList<String>(), host.fetches)
    }

    @Test fun `a request over 1 MB never crosses, whatever the plugin did to the built-ins`() {
        val host = Host()
        val out = home(
            """
            Array.prototype[Symbol.iterator] = function* () {};
            Object.keys = () => [];
            try { await kino.fetch('https://x.example/', { method: 'POST', body: 'x'.repeat(2 * 1024 * 1024) }) } catch (e) { return [e.code] }
            return ['crossed'];
            """,
            host,
        )
        assertEquals("""["too_large"]""", out)
        assertEquals(emptyList<String>(), host.fetches)
    }

    @Test fun `the host's error envelope becomes a coded error`() {
        val host = Host().apply {
            onFetch = { JSONObject().put("error", JSONObject().put("code", "host_not_allowed").put("message", "host no permitido: evil.example")).toString() }
        }
        assertEquals("""["host_not_allowed","host no permitido: evil.example"]""", home("try { await kino.fetch('https://evil.example/') } catch (e) { return [e.code, e.message] }", host))
    }

    @Test fun `text, json and base64 are derived from whichever form crossed`() {
        val host = Host().apply {
            onFetch = { req ->
                val url = JSONObject(req).getString("url")
                val r = JSONObject().put("ok", true).put("status", 200).put("url", url).put("headers", JSONObject())
                if (url.endsWith("bin")) r.put("base64", "eyJhIjoxfQ==") else r.put("text", "{\"a\":\"ñ\"}")
                r.toString()
            }
        }
        val out = home(
            """
            const t = await kino.fetch('https://x.example/text'), b = await kino.fetch('https://x.example/bin');
            return [t.json().a, t.base64(), b.text(), b.json().a, b.base64(), Object.isFrozen(t)];
            """,
            host,
        )
        assertEquals("""["ñ","eyJhIjoiw7EifQ==","{\"a\":1}",1,"eyJhIjoxfQ==",true]""", out)
    }

    @Test fun `a 5 MB body converts to base64 and back inside the call time`() {
        // QuickJS copies a string on every `+=`: built one piece at a time these took minutes.
        val host = Host()
        val text = "x".repeat(5 * 1024 * 1024)
        host.onFetch = { req ->
            val bin = JSONObject(req).getString("url").endsWith("/bin")
            JSONObject().put("ok", true).put("status", 200).put("url", JSONObject(req).getString("url")).put("headers", JSONObject())
                .apply { if (bin) put("base64", java.util.Base64.getEncoder().encodeToString(text.toByteArray())) else put("text", text) }
                .toString()
        }
        val started = System.nanoTime()
        val out = home(
            """
            const t = await kino.fetch('https://x.example/text');
            const b = await kino.fetch('https://x.example/bin');
            return [t.base64().length, b.text().length];
            """,
            host,
        )
        assertEquals("[6990508,5242880]", out)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 8_000_000_000L)
    }

    @Test fun `end to end - fetch v2 through PluginHttp with persisted cookies against MockWebServer`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":1}").addHeader("Set-Cookie", "sid=s3cr3t; Path=/").addHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))).addHeader("Content-Type", "application/octet-stream"))
            val hosts = EffectiveHosts(listOf("localhost"))
            val cookieFile = tmp.root.resolve("cookies.json")
            val cookies = PluginCookies(cookieFile, hosts)
            val http = PluginHttp(OkHttpClient(), "api", hosts, "9.9.9", cookies = cookies, allowInsecureLocalhost = true)
            val host = DefaultPluginHost("api", http, PluginStorage(tmp.root.resolve("s.json")), cookies = cookies, hosts = hosts, allowInsecureLocalhost = true, logger = {})
            val base = "http://localhost:${server.port}"
            val rt = open(
                """
                export async function home() {
                  const login = await kino.fetch('$base/login', { method: 'POST', body: { form: { user: 'ana' } } });
                  const bin = await kino.fetch('$base/blob');
                  return [login.json().ok, login.headers['set-cookie'] === undefined, kino.cookies.get('$base/', 'sid'), bin.base64()];
                }
                """,
                host,
            )
            http.beginCall()
            assertEquals("""[1,true,"s3cr3t","AQID"]""", rt.call("home", "null", 10_000))
            server.takeRequest()
            assertEquals("sid=s3cr3t", server.takeRequest().getHeader("Cookie"))
            assertTrue(cookieFile.readText().contains("s3cr3t"))
            // A new jar on the same file (the runtime closed and reopened) still has the login.
            assertEquals("s3cr3t", PluginCookies(cookieFile, hosts).get(okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(server.port).build(), "sid"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `fetch limits handed to the prelude come from their Kotlin owners`() {
        val l = JSONObject(PluginRuntime.limits())
        assertEquals(PluginHttp.METHODS, l.getJSONArray("fetchMethods").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(PluginRuntime.MAX_URL_CHARS, l.getInt("maxUrlChars"))
        assertEquals(PluginRuntime.MAX_COOKIE_NAME_CHARS, l.getInt("maxCookieNameChars"))
        assertEquals(PluginRuntime.MAX_REQUEST_CHARS, l.getInt("maxRequestChars"))
    }
}
