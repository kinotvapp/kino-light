package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Xtream Codes: addresses, answers of player_api.php, the download that turns a server into an M3U, and "Probar". */
class XtreamApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val now = 1_800_000_000_000L // 2027-01-15

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun base() = "http://localhost:${server.port}"
    private fun gated() = PluginPlaylistFetcher(PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(listOf("localhost")), allowInsecureLocalhost = true))
    // Built by hand: the form refuses localhost (only a public server can be saved), the download itself does not check it.
    private fun xtreamUrl(user: String = "juan", pass: String = "s3cret") =
        XtreamUrl.apiUrl(XtreamAccount(okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(server.port).build(), user, pass))

    private val goodAuth = """{"user_info":{"username":"juan","auth":1,"status":"Active","exp_date":"${now / 1000 + 86_400 * 30}","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"evil.example.org","port":"80"}}"""
    private val categories = """[{"category_id":"1","category_name":"Noticias","parent_id":0},{"category_id":"2","category_name":"Deportes","parent_id":0}]"""
    private val streams = """[
        {"num":1,"name":"Canal Uno","stream_type":"live","stream_id":101,"stream_icon":"https://img.example.com/1.png","epg_channel_id":"uno.co","category_id":"1"},
        {"num":2,"name":"Canal Dos","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":2},
        {"num":3,"name":"","stream_id":103,"category_id":"1"},
        {"num":4,"name":"Mala","stream_id":"../etc","category_id":"1"}
    ]"""

    private fun answer(auth: String = goodAuth, cats: MockResponse = MockResponse().setBody(categories), live: MockResponse = MockResponse().setBody(streams), authCode: Int = 200) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.queryParameter("action")) {
                null -> MockResponse().setResponseCode(authCode).setBody(auth)
                "get_live_categories" -> cats
                "get_live_streams" -> live
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    // --- addresses ---

    @Test fun `the three fields become one stored address, with the login encoded`() {
        val ok = XtreamUrl.build("miservidor.com:8080", "juan", "p&ss w/rd,1") as XtreamUrl.Built.Ok
        assertTrue(ok.cleartext)
        assertTrue(XtreamUrl.isApi(ok.url))
        val account = XtreamUrl.accountOf(ok.url)!!
        assertEquals("juan", account.username)
        assertEquals("p&ss w/rd,1", account.password)
        assertEquals("miservidor.com", account.base.host)
        assertEquals(8080, account.base.port)
    }

    @Test fun `a pasted get_php or player_api address is split into server, user and password`() {
        val (server, user, pass) = XtreamUrl.split("http://tv.example.com:8000/get.php?username=ana&password=clave1&type=m3u_plus&output=ts")!!
        assertEquals("http://tv.example.com:8000", server)
        assertEquals("ana", user)
        assertEquals("clave1", pass)
        assertEquals("ana", XtreamUrl.split("https://tv.example.com/player_api.php?username=ana&password=x")!!.second)
        assertNull(XtreamUrl.split("https://tv.example.com/lista.m3u"))
        assertNull(XtreamUrl.split("http://tv.example.com/get.php?username=ana"))
        // Building from a whole address keeps only the folder in front of the file.
        val ok = XtreamUrl.build("http://tv.example.com/panel/get.php?x=1", "ana", "clave1") as XtreamUrl.Built.Ok
        assertEquals("/panel/player_api.php", java.net.URI(ok.url).path)
    }

    @Test fun `every field is validated with its own message`() {
        val bad = XtreamUrl.build("", "", "") as XtreamUrl.Built.Invalid
        assertEquals(setOf(XtreamUrl.XtreamField.SERVER, XtreamUrl.XtreamField.USERNAME, XtreamUrl.XtreamField.PASSWORD), bad.errors.keys)
        val local = XtreamUrl.build("192.168.1.10:8080", "a", "b") as XtreamUrl.Built.Invalid
        assertTrue(local.errors.getValue(XtreamUrl.XtreamField.SERVER).contains("red local"))
        val spaces = XtreamUrl.build("mi servidor.com", "a", "b") as XtreamUrl.Built.Invalid
        assertTrue(XtreamUrl.XtreamField.SERVER in spaces.errors)
    }

    @Test fun `the login is masked in any address or text that is shown`() {
        val url = "http://tv.example.com:8080/player_api.php?username=juan&password=s3cret&action=get_live_streams"
        val masked = XtreamUrl.mask(url)
        assertFalse(masked.contains("juan") || masked.contains("s3cret"))
        assertTrue(masked.contains("username=***") && masked.contains("password=***") && masked.contains("action=get_live_streams"))
        val stream = XtreamUrl.mask("failed http://tv.example.com/live/juan/s3cret/101.m3u8 now")
        assertEquals("failed http://tv.example.com/live/***/***/101.m3u8 now", stream)
        assertEquals("https://x.example.com/lista.m3u", XtreamUrl.mask("https://x.example.com/lista.m3u"))
        // Logs: a list's address never reaches them (the provider redacts every address), and the form state hides the password.
        assertFalse(redactUrls("playlist $url failed").contains("s3cret"))
        assertFalse(com.arkiv.player.ui.live.XtreamInput("h", "juan", "s3cret").toString().let { it.contains("s3cret") || it.contains("juan") })
        assertFalse(XtreamUrl.parse(url)!!.toString().contains("juan"))
    }

    @Test fun `two accounts on one server are two lists, and a rotated token is still one`() {
        val a = PlaylistSource.cacheKey("http://tv.example.com/player_api.php?username=ana&password=1")
        val b = PlaylistSource.cacheKey("http://tv.example.com/player_api.php?username=beto&password=1")
        assertTrue(a != b)
        assertEquals(a, PlaylistSource.cacheKey("http://tv.example.com/player_api.php?username=ana&password=2"))
        // Other lists keep the key they always had.
        assertEquals(PlaylistSource.cacheKey("http://tv.example.com/get.php?username=ana&password=1"), PlaylistSource.cacheKey("http://tv.example.com/get.php?username=beto&password=1"))
    }

    // --- answers ---

    @Test fun `a good account says when it expires and what formats it allows`() {
        val ok = XtreamParser.auth(goodAuth, now, java.time.ZoneOffset.UTC) as XtreamAuth.Ok
        assertEquals(setOf("m3u8", "ts"), ok.formats)
        assertEquals(2, ok.connections)
        assertEquals(now + 86_400_000L * 30, ok.expiresMs)
        assertEquals("m3u8", XtreamParser.extensionFor(ok.formats))
        assertEquals("ts", XtreamParser.extensionFor(setOf("ts")))
        assertTrue(XtreamParser.describe(ok, java.time.ZoneOffset.UTC).contains("14/02/2027"))
    }

    @Test fun `no expiry means an account that does not expire`() {
        val ok = XtreamParser.auth("""{"user_info":{"auth":"1","status":"Active","exp_date":null}}""", now) as XtreamAuth.Ok
        assertNull(ok.expiresMs)
        assertTrue(ok.formats.isEmpty())
    }

    @Test fun `a refused, expired or disabled account says so in Spanish`() {
        assertEquals(XtreamParser.BAD_LOGIN, (XtreamParser.auth("""{"user_info":{"auth":0}}""", now) as XtreamAuth.Failed).message)
        assertEquals(XtreamParser.BAD_LOGIN, (XtreamParser.auth("""{"user_info":{}}""", now) as XtreamAuth.Failed).message)
        val expired = XtreamParser.auth("""{"user_info":{"auth":1,"status":"Active","exp_date":"1700000000"}}""", now, java.time.ZoneOffset.UTC) as XtreamAuth.Failed
        assertTrue(expired.message.startsWith("La cuenta venció el 14/11/2023"))
        assertTrue((XtreamParser.auth("""{"user_info":{"auth":1,"status":"Expired"}}""", now) as XtreamAuth.Failed).message.startsWith("La cuenta venció"))
        assertEquals(XtreamParser.DISABLED, (XtreamParser.auth("""{"user_info":{"auth":1,"status":"Banned"}}""", now) as XtreamAuth.Failed).message)
    }

    @Test fun `an answer that is not Xtream is told apart, never a crash`() {
        for (text in listOf("", "<html>Login</html>", "[]", """{"hello":1}""", "{broken", """{"user_info":"x"}""")) {
            assertEquals(text, XtreamParser.NOT_XTREAM, (XtreamParser.auth(text, now) as XtreamAuth.Failed).message)
        }
    }

    @Test fun `channels and categories are read through the dialects of the panels`() {
        val cats = XtreamParser.categories(categories)
        assertEquals(mapOf("1" to "Noticias", "2" to "Deportes"), cats)
        val live = XtreamParser.streams(streams)
        // The nameless one and the one whose id is not a number are dropped.
        assertEquals(listOf("101", "102"), live.map { it.id })
        assertEquals("uno.co", live[0].epgId)
        assertEquals("", live[1].epgId)
        assertEquals("2", live[1].categoryId)
        assertTrue(XtreamParser.streams("not json").isEmpty())
        assertTrue(XtreamParser.categories("""{"a":1}""").isEmpty())
    }

    // --- the download ---

    @Test fun `a server is saved as an M3U with groups, guide ids and stream addresses on the typed server`() = runBlocking {
        answer()
        val file = File(tmp.root, "out.m3u")
        XtreamPlaylistFetcher(gated(), clock = { now }).fetchTo(xtreamUrl(), emptyMap(), 20L * 1024 * 1024, file)
        val m3u = M3uParser.parse(file)
        assertEquals(listOf("Canal Uno", "Canal Dos"), m3u.entries.map { it.name })
        assertEquals(listOf("Noticias", "Deportes"), m3u.entries.map { it.group })
        assertEquals("uno.co", m3u.entries[0].tvgId)
        assertEquals("${base()}/live/juan/s3cret/101.m3u8", m3u.entries[0].url)
        assertEquals("https://img.example.com/1.png", m3u.entries[0].logo)
        // The guide is the server's xmltv.php, and it is the typed server, not the one the answer reported.
        assertEquals(listOf("${base()}/xmltv.php?username=juan&password=s3cret"), m3u.epgUrls)
        assertFalse(file.readText().contains("evil.example.org"))
    }

    @Test fun `an account that only allows ts gets ts addresses`() = runBlocking {
        answer(auth = goodAuth.replace("""["m3u8","ts"]""", """["ts"]"""))
        val file = File(tmp.root, "out.m3u")
        XtreamPlaylistFetcher(gated(), clock = { now }).fetchTo(xtreamUrl(), emptyMap(), 20L * 1024 * 1024, file)
        assertTrue(M3uParser.parse(file).entries.all { it.url.endsWith(".ts") })
    }

    @Test fun `the requests carry the login, the action and the list's headers`() = runBlocking {
        answer()
        XtreamPlaylistFetcher(gated(), clock = { now }).fetchTo(xtreamUrl(), mapOf("User-Agent" to "Kino-Test"), 20L * 1024 * 1024, File(tmp.root, "o.m3u"))
        val seen = (1..3).map { server.takeRequest() }
        assertEquals(listOf(null, "get_live_categories", "get_live_streams"), seen.map { it.requestUrl?.queryParameter("action") })
        assertTrue(seen.all { it.requestUrl?.queryParameter("username") == "juan" && it.requestUrl?.queryParameter("password") == "s3cret" && it.path!!.startsWith("/player_api.php") })
        assertTrue(seen.all { it.getHeader("User-Agent") == "Kino-Test" })
    }

    private suspend fun failure(fetcher: XtreamPlaylistFetcher, url: String = xtreamUrl()): XtreamException {
        val file = File(tmp.root, "f.m3u")
        try {
            fetcher.fetchTo(url, emptyMap(), 20L * 1024 * 1024, file)
        } catch (e: XtreamException) {
            assertFalse("a failed download leaves no file", file.exists())
            return e
        }
        throw AssertionError("expected a failure")
    }

    @Test fun `a refused login fails with its message and leaves no file`() = runTest {
        answer(auth = """{"user_info":{"auth":0}}""")
        assertEquals(XtreamParser.BAD_LOGIN, failure(XtreamPlaylistFetcher(gated(), clock = { now })).message)
    }

    @Test fun `an expired account fails with its date`() = runTest {
        answer(auth = """{"user_info":{"auth":1,"status":"Active","exp_date":"1700000000"}}""")
        assertTrue(failure(XtreamPlaylistFetcher(gated(), clock = { now })).message!!.startsWith("La cuenta venció"))
    }

    @Test fun `HTTP errors and malformed answers become clear messages with no address in them`() = runTest {
        val fetcher = XtreamPlaylistFetcher(gated(), clock = { now })
        for ((code, expected) in listOf(403 to "rechazó la cuenta", 404 to "no tiene la API Xtream", 500 to "El servidor falló", 429 to "HTTP 429")) {
            answer(authCode = code)
            val m = failure(fetcher).message!!
            assertTrue("$code -> $m", m.contains(expected))
            assertFalse(m.contains("s3cret") || m.contains("localhost") || m.contains("juan"))
        }
        answer(auth = "<html>maintenance</html>")
        assertEquals(XtreamParser.NOT_XTREAM, failure(fetcher).message)
        answer(live = MockResponse().setBody("[]"))
        assertTrue(failure(fetcher).message!!.contains("no tiene canales en vivo"))
        answer(live = MockResponse().setResponseCode(502))
        assertTrue(failure(fetcher).message!!.contains("El servidor falló"))
    }

    @Test fun `an unreachable or slow server says so`() = runTest {
        val url = xtreamUrl()
        server.shutdown()
        assertTrue(failure(XtreamPlaylistFetcher(gated(), clock = { now }), url).message!!.contains("No se pudo conectar"))
        assertEquals("El servidor tardó demasiado en responder", XtreamPlaylistFetcher.describe(java.net.SocketTimeoutException()))
        assertEquals("No encontré ese servidor", XtreamPlaylistFetcher.describe(java.net.UnknownHostException()))
        assertTrue(XtreamPlaylistFetcher.describe(PlaylistTooLargeException(12)).contains("demasiados datos"))
    }

    @Test fun `any other address goes to the inner fetcher untouched`() = runBlocking {
        var asked: String? = null
        val inner = object : LivePlaylistFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long) = "#EXTM3U\n".toByteArray().also { asked = url }
        }
        val f = File(tmp.root, "x.m3u")
        XtreamPlaylistFetcher(inner).fetchTo("https://tv.example.com/get.php?username=a&password=b", emptyMap(), 1024, f)
        assertEquals("https://tv.example.com/get.php?username=a&password=b", asked)
        assertEquals("#EXTM3U\n", f.readText())
    }

    // --- "Probar" ---

    @Test fun `Probar tells the login state and counts the live channels`() = runBlocking {
        answer()
        val r = OwnSourceProbe.run(OwnKind.PLAYLIST, xtreamUrl(), emptyMap(), gated()) as OwnProbe.Ok
        assertTrue(r.message, r.message.contains("correctos") && r.message.contains("2 canales en vivo"))
        answer(auth = """{"user_info":{"auth":0}}""")
        assertEquals(XtreamParser.BAD_LOGIN, (OwnSourceProbe.run(OwnKind.PLAYLIST, xtreamUrl(), emptyMap(), gated()) as OwnProbe.Failed).message)
        answer(authCode = 403)
        val denied = (OwnSourceProbe.run(OwnKind.PLAYLIST, xtreamUrl(), emptyMap(), gated()) as OwnProbe.Failed).message
        assertTrue(denied.contains("rechazó") && !denied.contains("s3cret"))
    }

    @Test fun `a pasted player_api answer is recognised, and refused as a list or a channel`() {
        val r = OwnSourceProbe.classify(OwnKind.PLAYLIST, goodAuth.toByteArray())
        assertTrue(r is OwnProbe.Ok)
        assertTrue((OwnSourceProbe.classify(OwnKind.CHANNEL, goodAuth.toByteArray()) as OwnProbe.Failed).message.contains("Xtream"))
        assertTrue((OwnPastedList.check(goodAuth) as OwnPastedCheck.Refused).message.contains("Xtream"))
    }

    // --- end to end through the provider ---

    /** A server that is not local: the provider only lists public addresses. */
    private fun fakeServer(auth: String = goodAuth) = LivePlaylistFetcher { url, _, _ ->
        when (url.toHttpUrl().queryParameter("action")) {
            null -> auth
            "get_live_categories" -> categories
            else -> streams
        }.toByteArray()
    }

    private val publicUrl = "http://tv.example.com:8080/player_api.php?username=juan&password=s3cret"

    private fun provider(fetcher: LivePlaylistFetcher) = OwnLiveProvider(
        sources = { listOf(OwnLiveSourceEntity("x1", "PLAYLIST", "Mi Xtream", publicUrl)) },
        fetcher = fetcher, cacheDir = tmp.newFolder(), allCachesRoot = null, clock = { now }, log = {},
    )

    @Test fun `the provider lists an Xtream server's channels by category and opens them with the guide`() = runTest {
        val p = provider(XtreamPlaylistFetcher(fakeServer(), clock = { now }))
        val cats = p.categories(includeAdults = false)
        assertEquals(setOf("Noticias", "Deportes"), cats.map { it.name }.toSet())
        val channels = cats.flatMap { p.channels(it.id) }
        assertEquals(setOf("Canal Uno", "Canal Dos"), channels.map { it.name }.toSet())
        assertTrue(p.hasGuide())
        assertNull(p.notice.value)
        val opened = p.open(channels.first { it.name == "Canal Uno" }) as LiveOpening.Plugin
        assertEquals("http://tv.example.com:8080/live/juan/s3cret/101.m3u8", opened.channel.direct!!.url)
    }

    @Test fun `a refused login shows up as the list's notice`() = runTest {
        val p = provider(XtreamPlaylistFetcher(fakeServer("""{"user_info":{"auth":0}}"""), clock = { now }))
        assertTrue(p.categories(includeAdults = false).isEmpty())
        assertEquals("Mi Xtream: ${XtreamParser.BAD_LOGIN}", p.notice.value)
    }
}
