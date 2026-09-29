package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.FakePortalClient
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.data.magis.MagisResolve
import com.arkiv.player.data.magis.MagisResult
import com.arkiv.player.data.magis.testSession
import com.arkiv.player.ui.home.MagisHomeCatalog
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `kino.xuper.search` against what the native `MagisSource.search` produced on a REAL activated
 * session, frozen in the capture.
 *
 * Each `src/test/resources/xuper-parity/search-<n>.json` was captured on the phone by running the
 * real `MagisSource.search` (throwaway instrumented harness, see the Task 5 report): the request,
 * every portal `v3/searchByName` answer and every TMDB body it consumed, in call order, and the
 * results it produced (`expected`). The only edit is the redaction its own `redaction` field
 * describes.
 *
 * `MagisSource` itself is gone (Task 13c). Until then this class also replayed every fixture through
 * it and required it to reproduce `expected` exactly, which proved the replay faithful; that check
 * passed on the last tree that still had the class, and the fixtures are unchanged since. What stays:
 *  1. `xuperSearch`, fed the recorded answers, makes the same portal calls, in the same order, with
 *     the same beans, and each of its items carries everything the device's `GatewayResult` had:
 *     rebuilding the result from the item plus the request gives back the device's frozen JSON,
 *     field for field;
 *  2. the plugin contract reader ([PluginOutput.page]) keeps every item it returned.
 */
class XuperSearchParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var tmdbServer: MockWebServer
    private var tmdbBodies: Map<String, Pair<Int, String>> = emptyMap()

    @Before fun setUp() {
        tmdbServer = MockWebServer()
        tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (code, body) = tmdbBodies[request.requestUrl!!.encodedPath] ?: (404 to "{}")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
        tmdbServer.start()
    }

    @After fun tearDown() = tmdbServer.shutdown()

    private fun tmdb() = TmdbApi(
        apiKey = "k",
        language = "es-MX",
        baseUrl = tmdbServer.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    private fun fixtures(): List<Pair<String, JSONObject>> =
        File("src/test/resources/xuper-parity").listFiles { f -> f.name.startsWith("search-") }!!
            .sortedBy { it.name }
            .map { it.name to JSONObject(it.readText()) }

    /** A portal fake answering exactly what the device's portal answered, in the same order. */
    private fun replayPortal(fixture: JSONObject): FakePortalClient {
        val fake = FakePortalClient()
        val calls = fixture.getJSONArray("portal")
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val result: MagisResult<JSONObject> = when {
                c.has("ok") -> MagisResult.Ok(c.getJSONObject("ok"))
                c.has("portalError") -> c.getJSONObject("portalError").let {
                    MagisResult.PortalError(it.getString("code"), it.optString("msg").takeIf { m -> m.isNotEmpty() })
                }
                else -> MagisResult.RedError(java.io.IOException(c.getString("redError")))
            }
            fake.queueResponse(c.getString("path"), result)
        }
        return fake
    }

    private fun loadTmdb(fixture: JSONObject) {
        val calls = fixture.getJSONArray("tmdb")
        tmdbBodies = (0 until calls.length()).associate { i ->
            val c = calls.getJSONObject(i)
            c.getString("path") to (c.getInt("code") to c.getString("body"))
        }
    }

    /** What `kino.xuper.search` is called with: the request's own fields. */
    private fun argsJson(request: JSONObject) = JSONObject()
        .put("q", request.getString("q"))
        .put("type", request.getString("type"))
        .put("season", request.getInt("season"))
        .put("episode", request.getInt("episode"))
        .put("tmdbId", request.getInt("tmdbId"))
        .toString()

    /** Same serialization the capture harness used for the device's results. */
    private fun GatewayResult.toJson() = JSONObject()
        .put("source", source).put("title", title).put("ref", ref).put("kind", kind)
        .put("lang", lang).put("quality", quality).put("sizeBytes", sizeBytes).put("seeders", seeders)
        .put("year", year).put("season", season).put("episode", episode)
        .put("extra", JSONObject(extra.toSortedMap() as Map<*, *>))

    /**
     * The `GatewayResult` the retired `MagisSource.resultFrom` would have built, rebuilt from one
     * `xuperSearch` item plus the request -- so nothing the device's result carried may be missing
     * from the item.
     */
    private fun rebuilt(item: JSONObject, request: JSONObject): JSONObject {
        val ref = MagisRef.decode(item.getString("ref"))!!
        val extra = sortedMapOf(
            "content_id" to item.getString("id"),
            "program_type" to ref.programType,
            "episode_count" to item.getInt("episodeCount").toString(),
        )
        item.optString("poster").takeIf { it.isNotEmpty() }?.let { extra["poster"] = it }
        item.optString("backdrop").takeIf { it.isNotEmpty() }?.let { extra["backdrop"] = it }
        return GatewayResult(
            source = "magis",
            title = item.getString("title"),
            ref = item.getString("ref"),
            kind = request.getString("type"),
            year = item.getString("year"),
            season = item.getInt("season"),
            episode = ref.episode,
            extra = extra,
        ).toJson()
    }

    private fun host(fake: FakePortalClient) = DefaultPrivilegedXuperHost(
        "xuper",
        PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
        PluginStorage(File(tmp.newFolder(), "storage.json")),
        PluginConfig.EMPTY,
        null,
        testSession(fake).let { session ->
            lazyOf(
                MagisPluginBridge(
                    MagisCatalog(fake, session), MagisResolve(fake, session), tmdb(),
                    vodStore = null, streams = XuperStreams(), homeCatalog = MagisHomeCatalog(tree = { emptyList() }),
                ),
            )
        },
    )

    /** Key order differs between Android's and the JVM's org.json, so beans compare key-sorted. */
    private fun canonical(bean: JSONObject) = bean.keys().asSequence().sorted().joinToString(",") { "$it=${bean.get(it)}" }

    private fun beans(fake: FakePortalClient) = fake.calls.map { (path, bean) -> path to canonical(JSONObject(bean)) }

    private fun recordedBeans(fixture: JSONObject): List<Pair<String, String>> {
        val calls = fixture.getJSONArray("portal")
        return (0 until calls.length()).map { i ->
            val c = calls.getJSONObject(i)
            c.getString("path") to canonical(c.getJSONObject("bean"))
        }
    }

    @Test fun `the fixtures cover the four capturable cases`() {
        val all = fixtures().map { it.second }
        assertEquals(4, all.size)
        // A plain match, the full-title fallback (more portal calls than title forms asked), no
        // results, and a series query with a requested season.
        assertTrue(all.any { it.getJSONObject("expected").getJSONArray("results").length() > 3 })
        assertTrue(all.any { f -> f.getJSONArray("portal").let { p -> (0 until p.length()).any { p.getJSONObject(it).getJSONObject("bean").getString("value") == "Love, Death & Robots" } } })
        assertTrue(all.any { it.getJSONObject("expected").getJSONArray("results").length() == 0 })
        assertTrue(all.any { it.getJSONObject("request").getInt("season") > 0 })
    }

    @Test fun `xuperSearch reproduces the device's captured results for every fixture`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            loadTmdb(fixture)
            val request = fixture.getJSONObject("request")
            val fake = replayPortal(fixture)
            val envelope = JSONObject(host(fake).xuperSearch(argsJson(request)))

            // Every capture is one the device answered without an error.
            assertTrue(name, fixture.getJSONObject("expected").isNull("error"))
            assertTrue(name + ": " + envelope, envelope.getBoolean("ok"))
            val data = envelope.getJSONArray("data")
            val rebuilt = JSONArray((0 until data.length()).map { rebuilt(data.getJSONObject(it), request) })
            assertEquals(name, fixture.getJSONObject("expected").getJSONArray("results").toString(2), rebuilt.toString(2))
            assertEquals(name, recordedBeans(fixture), beans(fake))

            val page = PluginOutput.page(data.toString(), PluginOutput.MAX_SEARCH_ITEMS, allowSeries = true, allowNext = false)
            assertEquals(name, data.length(), page.items.size)
            // The plugin `kind` is what makes the app list chapters: series exactly for Magis's series types.
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val isSeries = MagisRef.decode(item.getString("ref"))!!.isSeries
                assertEquals(name, if (isSeries) "series" else "movie", item.getString("kind"))
            }
        }
    }

    // --- error envelope ------------------------------------------------------------------------
    //
    // NOT device captures: a geo-blocked session couldn't be produced on the test phone without
    // touching its real session/region state (see the Task 5 report). These feed a portal error
    // through the same replay and check that the code reaches Task 4's mapping instead of being
    // flattened into a message the way the retired MagisSource's `explain` did.

    private suspend fun searchFailingWith(result: MagisResult<JSONObject>): JSONObject {
        val fake = FakePortalClient().apply { defaultResponse = result }
        return JSONObject(host(fake).xuperSearch("""{"q":"Dune","type":"movie","season":0,"episode":0,"tmdbId":0}"""))
    }

    @Test fun `a geo-blocked search is geo_blocked`() = runBlocking {
        val envelope = searchFailingWith(MagisResult.PortalError("portal100024", "blocked"))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals(PluginErrors.GEO_BLOCKED, envelope.getString("code"))
        assertEquals("blocked", envelope.getString("message"))
    }

    @Test fun `null type and tmdbId read as a plain movie search`() = runBlocking {
        val fake = FakePortalClient()
        JSONObject(host(fake).xuperSearch("""{"q":"Dune","type":null,"tmdbId":null}"""))
        assertEquals(listOf("Dune"), fake.calls.map { it.second["value"] })
    }

    @Test fun `a portal that never answers is unavailable`() = runBlocking {
        val envelope = searchFailingWith(MagisResult.RedError(java.io.IOException("timeout")))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals(PluginErrors.UNAVAILABLE, envelope.getString("code"))
    }

    @Test fun `one failing query is not an error when another form answered`() = runBlocking {
        val fixture = fixtures().first { it.first == "search-1.json" }.second
        loadTmdb(fixture)
        // Drop the device's answer to the first query and fail it instead: the second still answers.
        val calls = fixture.getJSONArray("portal")
        val failing = FakePortalClient().apply {
            queueResponse("v3/searchByName", MagisResult.RedError(java.io.IOException("down")))
            queueResponse("v3/searchByName", MagisResult.Ok(calls.getJSONObject(1).getJSONObject("ok")))
        }
        val envelope = JSONObject(host(failing).xuperSearch(argsJson(fixture.getJSONObject("request"))))
        assertTrue(envelope.toString(), envelope.getBoolean("ok"))
        assertTrue(envelope.getJSONArray("data").length() > 0)
    }
}
