package com.arkiv.player.data.plugin.discovery

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommunityListParserTest {
    private fun entry(owner: Any?, repo: Any?, stars: Any? = 1) = JSONObject().put("owner", owner).put("repo", repo).put("stars", stars)
    private fun list(vararg entries: JSONObject, schema: Int = 1) =
        JSONObject().put("schema", schema).put("plugins", JSONArray(entries.toList())).toString()

    @Test fun `a valid list gives its repos in order`() {
        val repos = CommunityListParser.parse(list(entry("xuper-plugin", "kino-plugin-xuper", 12), entry("a", "b", 3)))
        assertEquals(listOf(DiscoveredRepo("xuper-plugin", "kino-plugin-xuper", 12), DiscoveredRepo("a", "b", 3)), repos)
    }

    @Test fun `bad entries are skipped, the rest kept`() {
        val repos = CommunityListParser.parse(
            list(
                entry("ok", "one", 5),
                entry("..", "x"),
                entry("a", ".."),
                entry("a/b", "c"),
                entry("https://evil.example", "x"),
                entry("a", "b@v1"),
                entry("a b", "c"),
                entry(5, "c"),
                entry("a", null),
                entry("neg", "stars", -9),
                entry("str", "stars", "lots"),
                entry("OK", "ONE", 1),
            ),
        )
        assertEquals(listOf(DiscoveredRepo("ok", "one", 5), DiscoveredRepo("neg", "stars", 0), DiscoveredRepo("str", "stars", 0)), repos)
    }

    @Test fun `a hostile body is refused whole`() {
        assertNull(CommunityListParser.parse("garbage"))
        assertNull(CommunityListParser.parse("[]"))
        assertNull(CommunityListParser.parse(list(entry("a", "b"), schema = 2)))
        assertNull(CommunityListParser.parse("""{"schema":1}"""))
        assertNull(CommunityListParser.parse("""{"schema":1,"plugins":[{'owner':'a','repo':'b'}]}"""))
        assertNull(CommunityListParser.parse("""{"schema":1,"plugins":[]} // comment"""))
        assertNull(CommunityListParser.parse("""{"schema":1,"plugins":${"[".repeat(5000)}${"]".repeat(5000)}}"""))
        assertNull(CommunityListParser.parse("""{"schema":1,"pad":"${"x".repeat(70 * 1024)}","plugins":[{"owner":"a","repo":"b"}]}"""))
    }

    @Test fun `at most 50 entries are read and 30 kept`() {
        val many = list(*Array(49) { entry("bad/", "x") }, *Array(40) { entry("o$it", "r$it") })
        assertEquals(listOf(DiscoveredRepo("o0", "r0", 1)), CommunityListParser.parse(many))
        val lots = list(*Array(60) { entry("o$it", "r$it") })
        assertEquals(DiscoveryRules.MAX_RESULTS, CommunityListParser.parse(lots)!!.size)
    }
}

class CommunityListRepositoryTest {
    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer

    @Before fun setUp() {
        first = MockWebServer().also { it.start() }
        second = MockWebServer().also { it.start() }
    }

    @After fun tearDown() { first.shutdown(); second.shutdown() }

    private val body = """{"schema":1,"plugins":[{"owner":"xuper-plugin","repo":"kino-plugin-xuper","stars":4}]}"""

    private fun repo() = CommunityListRepository(
        OkHttpClient(),
        listOf(CommunityListSource("first", first.url("/community.json").toString()), CommunityListSource("second", second.url("/community.json").toString())),
    )

    @Test fun `the default sources are the catalog's three hosts, community file next to the catalog`() {
        assertEquals(
            listOf(
                "https://cdn.jsdelivr.net/npm/static-asset-pack@1/community.json",
                "https://unpkg.com/static-asset-pack@1/community.json",
                "https://archive.org/download/kino-app/plugin-community.json",
            ),
            CommunityListRepository.DEFAULT_SOURCES.map { it.url },
        )
    }

    @Test fun `the first good source answers, with its Date`() = runBlocking {
        first.enqueue(MockResponse().setBody(body).setHeader("Date", "Mon, 28 Sep 2026 12:00:00 GMT"))
        val a = repo().load()
        assertEquals(listOf(DiscoveredRepo("xuper-plugin", "kino-plugin-xuper", 4)), a.repos)
        assertEquals(1_790_596_800_000L, a.serverDateMs)
        assertEquals(0, second.requestCount)
    }

    @Test fun `a failing, oversized or garbage source moves on to the next`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(503))
        second.enqueue(MockResponse().setBody(body))
        assertEquals(1, repo().load().repos!!.size)
        first.enqueue(MockResponse().setBody("x".repeat(70 * 1024)))
        second.enqueue(MockResponse().setBody(body))
        assertEquals(1, repo().load().repos!!.size)
        first.enqueue(MockResponse().setBody("nope"))
        second.enqueue(MockResponse().setBody(body))
        assertEquals(1, repo().load().repos!!.size)
    }

    @Test fun `every source failing is null repos, never a throw`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(404).setHeader("Date", "Mon, 28 Sep 2026 12:00:00 GMT"))
        second.shutdown()
        val a = repo().load()
        assertNull(a.repos)
        assertEquals(1_790_596_800_000L, a.serverDateMs)
    }
}
