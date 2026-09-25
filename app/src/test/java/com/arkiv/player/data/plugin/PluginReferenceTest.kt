package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two reference plugins (app/src/test/resources/plugins/reference-*, never published) end to
 * end through the real runtime, PluginHttp, the cookie jar, storage and config — the SDK v1
 * features as an author would use them.
 */
class PluginReferenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val opened = mutableListOf<PluginRuntime>()

    @After fun stop() {
        opened.forEach { it.close() }
        server.shutdown()
    }

    private fun resource(path: String) = File("src/test/resources/plugins/$path").readText()

    /** Connects wherever OkHttp asked, but on 127.0.0.1: the typed "LAN server" is this MockWebServer. */
    private object LanToLoopback : javax.net.SocketFactory() {
        private class Redirecting : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                super.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), (endpoint as java.net.InetSocketAddress).port), timeout)
            }
        }
        override fun createSocket() = Redirecting()
        override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    }

    private suspend fun runtime(id: String, script: String, hosts: EffectiveHosts, config: Map<String, Any>, base: OkHttpClient = OkHttpClient(), insecure: Boolean = false): Pair<PluginRuntime, PluginHttp> {
        val dir = tmp.root.resolve(id).apply { mkdirs() }
        val cookies = PluginCookies(File(dir, PluginCookies.FILE_NAME), hosts)
        val http = PluginHttp(base, id, hosts, "9.9.9", cookies = cookies, allowInsecureLocalhost = insecure)
        val host = DefaultPluginHost(id, http, PluginStorage(File(dir, "storage.json")), PluginConfig(config), cookies, hosts, allowInsecureLocalhost = insecure, logger = {})
        val rt = PluginRuntime.open(id, script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }
        return rt to http
    }

    // --- reference-scraper: a login form, a session cookie, HTML pages, AES-hidden links ---

    private val logins = AtomicInteger()
    private fun link(url: String) = JSONObject(PluginCrypto.run(JSONObject().put("op", "encrypt").put("alg", "aes-128-cbc").put("key", "0123456789abcdef").put("iv", "abcdef9876543210").put("data", url).toString())).getString("ok")

    private fun scraperSite(password: String = "s3cr3t", rateLimited: Boolean = false) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val loggedIn = request.getHeader("Cookie")?.contains("session=ok") == true
            val path = request.path!!
            return when {
                path == "/session" -> if (loggedIn) MockResponse().setBody("ok") else MockResponse().setResponseCode(302).setHeader("Location", "/login")
                path == "/login" -> {
                    logins.incrementAndGet()
                    val body = request.body.readUtf8()
                    if (body == "user=ana&password=$password") MockResponse().setResponseCode(302).setHeader("Location", "/").addHeader("Set-Cookie", "session=ok; Path=/; HttpOnly")
                    else MockResponse().setResponseCode(401)
                }
                !loggedIn -> MockResponse().setResponseCode(302).setHeader("Location", "/login")
                rateLimited -> MockResponse().setResponseCode(429)
                path.startsWith("/buscar") -> html(listOf("m1" to "Metrópolis"), next = null)
                path == "/catalogo" -> html(listOf("m1" to "Metrópolis", "m2" to "Nosferatu"), next = "/catalogo?p=2")
                path == "/catalogo?p=2" -> html(listOf("m3" to "Fausto"), next = null)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun html(items: List<Pair<String, String>>, next: String?) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(
            "<html><body>" + items.joinToString("") { (id, title) -> "<a class=\"card\" data-id=\"$id\" data-link=\"${link("https://cdn.example.com/v/$id.mp4")}\">$title</a>" } +
                (next?.let { "<a class=\"next\" href=\"$it\">Siguiente</a>" } ?: "") + "</body></html>",
        )
    }

    private suspend fun scraper(config: Map<String, Any> = mapOf("user" to "ana", "password" to "s3cr3t")): Pair<PluginRuntime, PluginHttp> {
        val script = resource("reference-scraper/plugin.js").replace("https://scraper.example", "http://localhost:${server.port}")
        return runtime("reference-scraper", script, EffectiveHosts(listOf("localhost", "cdn.example.com")), config, insecure = true)
    }

    @Test fun `the reference manifests are valid`() {
        listOf("reference-scraper", "reference-server").forEach { id ->
            val r = ManifestParser.parse(resource("$id/kino-plugin.json"))
            assertTrue("$id: $r", r is ManifestResult.Valid)
        }
    }

    @Test fun `scraper - logs in once, keeps the session cookie, pages with a cursor and decrypts links`() = runBlocking {
        server.dispatcher = scraperSite()
        server.start()
        val (rt, http) = scraper()
        http.beginCall()
        val search = PluginOutput.page(rt.call("search", """{"q":"metropolis"}""", 15_000), 100, false, true)
        assertEquals(listOf("Metrópolis"), search.items.map { it.title })
        val rows = PluginOutput.rows(rt.call("home", "null", 20_000), allowSeries = false, allowBrowse = true)
        assertEquals("/catalogo", rows.single().ref)
        val first = PluginOutput.page(rt.call("browse", """{"ref":"/catalogo","cursor":null}""", 20_000), 100, false, true)
        assertEquals("/catalogo?p=2", first.next)
        val second = PluginOutput.page(rt.call("browse", """{"ref":"/catalogo","cursor":"/catalogo?p=2"}""", 20_000), 100, false, true)
        assertEquals(listOf("Fausto"), second.items.map { it.title })
        assertEquals(null, second.next)
        val stream = PluginOutput.stream(rt.call("resolve", JSONObject.quote(second.items.single().ref), 20_000), EffectiveHosts(listOf("localhost", "cdn.example.com")))
        assertEquals("https://cdn.example.com/v/m3.mp4", stream.url)
        assertEquals(1, logins.get())
    }

    @Test fun `scraper - the session survives the runtime closing`() = runBlocking {
        server.dispatcher = scraperSite()
        server.start()
        scraper().first.call("search", """{"q":"a"}""", 15_000)
        scraper().first.call("search", """{"q":"b"}""", 15_000)
        assertEquals(1, logins.get())
    }

    @Test fun `scraper - a wrong password is auth_required, a busy site is rate_limited`() = runBlocking {
        server.dispatcher = scraperSite(password = "other")
        server.start()
        val auth = assertThrows(PluginErrorException::class.java) { runBlocking { scraper().first.call("search", """{"q":"a"}""", 15_000) } }
        assertEquals("auth_required", auth.code)
        server.dispatcher = scraperSite(rateLimited = true)
        tmp.root.resolve("reference-scraper/cookies.json").delete()
        assertEquals("rate_limited", assertThrows(PluginErrorException::class.java) { runBlocking { scraper().first.call("home", "null", 15_000) } }.code)
    }

    // --- reference-server: the person's own server, typed in its settings ---

    private val auths = AtomicInteger()
    private val catalog = (1..23).map { i -> JSONObject().put("id", "v$i").put("title", "Video $i").put("year", 2000 + i).put("genres", JSONArray(listOf("Casero"))).put("tmdb", if (i == 1) 603 else JSONObject.NULL) }

    private fun ownServer() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            if (path == "/auth") {
                auths.incrementAndGet()
                val body = JSONObject(request.body.readUtf8())
                return if (body.optString("password") == "s3cr3t") MockResponse().setBody("""{"token":"t-1"}""") else MockResponse().setResponseCode(401)
            }
            if (request.getHeader("X-Token") != "t-1") return MockResponse().setResponseCode(401)
            val special = mapOf("/items/missing" to 404, "/items/busy" to 429, "/items/blocked" to 451, "/items/down" to 503)
            special[path]?.let { return MockResponse().setResponseCode(it) }
            if (path.startsWith("/items/")) return MockResponse().setBody("""{"stream":"/stream/${path.removePrefix("/items/")}.mp4"}""")
            val cursor = Regex("cursor=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
            val slice = catalog.drop(cursor).take(10)
            val next = if (cursor + 10 < catalog.size) (cursor + 10).toString() else JSONObject.NULL
            return MockResponse().setBody(JSONObject().put("items", JSONArray(slice)).put("next", next).toString())
        }
    }

    private suspend fun ownServerRuntime(password: String = "s3cr3t", user: String = "ana"): Pair<PluginRuntime, EffectiveHosts> {
        val typed = UserHost("http", "10.0.2.2", server.port)
        val hosts = EffectiveHosts(listOf("example.org"), listOf(typed))
        val config = mapOf("server" to "http://10.0.2.2:${server.port}/", "user" to user, "password" to password, "hd" to true)
        val rt = runtime("reference-server", resource("reference-server/plugin.js"), hosts, config, OkHttpClient.Builder().socketFactory(LanToLoopback).build()).first
        return rt to hosts
    }

    @Test fun `own server - home, browse with cursors, search and resolve on the typed server`() = runBlocking {
        server.dispatcher = ownServer()
        server.start()
        val (rt, hosts) = ownServerRuntime()
        val row = PluginOutput.rows(rt.call("home", "null", 20_000), false, allowBrowse = true, hosts = hosts).single()
        assertEquals("all", row.ref)
        assertEquals(10, row.items.size)
        assertEquals("http://10.0.2.2:${server.port}/img/v1", row.items.first().poster)
        assertEquals(603, row.items.first().tmdbId)
        assertEquals(listOf("HD"), row.items.first().badges)
        var cursor: String? = null
        val titles = mutableListOf<String>()
        do {
            val p = PluginOutput.page(rt.call("browse", JSONObject().put("ref", "all").put("cursor", cursor ?: JSONObject.NULL).toString(), 20_000), 100, false, true, hosts)
            titles += p.items.map { it.title }
            cursor = p.next
        } while (cursor != null)
        assertEquals(23, titles.size)
        val stream = PluginOutput.stream(rt.call("resolve", JSONObject.quote("v7"), 20_000), hosts)
        assertEquals("http://10.0.2.2:${server.port}/stream/v7.mp4", stream.url)
        assertEquals(600, stream.expiresInSeconds)
        assertEquals("the token is reused", 1, auths.get())
    }

    @Test fun `own server - each HTTP status becomes its typed error`() = runBlocking {
        server.dispatcher = ownServer()
        server.start()
        val rt = ownServerRuntime().first
        mapOf("missing" to "not_found", "busy" to "rate_limited", "blocked" to "geo_blocked", "down" to "unavailable").forEach { (ref, code) ->
            assertEquals(ref, code, assertThrows(PluginErrorException::class.java) { runBlocking { rt.call("resolve", JSONObject.quote(ref), 20_000) } }.code)
        }
        val wrong = ownServerRuntime(password = "nope").first
        tmp.root.resolve("reference-server/storage.json").delete()
        assertEquals("auth_required", assertThrows(PluginErrorException::class.java) { runBlocking { wrong.call("home", "null", 20_000) } }.code)
    }

    @Test fun `own server - another user in Configurar never reuses the first user's token`() = runBlocking {
        // kino.storage survives a settings change (only the runtime, cookies and Home cache go), so
        // the plugin keys its token by user and server: the new user logs in on its own.
        server.dispatcher = ownServer()
        server.start()
        ownServerRuntime(user = "ana").first.call("home", "null", 20_000)
        assertEquals(1, auths.get())
        ownServerRuntime(user = "beto").first.call("home", "null", 20_000)
        assertEquals(2, auths.get())
        ownServerRuntime(user = "ana").first.call("home", "null", 20_000)
        assertEquals("ana's token is still hers", 2, auths.get())
    }
}
