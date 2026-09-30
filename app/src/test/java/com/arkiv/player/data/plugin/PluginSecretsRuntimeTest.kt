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

    /** Every line both loggers wrote: [PluginHttp]'s and [DefaultPluginHost]'s (`kino.log`, `console`). */
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun home(body: String, secrets: PluginSecrets? = this.secrets): String = runBlocking {
        val hosts = EffectiveHosts(listOf("localhost"))
        val cookies = PluginCookies(tmp.root.resolve("c-${System.nanoTime()}.json"), hosts)
        val http = PluginHttp(OkHttpClient(), "test", hosts, "1.0", cookies = cookies, allowInsecureLocalhost = true, log = { logs += it })
        val host = DefaultPluginHost(
            "test", http, PluginStorage(tmp.root.resolve("s-${System.nanoTime()}.json")), cookies = cookies,
            allowInsecureLocalhost = true, secrets = secrets, logger = { logs += it },
        )
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

    // --- Redaction (spec §5): every form of an opened value comes back to the plugin as its marker. ---

    private val tricky = "k3y with/sl?sh+plus~*>"
    private val trickySecrets = PluginSecrets(
        mapOf("apiKey" to TestSealing.seal(tricky, "owner/repo", "apiKey")), "owner/repo", TestSealing.agreement,
        sealedHosts = listOf("localhost"), recipient = TestSealing.TEST_PUBLIC,
    )

    /** How a server may echo [plain] back: as is, form- and component-encoded, base64 and base64url with and without padding. */
    private fun echoForms(plain: String): List<String> {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val form = java.net.URLEncoder.encode(plain, "UTF-8")
        return listOf(
            plain, form, form.replace("+", "%20"),
            java.util.Base64.getEncoder().encodeToString(bytes), java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes),
            java.util.Base64.getUrlEncoder().encodeToString(bytes), java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        )
    }

    @Test fun `echoed value is redacted in every encoding`() {
        val forms = echoForms(tricky)
        server.enqueue(
            MockResponse().setBody(forms.joinToString("\n")).addHeader("X-Echo", forms.joinToString(" | "))
                .apply { forms.forEachIndexed { i, f -> addHeader("Set-Cookie", "c$i=$f; Path=/") } },
        )
        val base = "http://localhost:${server.port}"
        val out = home(
            """
            const k = kino.secret('apiKey');
            const r = await kino.fetch('$base/echo/' + encodeURIComponent(k) + '?k=' + encodeURIComponent(k), { headers: { 'X-Key': k } });
            const cookies = [];
            for (let i = 0; i < ${forms.size}; i++) cookies.push(kino.cookies.get('$base/', 'c' + i));
            return { url: r.url, text: r.text(), echo: r.headers['x-echo'], cookies };
            """,
            secrets = trickySecrets,
        )
        val seen = server.takeRequest()
        assertEquals(tricky, seen.getHeader("X-Key"))
        assertEquals(tricky, seen.requestUrl!!.queryParameter("k"))
        // Nothing handed to the plugin carries any form of the value, only its marker.
        for (f in forms) assertFalse("$f in $out", f in out)
        val m = trickySecrets.marker("apiKey")!!
        val o = org.json.JSONObject(out)
        assertEquals(forms.joinToString("\n") { m }, o.getString("text"))
        assertEquals(forms.joinToString(" | ") { m }, o.getString("echo"))
        assertEquals(forms.joinToString(",", "[", "]") { "\"$m\"" }, o.getJSONArray("cookies").toString())
        assertEquals("$base/echo/$m?k=$m", o.getString("url"))
    }

    @Test fun `a text body that isn't UTF-8 hands the plugin no base64 twin with the value`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                .setBody(okio.Buffer().write("año $tricky".toByteArray(Charsets.ISO_8859_1))),
        )
        val out = JSONArray(
            home(
                "const r = await kino.fetch('http://localhost:${server.port}/x?k=' + kino.secret('apiKey')); return [r.text(), r.base64()]",
                secrets = trickySecrets,
            ),
        )
        val m = trickySecrets.marker("apiKey")!!
        assertEquals("año $m", out.getString(0))
        // The raw bytes re-encoded from the redacted text, in the body's own charset.
        assertEquals("año $m", String(java.util.Base64.getDecoder().decode(out.getString(1)), Charsets.ISO_8859_1))
    }

    @Test fun `a base64 twin whose bytes don't match the declared charset still hands back no value`() {
        // Declared UTF-16, sent as UTF-8 (and as Latin-1 bytes for a non-ASCII value): the decoded
        // text is garbage redaction can't read, while the raw bytes still hold the value.
        val accented = "clé-sécrète"
        val s = PluginSecrets(
            mapOf("apiKey" to TestSealing.seal(tricky, "owner/repo", "apiKey"), "other" to TestSealing.seal(accented, "owner/repo", "other")),
            "owner/repo", TestSealing.agreement, sealedHosts = listOf("localhost"), recipient = TestSealing.TEST_PUBLIC,
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=UTF-16").setBody(okio.Buffer().write("año $tricky".toByteArray())))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=UTF-16").setBody(okio.Buffer().write("año $accented!".toByteArray(Charsets.ISO_8859_1))))
        val out = JSONArray(
            home(
                """
                const u = 'http://localhost:${server.port}/x?k=' + kino.secret('apiKey') + '&o=' + kino.secret('other');
                const a = await kino.fetch(u), b = await kino.fetch(u);
                return [a.text(), a.base64(), b.text(), b.base64()];
                """,
                secrets = s,
            ),
        )
        for (i in 0 until out.length()) {
            val v = out.getString(i)
            val decoded = if (i % 2 == 1) java.util.Base64.getDecoder().decode(v).let { listOf(String(it, Charsets.UTF_8), String(it, Charsets.ISO_8859_1)) } else listOf(v)
            for (d in decoded) {
                assertFalse("$i: $d", tricky in d)
                assertFalse("$i: $d", accented in d)
            }
        }
    }

    @Test fun `errors and logs never carry the value`() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.requestUrl!!.pathSegments[0]) {
                // A network failure on a request with the value in its path.
                "a" -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                // A server whose broken status line quotes the value, straddling the 200-character
                // cut of an error message: no piece of it may survive the cut unredacted.
                "b" -> MockResponse().setStatus("HTTP/1.1 abc " + "x".repeat(142) + request.requestUrl!!.pathSegments[1])
                // A binary body is not scanned (spec §5): the plugin can read the value there, but not log it.
                else -> MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(okio.Buffer().write(tricky.toByteArray()))
            }
        }
        val base = "http://localhost:${server.port}"
        val out = home(
            """
            const k = kino.secret('apiKey');
            const errors = [];
            for (const path of ['/a/', '/b/']) {
              try { await kino.fetch('$base' + path + encodeURIComponent(k)); errors.push('no error'); } catch (e) { errors.push([e.code, e.message]); kino.log(e.message); }
            }
            const leaked = atob((await kino.fetch('$base/c/' + encodeURIComponent(k))).base64());
            kino.log('got ' + leaked);
            console.warn(leaked, errors);
            return [errors, leaked === '$tricky'];
            """,
            secrets = trickySecrets,
        )
        val o = JSONArray(out)
        assertTrue(out, o.getBoolean(1))
        val errors = o.getJSONArray(0)
        assertEquals(out, "network", errors.getJSONArray(0).getString(0))
        assertEquals(out, "network", errors.getJSONArray(1).getString(0))
        val pieces = (0..tricky.length - 6).map { tricky.substring(it, it + 6) } + PluginSecrets.encode(tricky, PluginSecrets.Encoding.URL_COMPONENT).take(8)
        for (p in pieces) assertFalse("$p in $errors", p in errors.toString())
        assertTrue(logs.toString(), logs.any { "got " + trickySecrets.marker("apiKey") in it })
        for (p in pieces) assertFalse("$p in $logs", logs.any { p in it })
    }

    @Test fun `a sealed value a header can't carry is refused before sending, without echoing it`() {
        for (value in listOf("line1\nline2", "clé")) {
            val s = PluginSecrets(
                mapOf("apiKey" to TestSealing.seal(value, "owner/repo", "apiKey")), "owner/repo", TestSealing.agreement,
                sealedHosts = listOf("localhost"), recipient = TestSealing.TEST_PUBLIC,
            )
            logs.clear()
            server.enqueue(MockResponse().setBody("ok")) // only reached if the header is dropped instead of refused
            val out = home(
                "try { await kino.fetch('http://localhost:${server.port}/x', { headers: { 'X-Key': 'v=' + kino.secret('apiKey') } }); return 'sent' } catch (e) { return [e.code, e.message] }",
                secrets = s,
            )
            assertEquals(value, "[\"invalid_request\",\"el encabezado X-Key no puede llevar este dato sellado: tiene caracteres no permitidos\"]", out)
            assertFalse(logs.toString(), logs.any { "line1" in it || "clé" in it })
        }
        assertEquals(0, server.requestCount)
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
