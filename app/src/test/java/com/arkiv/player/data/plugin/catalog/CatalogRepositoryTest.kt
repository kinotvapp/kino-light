package com.arkiv.player.data.plugin.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private fun defaultSources() = listOf(
        CatalogSource("first", first.url("/c.json").toString()),
        CatalogSource("second", second.url("/c.json").toString()),
    )

    private fun repo(
        client: OkHttpClient = OkHttpClient(),
        sources: List<CatalogSource> = defaultSources(),
        seed: () -> String = { seedJson },
    ) = CatalogRepository(
        client = client,
        sources = sources,
        cacheFile = cache,
        seed = seed,
        capabilities = emptySet(),
        clock = { now },
        ttlMs = 6 * 3600_000L,
    )

    private val emptyJson = """{"schema":1,"plugins":[]}"""

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

    // ---- Fix round 1 ----

    @Test fun `a failed cache write does not lose a good download`() = runBlocking {
        // A directory where the cache file should be makes every write to it fail with an IOException.
        cache.mkdirs()
        first.enqueue(MockResponse().setBody(json("a")))
        val r = repo().load(false)
        assertEquals(listOf("a"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
        assertEquals("FileNotFoundException", r.failures["cache"])
    }

    @Test fun `an empty catalog from a source is skipped for the next source`() = runBlocking {
        first.enqueue(MockResponse().setBody(emptyJson))
        second.enqueue(MockResponse().setBody(json("b")))
        val r = repo().load(false)
        assertEquals(listOf("b"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
        assertEquals("empty catalog", r.failures["first"])
    }

    @Test fun `a catalog whose entries are all invalid counts as empty`() = runBlocking {
        first.enqueue(MockResponse().setBody("""{"schema":1,"plugins":[{"id":"BAD ID","repo":"nope","name":""}]}"""))
        second.enqueue(MockResponse().setBody(json("b")))
        val r = repo().load(false)
        assertEquals(listOf("b"), ids(r))
        assertEquals("empty catalog", r.failures["first"])
    }

    @Test fun `an empty catalog never replaces a good cache`() = runBlocking {
        cache.writeText(json("good")); cache.setLastModified(now - 7 * 3600_000L)
        first.enqueue(MockResponse().setBody(emptyJson)); second.enqueue(MockResponse().setBody(emptyJson))
        val r = repo().load(false)
        assertEquals(listOf("good"), ids(r)); assertEquals(CatalogOrigin.CACHE, r.origin)
        assertEquals(json("good"), cache.readText())
    }

    @Test fun `an empty cache file falls through to the seed`() = runBlocking {
        cache.writeText(emptyJson); cache.setLastModified(now - 7 * 3600_000L)
        first.enqueue(MockResponse().setResponseCode(500)); second.enqueue(MockResponse().setResponseCode(500))
        val r = repo().load(false)
        assertEquals(listOf("seed"), ids(r)); assertEquals(CatalogOrigin.SEED, r.origin)
    }

    @Test fun `an empty cache file inside the TTL does not stop a download`() = runBlocking {
        cache.writeText(emptyJson); cache.setLastModified(now - 1000)
        first.enqueue(MockResponse().setBody(json("new")))
        val r = repo().load(false)
        assertEquals(listOf("new"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
    }

    @Test fun `an unreadable seed does not throw out of load`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(500)); second.enqueue(MockResponse().setResponseCode(500))
        val r = repo(seed = { throw IOException("asset missing") }).load(false)
        assertTrue(r.catalog.entries.isEmpty()); assertEquals(CatalogOrigin.SEED, r.origin)
        assertEquals("unreadable", r.failures["seed"])
        assertEquals("HTTP 500", r.failures["first"])
    }

    @Test fun `a cache dated in the future is stale`() = runBlocking {
        cache.writeText(json("cached")); cache.setLastModified(now + 24 * 3600_000L)
        first.enqueue(MockResponse().setBody(json("new")))
        val r = repo().load(false)
        assertEquals(listOf("new"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
        assertEquals(1, first.requestCount)
    }

    @Test fun `redirects are followed even when the injected client refuses them`() = runBlocking {
        first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/real.json").toString()))
        second.enqueue(MockResponse().setBody(json("redirected")))
        val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val r = repo(client = noRedirects, sources = listOf(CatalogSource("first", first.url("/c.json").toString()))).load(false)
        assertEquals(listOf("redirected"), ids(r)); assertEquals(CatalogOrigin.FRESH, r.origin)
    }

    // ---- Device pass: the list paints from disk, before any download ----

    @Test fun `cachedOrSeed returns a fresh cache without touching the network`() {
        cache.writeText(json("cached")); cache.setLastModified(now - 1000)
        val r = repo().cachedOrSeed()
        assertEquals(listOf("cached"), ids(r)); assertEquals(CatalogOrigin.CACHE, r.origin)
        assertEquals(0, first.requestCount); assertEquals(0, second.requestCount)
    }

    @Test fun `cachedOrSeed returns a stale cache too, its age is ignored`() {
        cache.writeText(json("stale")); cache.setLastModified(now - 7 * 3600_000L)
        val r = repo().cachedOrSeed()
        assertEquals(listOf("stale"), ids(r)); assertEquals(CatalogOrigin.CACHE, r.origin)
        assertEquals(0, first.requestCount)
    }

    @Test fun `cachedOrSeed returns a cache dated in the future too`() {
        cache.writeText(json("cached")); cache.setLastModified(now + 24 * 3600_000L)
        assertEquals(listOf("cached"), ids(repo().cachedOrSeed()))
    }

    @Test fun `cachedOrSeed falls back to the seed when there is no cache`() {
        val r = repo().cachedOrSeed()
        assertEquals(listOf("seed"), ids(r)); assertEquals(CatalogOrigin.SEED, r.origin)
        assertTrue(r.failures.isEmpty())
        assertEquals(0, first.requestCount)
    }

    @Test fun `cachedOrSeed ignores an empty cache`() {
        cache.writeText(emptyJson); cache.setLastModified(now - 1000)
        val r = repo().cachedOrSeed()
        assertEquals(listOf("seed"), ids(r)); assertEquals(CatalogOrigin.SEED, r.origin)
    }

    @Test fun `cachedOrSeed ignores a corrupt cache`() {
        cache.writeText("{not json"); cache.setLastModified(now - 1000)
        val r = repo().cachedOrSeed()
        assertEquals(listOf("seed"), ids(r)); assertEquals(CatalogOrigin.SEED, r.origin)
    }

    @Test fun `cachedOrSeed never throws when the seed cannot be read`() {
        val r = repo(seed = { throw IOException("asset missing") }).cachedOrSeed()
        assertTrue(r.catalog.entries.isEmpty()); assertEquals(CatalogOrigin.SEED, r.origin)
        assertEquals("unreadable", r.failures["seed"])
    }

    @Test fun `cachedOrSeed with a seed that is not a catalog is an empty seed result`() {
        val r = repo(seed = { "<html>" }).cachedOrSeed()
        assertTrue(r.catalog.entries.isEmpty()); assertEquals(CatalogOrigin.SEED, r.origin)
    }

    @Test fun `cachedOrSeed leaves the cache file as it found it`() {
        cache.writeText(json("cached")); cache.setLastModified(now - 7 * 3600_000L)
        repo().cachedOrSeed()
        assertEquals(json("cached"), cache.readText())
        assertEquals(now - 7 * 3600_000L, cache.lastModified())
    }

    @Test fun `cancelling during a request stops the chain before the next source`() = runBlocking {
        // The first source answers slowly; the job is cancelled while that call is in flight. The blocking
        // call cannot be interrupted, so the loop must notice the cancellation before it starts source two.
        first.enqueue(MockResponse().setResponseCode(503).setHeadersDelay(1, TimeUnit.SECONDS))
        second.enqueue(MockResponse().setBody(json("b")))
        val job = launch(Dispatchers.Default) { repo().load(false) }
        first.takeRequest(5, TimeUnit.SECONDS)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, second.requestCount)
    }
}
