package com.arkiv.player.data.plugin.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var cache: File
    // A round number well past 7 h so `now - 7h` is a valid (positive) file time.
    private var now = 10_000_000_000L

    @Before fun setUp() {
        first = MockWebServer().also { it.start() }
        second = MockWebServer().also { it.start() }
        cache = File(tmp.root, "catalog.json")
    }
    @After fun tearDown() { first.shutdown(); second.shutdown() }

    private fun json(vararg ids: String) =
        """{"schema":1,"plugins":[${ids.joinToString(",") { """{"id":"$it","repo":"o/$it","name":"$it"}""" }}]}"""

    private val seedJson = json("seed")

    private fun repo() = CatalogRepository(
        client = OkHttpClient(),
        sources = listOf(CatalogSource("first", first.url("/c.json").toString()), CatalogSource("second", second.url("/c.json").toString())),
        cacheFile = cache,
        seed = { seedJson },
        capabilities = emptySet(),
        clock = { now },
        ttlMs = 6 * 3600_000L,
    )

    private fun ids(r: CatalogResult) = r.catalog.entries.map { it.id }

    @Test fun `a fresh download is used and cached`() = runBlocking {
        first.enqueue(MockResponse().setBody(json("a", "b")))
        val r = repo().load(force = false)
        assertEquals(listOf("a", "b"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
        assertEquals(json("a", "b"), cache.readText())
    }

    @Test fun `the next source is tried when one fails`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(503))
        second.enqueue(MockResponse().setBody(json("b")))
        val r = repo().load(false)
        assertEquals(listOf("b"), ids(r))
        assertEquals("HTTP 503", r.failures["first"])
    }

    @Test fun `a 200 with a page instead of a catalog is skipped`() = runBlocking {
        first.enqueue(MockResponse().setBody("<html>portal</html>"))
        second.enqueue(MockResponse().setBody(json("b")))
        assertEquals(listOf("b"), ids(repo().load(false)))
    }

    @Test fun `a body over 64 KB is skipped`() = runBlocking {
        first.enqueue(MockResponse().setBody("x".repeat(PluginCatalogParser.MAX_BYTES + 10)))
        second.enqueue(MockResponse().setBody(json("b")))
        assertEquals(listOf("b"), ids(repo().load(false)))
    }

    @Test fun `a cache inside the TTL is used without touching the network`() = runBlocking {
        cache.writeText(json("cached")); cache.setLastModified(now - 1000)
        val r = repo().load(false)
        assertEquals(listOf("cached"), ids(r)); assertEquals(CatalogOrigin.CACHE, r.origin)
        assertEquals(0, first.requestCount)
    }

    @Test fun `force ignores the TTL`() = runBlocking {
        cache.writeText(json("cached")); cache.setLastModified(now - 1000)
        first.enqueue(MockResponse().setBody(json("new")))
        assertEquals(listOf("new"), ids(repo().load(force = true)))
    }

    @Test fun `every source failing falls back to a stale cache`() = runBlocking {
        cache.writeText(json("stale")); cache.setLastModified(now - 7 * 3600_000L)
        first.enqueue(MockResponse().setResponseCode(500)); second.enqueue(MockResponse().setResponseCode(500))
        val r = repo().load(false)
        assertEquals(listOf("stale"), ids(r)); assertEquals(CatalogOrigin.CACHE, r.origin)
    }

    @Test fun `no source and no cache gives the seed, never an empty list`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(500)); second.enqueue(MockResponse().setResponseCode(500))
        val r = repo().load(false)
        assertEquals(listOf("seed"), ids(r)); assertEquals(CatalogOrigin.SEED, r.origin)
        assertTrue(r.failures.size == 2)
    }

    @Test fun `a corrupt cache falls through to the seed`() = runBlocking {
        cache.writeText("{not json"); cache.setLastModified(now - 7 * 3600_000L)
        first.enqueue(MockResponse().setResponseCode(500)); second.enqueue(MockResponse().setResponseCode(500))
        assertEquals(listOf("seed"), ids(repo().load(false)))
    }
}
