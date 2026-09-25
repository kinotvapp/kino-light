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

    @Test fun `headers and form fields still cross correctly when the plugin breaks the array iterator`() {
        // The same hostile pattern as the 1 MB test above, but proving the OTHER half of the
        // Global Constraint: not just "the cap still holds" but "nothing gets silently dropped"
        // when every loop over plugin-reachable data must be index-based, never for...of.
        val host = Host()
        home(
            """
            Array.prototype[Symbol.iterator] = function* () {};
            Object.keys = () => [];
            await kino.fetch('https://x.example/h', { headers: { 'x-a': '1', 'x-b': '2' }, body: { form: { user: 'ana', n: 5 } } });
            return [];
            """,
            host,
        )
        assertEquals(1, host.fetches.size)
        val sent = JSONObject(host.fetches[0])
        assertEquals("1", sent.getJSONObject("headers").getString("x-a"))
        assertEquals("2", sent.getJSONObject("headers").getString("x-b"))
        assertEquals("""[["user","ana"],["n","5"]]""", sent.getJSONObject("body").getJSONArray("value").toString())
    }

    @Test fun `hostile input is caught, not crashed, by kino fetch and kino cookies get`() {
        val host = Host()
        val out = home(
            """
            const results = [];
            try { await kino.fetch('https://x.example/', { get method() { throw 1; } }); results.push('method:no-throw'); } catch (e) { results.push('method:caught'); }
            const jsonBody = {}; Object.defineProperty(jsonBody, 'json', { get() { throw new Error('evil'); } });
            try { await kino.fetch('https://x.example/', { body: jsonBody }); results.push('json:no-throw'); } catch (e) { results.push('json:caught'); }
            const formBody = {}; Object.defineProperty(formBody, 'form', { get() { throw new Error('evil'); } });
            try { await kino.fetch('https://x.example/', { body: formBody }); results.push('form:no-throw'); } catch (e) { results.push('form:caught'); }
            const evilHeaderValue = { toString() { throw new Error('evil'); } };
            try { await kino.fetch('https://x.example/', { headers: { x: evilHeaderValue } }); results.push('header:no-throw'); } catch (e) { results.push('header:caught'); }
            const evilUrl = { toString() { throw new Error('evil'); } };
            try { kino.cookies.get(evilUrl, 'n'); results.push('cookieUrl:no-throw'); } catch (e) { results.push('cookieUrl:caught'); }
            const evilName = { toString() { throw new Error('evil'); } };
            try { kino.cookies.get('https://x.example/', evilName); results.push('cookieName:no-throw'); } catch (e) { results.push('cookieName:caught'); }
            // A wrong-type argument (neither throws nor crashes: it is coerced to a string).
            results.push(kino.cookies.get(123, {}) === null ? 'wrongType:safe-null' : 'wrongType:unexpected');
            return results;
            """,
            host,
        )
        assertEquals(
            """["method:caught","json:caught","form:caught","header:caught","cookieUrl:caught","cookieName:caught","wrongType:safe-null"]""",
            out,
        )
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

    /** Connects wherever OkHttp asked, but on 127.0.0.1: the port picks the MockWebServer. Same
     *  trick as PluginUserHostStreamTest -- a typed server can never be loopback/"localhost" (even
     *  hand-built, PluginHostGate double-checks it), so it has to be a LAN-shaped address instead. */
    private object LanToLoopback : javax.net.SocketFactory() {
        private class Redirecting : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                val port = (endpoint as java.net.InetSocketAddress).port
                super.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), timeout)
            }
        }
        override fun createSocket() = Redirecting()
        override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: java.net.InetAddress?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    }

    /**
     * Fix round 1's "also fold in": a typed (person-configured) server reaches a live `kino.fetch`
     * call through the REAL QuickJS runtime end to end -- not just at the registry/PluginContentSource
     * level (PluginBrowseTest's "a stream on the typed server resolves", PluginSetupLifecycleTest's
     * "the registry carries typed servers into the player's access"). Proves the SAME `EffectiveHosts`
     * shape AppGraph builds from `InstalledPlugin.hosts` (declared ∪ typed, no declared hosts here at
     * all) actually lets a plugin's own JS reach the server the person typed into Configurar, and
     * still refuses everything else.
     */
    @Test fun `end to end - a typed server reaches a live kino fetch call, an undeclared one is refused`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":1}").addHeader("Content-Type", "application/json"))
            // No declared hosts at all: only the typed server, exactly as a plugin whose sole `url`
            // setting is filled in (PluginHosts.effective / PluginConfigStore.setupState) reaches AppGraph.
            val hosts = EffectiveHosts(emptyList(), listOf(UserHost("http", "10.0.2.2", server.port)))
            val cookies = PluginCookies(tmp.root.resolve("cookies-typed.json"), hosts)
            val client = OkHttpClient.Builder().socketFactory(LanToLoopback).build()
            val http = PluginHttp(client, "api", hosts, "9.9.9", cookies = cookies)
            val host = DefaultPluginHost("api", http, PluginStorage(tmp.root.resolve("s-typed.json")), cookies = cookies, hosts = hosts, logger = {})
            val base = "http://10.0.2.2:${server.port}"
            val rt = open(
                """
                export async function home() {
                  const r = await kino.fetch('$base/x');
                  let refused = false;
                  try { await kino.fetch('http://undeclared.example/'); } catch (e) { refused = e.code === 'host_not_allowed'; }
                  return [r.json().ok, refused];
                }
                """,
                host,
            )
            http.beginCall()
            assertEquals("[1,true]", rt.call("home", "null", 10_000))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `fetch limits handed to the prelude come from their Kotlin owners`() {
        val l = JSONObject(PluginRuntime.limits())
        assertEquals(PluginHttp.METHODS, l.getJSONArray("fetchMethods").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(PluginHttp.BODY_KINDS, l.getJSONArray("fetchBodyKinds").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(PluginHttp.REDIRECT_MODES, l.getJSONArray("fetchRedirectModes").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(PluginRuntime.MAX_URL_CHARS, l.getInt("maxUrlChars"))
        assertEquals(PluginRuntime.MAX_COOKIE_NAME_CHARS, l.getInt("maxCookieNameChars"))
        assertEquals(PluginRuntime.MAX_REQUEST_CHARS, l.getInt("maxRequestChars"))
    }

    @Test fun `DefaultPluginHost rejects a redirect or body kind outside the real constants, before any request crosses`() = runBlocking {
        // kino.fetch itself can never construct these (the prelude only ever sends "follow"/"manual"
        // and one of BODY_KINDS) -- this proves Kotlin's OWN defense-in-depth against a bypassed or
        // future-buggy prelude, not just the JS-side check (see the redirect comment in
        // DefaultPluginHost.request).
        val hosts = EffectiveHosts(listOf("x.example"))
        val http = PluginHttp(OkHttpClient(), "api", hosts, "9.9.9", allowInsecureLocalhost = true)
        val host = DefaultPluginHost("api", http, PluginStorage(tmp.root.resolve("s2.json")), hosts = hosts, allowInsecureLocalhost = true, logger = {})
        val badRedirect = JSONObject().put("url", "https://x.example/").put("redirect", "teleport").toString()
        assertEquals("invalid_request", JSONObject(host.fetch(badRedirect)).getJSONObject("error").getString("code"))
        val badBody = JSONObject().put("url", "https://x.example/")
            .put("body", JSONObject().put("kind", "xml").put("value", "x")).toString()
        assertEquals("invalid_request", JSONObject(host.fetch(badBody)).getJSONObject("error").getString("code"))
    }
}
