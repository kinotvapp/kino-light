package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.magis.FakePortalClient
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisResolve
import com.arkiv.player.data.magis.MagisResult
import com.arkiv.player.data.magis.MagisSource
import com.arkiv.player.data.magis.testSession
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * `kino.xuper.episodes` against `MagisSource.episodesWithSeries`, on inputs recorded from a REAL
 * activated session and the real TMDB.
 *
 * Each `src/test/resources/xuper-parity/episodes-<n>.json` (local-only, never committed) was captured
 * on the phone by running the real `MagisSource.episodesWithSeries` (throwaway instrumented harness,
 * see the Task 7 report): the ref, every portal call it made with its answer, every TMDB body it
 * consumed (path + language, never the key), and the episodes/series or the error it produced. The
 * harness ran on an in-memory copy of the session and refused every call that would (re)activate or
 * log in a device; those are marked `blockedByHarness` and replayed as the same refusal. The only
 * edit is the redaction each file's `redaction` field describes; the `case` field is an added label.
 *
 * Per fixture, both sides are fed the SAME recorded answers:
 *  1. the real [MagisSource] on the replay reproduces the device's episodes/series or error exactly,
 *     with the same portal and TMDB calls -- proves the replay is faithful;
 *  2. `xuperEpisodes` makes the same portal and TMDB calls, in the same order, and:
 *     - where the device got a listing, rebuilding MagisSource's `GatewayEpisode`s/`GatewaySeries`
 *       from the answer gives back the device's JSON, field for field;
 *     - where the device got an error from a portal answer, the envelope carries that answer's code
 *       through Task 4's `toPluginError()`; any other error is `unavailable` with MagisSource's text;
 *  3. the plugin contract reader ([PluginOutput.episodes]) keeps every episode and the series ids.
 */
class XuperEpisodesParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var tmdbServer: MockWebServer
    private var tmdbBodies: Map<String, Pair<Int, String>> = emptyMap()
    private val tmdbCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        tmdbServer = MockWebServer()
        tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = tmdbKey(request.requestUrl!!.encodedPath, request.requestUrl!!.queryParameter("language"))
                tmdbCalls.add(key)
                val (code, body) = tmdbBodies[key] ?: (404 to "{}")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
        tmdbServer.start()
    }

    @After fun tearDown() = tmdbServer.shutdown()

    /** TMDB's es-MX and en-US season calls share a path: the language tells them apart. */
    private fun tmdbKey(path: String, language: String?) = "$path@$language"

    private fun tmdb() = TmdbApi(
        apiKey = "k",
        language = "es-MX",
        baseUrl = tmdbServer.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    private fun fixtures(): List<Pair<String, JSONObject>> =
        File("src/test/resources/xuper-parity").listFiles { f -> f.name.startsWith("episodes-") }!!
            .sortedBy { it.name.removePrefix("episodes-").removeSuffix(".json").toInt() }
            .map { it.name to JSONObject(it.readText()) }

    private fun recordedTmdb(fixture: JSONObject): List<String> {
        val calls = fixture.getJSONArray("tmdb")
        return (0 until calls.length()).map { i ->
            calls.getJSONObject(i).let { tmdbKey(it.getString("path"), it.opt("language") as? String) }
        }
    }

    /** Loads the fixture's TMDB bodies into the mock server and forgets the calls of the last one. */
    private fun loadTmdb(fixture: JSONObject) {
        val calls = fixture.getJSONArray("tmdb")
        tmdbBodies = (0 until calls.length()).associate { i ->
            val c = calls.getJSONObject(i)
            tmdbKey(c.getString("path"), c.opt("language") as? String) to (c.getInt("code") to c.getString("body"))
        }
        tmdbCalls.clear()
    }

    /** A portal fake answering exactly what the device's portal (or the harness) answered, in order. */
    private fun replayPortal(fixture: JSONObject): FakePortalClient {
        val fake = FakePortalClient()
        val calls = fixture.getJSONArray("portal")
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            fake.queueResponse(c.getString("path"), answerOf(c))
        }
        return fake
    }

    private fun answerOf(c: JSONObject): MagisResult<JSONObject> = when {
        c.has("ok") -> MagisResult.Ok(c.getJSONObject("ok"))
        c.has("portalError") -> c.getJSONObject("portalError").let {
            MagisResult.PortalError(it.getString("code"), it.optString("msg").takeIf { m -> m.isNotEmpty() })
        }
        else -> MagisResult.RedError(java.io.IOException(c.getString("redError")))
    }

    private fun bridge(fake: FakePortalClient): MagisPluginBridge = testSession(fake).let { session ->
        MagisPluginBridge(MagisCatalog(fake, session), MagisResolve(fake, session), tmdb(), vodStore = null, streams = XuperStreams())
    }

    private fun host(bridge: MagisPluginBridge) = DefaultPrivilegedXuperHost(
        "xuper",
        PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
        PluginStorage(File(tmp.newFolder(), "storage.json")),
        PluginConfig.EMPTY,
        null,
        EffectiveHosts(emptyList()),
        lazyOf(bridge),
    )

    private fun host(fake: FakePortalClient) = host(bridge(fake))

    private fun source(fake: FakePortalClient): MagisSource = testSession(fake).let { session ->
        MagisSource(MagisCatalog(fake, session), MagisResolve(fake, session), tmdb())
    }

    /** Same serialization the capture harness used for the device's listing. */
    private fun GatewayEpisode.toJson() = JSONObject()
        .put("number", number).put("title", title).put("ref", ref)
        .put("still", still ?: JSONObject.NULL).put("tmdbTitle", tmdbTitle ?: JSONObject.NULL)
        .put("overview", overview ?: JSONObject.NULL).put("season", season ?: JSONObject.NULL)

    private fun GatewaySeries.toJson() = JSONObject()
        .put("imdbId", imdbId).put("tmdbId", tmdbId).put("seasonNumber", seasonNumber)
        .put("title", title).put("posterUrl", posterUrl).put("backdropUrl", backdropUrl)

    private fun listingJson(episodes: List<GatewayEpisode>, series: GatewaySeries?) = JSONObject()
        .put("episodes", JSONArray(episodes.map { it.toJson() }))
        .put("series", series?.toJson() ?: JSONObject.NULL)

    /** `as? String`: an absent key is MagisSource's null (the JVM's org.json has no "null" text trap here). */
    private fun JSONObject.nullableString(key: String): String? = opt(key) as? String

    /** The episodes/series `MagisSource.episodesWithSeries` would have returned, rebuilt from one `xuperEpisodes` answer. */
    private fun rebuilt(data: JSONObject): JSONObject {
        val list = data.getJSONArray("episodes")
        val episodes = (0 until list.length()).map { i ->
            list.getJSONObject(i).let {
                GatewayEpisode(
                    number = it.getInt("number"),
                    title = it.getString("title"),
                    ref = it.getString("ref"),
                    still = it.nullableString("still"),
                    tmdbTitle = it.nullableString("tmdbTitle"),
                    overview = it.nullableString("overview"),
                )
            }
        }
        val series = data.optJSONObject("series")?.let {
            val ids = it.getJSONObject("ids")
            GatewaySeries(
                imdbId = ids.getString("imdb"),
                tmdbId = ids.getInt("tmdb"),
                seasonNumber = it.getInt("seasonNumber"),
                title = it.getString("title"),
                posterUrl = it.getString("poster"),
                backdropUrl = it.getString("backdrop"),
            )
        }
        return listingJson(episodes, series)
    }

    /** Key order differs between Android's and the JVM's org.json, so JSON compares key-sorted, recursively. */
    private fun sorted(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":${sorted(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { sorted(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    /** Key order differs between Android's and the JVM's org.json, so beans compare key-sorted. */
    private fun canonical(bean: JSONObject) = bean.keys().asSequence().sorted().joinToString(",") { "$it=${bean.get(it)}" }

    /** Path and bean of every call, in order; a call the harness refused is compared by path only. */
    private fun calls(fake: FakePortalClient, fixture: JSONObject): List<String> {
        val recorded = fixture.getJSONArray("portal")
        return fake.calls.mapIndexed { i, (path, bean) ->
            val blocked = i < recorded.length() && recorded.getJSONObject(i).optBoolean("blockedByHarness")
            if (blocked) path else path + " " + canonical(JSONObject(bean))
        }
    }

    private fun recordedCalls(fixture: JSONObject): List<String> {
        val recorded = fixture.getJSONArray("portal")
        return (0 until recorded.length()).map { i ->
            val c = recorded.getJSONObject(i)
            if (c.optBoolean("blockedByHarness")) c.getString("path") else c.getString("path") + " " + canonical(c.getJSONObject("bean"))
        }
    }

    /** The answer that ended the listing: the last portal call the harness let through. */
    private fun lastPortalAnswer(fixture: JSONObject): MagisResult<JSONObject>? {
        val recorded = fixture.getJSONArray("portal")
        return (recorded.length() - 1 downTo 0).map { recorded.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("blockedByHarness") }
            ?.let(::answerOf)
    }

    /** The code/message the envelope must carry for a fixture whose device listing failed. */
    private fun expectedFailure(fixture: JSONObject): Pair<String, String> {
        val error = fixture.getJSONObject("expected").getJSONObject("error")
        return when (val last = lastPortalAnswer(fixture)) {
            is MagisResult.PortalError -> last.toPluginError()
            is MagisResult.RedError -> last.toPluginError()
            else -> PluginErrors.UNAVAILABLE to error.getString("message")
        }
    }

    private fun ref(fixture: JSONObject) = fixture.getJSONObject("request").getString("ref")

    private fun JSONObject.expectedEpisodes(): List<JSONObject> = getJSONObject("expected").getJSONArray("episodes")
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `the fixtures cover the capturable cases`() {
        val all = fixtures().map { it.second }
        assertEquals(10, all.size)
        val listings = all.filter { it.getJSONObject("expected").has("episodes") }
        val withSeries = listings.filter { it.getJSONObject("expected").opt("series") is JSONObject }
        // Full TMDB metadata: every chapter enriched, with and without the en-US synopsis fallback.
        val full = withSeries.filter { f -> f.expectedEpisodes().all { it.opt("still") is String && it.opt("overview") is String } }
        assertTrue(full.any { f -> recordedTmdb(f).any { it.endsWith("@en-US") } })
        assertTrue(full.any { f -> recordedTmdb(f).none { it.endsWith("@en-US") } })
        // Enriched although the listing names no season list (read as "the 1st"), and although fewer
        // chapters are published than declared. Which fixture is which is its `case` label (set at
        // capture time, local-only like the fixture); the mutation checks in the Task 7 report show
        // these two are what catch a change to either rule.
        assertTrue(full.any { it.getString("case") == "single-season-list" })
        assertTrue(full.any { it.getString("case") == "declared-over-published" })
        // A series with no IMDb id: no TMDB call at all, no series block.
        assertTrue(listings.any { it.getJSONObject("expected").isNull("series") && recordedTmdb(it).isEmpty() })
        // An imdb whose enrichment didn't come out: series block, chapters without stills.
        assertTrue(withSeries.any { f -> f.expectedEpisodes().none { it.opt("still") is String } })
        // A series the portal doesn't have.
        val failureCodes = all.filter { it.getJSONObject("expected").has("error") }.map { expectedFailure(it).first }.toSet()
        assertEquals(setOf(PluginErrors.NOT_FOUND), failureCodes)
    }

    @Test fun `MagisSource on the replay reproduces the device's listing`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            loadTmdb(fixture)
            val fake = replayPortal(fixture)
            val expected = fixture.getJSONObject("expected")
            try {
                val (episodes, series) = source(fake).episodesWithSeries(ref(fixture))
                assertTrue(name + " listed but the device failed: " + expected, expected.has("episodes"))
                assertEquals(name, sorted(expected.getJSONArray("episodes")), sorted(JSONArray(episodes.map { it.toJson() })))
                assertEquals(name, sorted(expected.get("series")), sorted(series?.toJson() ?: JSONObject.NULL))
            } catch (e: AssertionError) {
                throw e
            } catch (e: Exception) {
                assertTrue("$name failed but the device listed: $e", expected.has("error"))
                val error = expected.getJSONObject("error")
                assertEquals(name, error.getString("type"), e.javaClass.simpleName)
                assertEquals(name, error.getString("message"), e.message)
            }
            assertEquals(name, recordedCalls(fixture), calls(fake, fixture))
            assertEquals(name, recordedTmdb(fixture), tmdbCalls.toList())
        }
    }

    @Test fun `xuperEpisodes matches MagisSource episodesWithSeries for every captured fixture`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            loadTmdb(fixture)
            val fake = replayPortal(fixture)
            val expected = fixture.getJSONObject("expected")
            val envelope = JSONObject(host(fake).xuperEpisodes(ref(fixture)))

            if (expected.has("episodes")) {
                assertTrue(name + ": " + envelope, envelope.getBoolean("ok"))
                val device = JSONObject().put("episodes", expected.getJSONArray("episodes")).put("series", expected.get("series"))
                assertEquals(name, sorted(device), sorted(rebuilt(envelope.getJSONObject("data"))))
            } else {
                assertFalse(name + ": " + envelope, envelope.getBoolean("ok"))
                val (code, message) = expectedFailure(fixture)
                assertEquals(name, code, envelope.getString("code"))
                assertEquals(name, message, envelope.getString("message"))
            }
            assertEquals(name, recordedCalls(fixture), calls(fake, fixture))
            assertEquals(name, recordedTmdb(fixture), tmdbCalls.toList())
        }
    }

    @Test fun `the plugin contract reader keeps every episode and the series ids`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            val expected = fixture.getJSONObject("expected")
            if (!expected.has("episodes")) continue
            loadTmdb(fixture)
            val data = JSONObject(host(replayPortal(fixture)).xuperEpisodes(ref(fixture))).getJSONObject("data")
            val read = PluginOutput.episodes(data.toString())
            val device = fixture.expectedEpisodes()
            assertEquals(name, device.map { it.getInt("number") to it.getString("ref") }, read.episodes.map { it.number to it.ref })
            assertEquals(name, device.map { it.opt("still") as? String ?: "" }, read.episodes.map { it.still })
            assertEquals(name, device.map { it.opt("overview") as? String ?: "" }, read.episodes.map { it.overview })
            val series = expected.optJSONObject("series")
            if (series == null) {
                assertEquals(name, null, read.series)
            } else {
                assertEquals(name, series.getString("imdbId"), read.series!!.imdbId)
                assertEquals(name, series.getInt("tmdbId"), read.series!!.tmdbId)
                assertEquals(name, series.getString("title"), read.series!!.title)
                assertEquals(name, series.getString("posterUrl"), read.series!!.poster)
                assertEquals(name, series.getString("backdropUrl"), read.series!!.backdrop)
            }
        }
    }

    @Test fun `a second listing of the same series reuses the chapters, and so does resolve`() = runBlocking {
        val (_, fixture) = fixtures().first { it.second.getJSONObject("expected").has("episodes") && recordedTmdb(it.second).isNotEmpty() }
        loadTmdb(fixture)
        val fake = replayPortal(fixture)
        val listing = fixture.getJSONArray("portal").getJSONObject(0).getString("path")
        val host = host(fake)
        val first = JSONObject(host.xuperEpisodes(ref(fixture)))
        val second = JSONObject(host.xuperEpisodes(ref(fixture)))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals(sorted(first), sorted(second))
        assertEquals(1, fake.timesCalled(listing))
        // resolve shares the same chapter cache: playing chapter 1 doesn't list the chapters again.
        host.xuperResolve(first.getJSONObject("data").getJSONArray("episodes").getJSONObject(0).getString("ref"))
        assertEquals(1, fake.timesCalled(listing))
    }

    // --- not device captures ---------------------------------------------------------------------
    //
    // A geo-blocked session couldn't be produced on the test phone without touching its real
    // session/region state (see the Task 5 and Task 6 reports). This feeds the geo-block answer
    // through a fake portal and checks the code reaches Task 4's mapping, while MagisSource shows it
    // as its own blocked dialog.

    @Test fun `a geo-blocked listing is geo_blocked, and MagisSource shows it as blocked`() = runBlocking {
        val blocked = MagisResult.PortalError("portal100024", "blocked")
        val ref = "magis1:teleplay:0:ABC"
        val envelope = JSONObject(host(FakePortalClient().apply { defaultResponse = blocked }).xuperEpisodes(ref))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals(PluginErrors.GEO_BLOCKED, envelope.getString("code"))
        assertEquals("blocked", envelope.getString("message"))
        try {
            source(FakePortalClient().apply { defaultResponse = blocked }).episodesWithSeries(ref)
            fail("MagisSource listed a geo-blocked series")
        } catch (e: GatewayBlockedException) {
            // expected: the same answer MagisSource turns into its blocked dialog
        }
    }

    @Test fun `a ref that isn't Xuper's is unavailable with MagisSource's text`() = runBlocking {
        val envelope = JSONObject(host(FakePortalClient()).xuperEpisodes("https://example.com/video.mp4"))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals(PluginErrors.UNAVAILABLE, envelope.getString("code"))
        assertEquals("ese ref no es de Xuper: no se pueden listar capítulos", envelope.getString("message"))
    }
}
