package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.PluginFetchStatusException
import com.arkiv.player.data.plugin.PluginFetcher
import com.arkiv.player.data.plugin.PluginFileTooBigException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PluginDiscoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    private inner class FakeGithub : GithubTransport {
        // Written on Dispatchers.IO (the load runs there), read by the test thread.
        @Volatile var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var answer: () -> GithubResponse = { ok(repo("a", "one", 5)) }
        override suspend fun search(): GithubResponse {
            calls++
            gate?.await()
            return answer()
        }
    }

    /** Serves manifests by `owner/repo`; a missing one is a 404. */
    private inner class FakeRaw : PluginFetcher {
        val manifests = HashMap<String, String>()
        val failures = HashMap<String, Exception>()
        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            val key = url.removePrefix("https://raw.githubusercontent.com/").substringBefore("/HEAD/")
            failures[key]?.let { throw it }
            return (manifests[key] ?: throw FileNotFoundException(url)).toByteArray()
        }
    }

    private fun manifest(id: String, api: Int = 1, extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("id", id).put("name", "Name $id").put("version", "1.0.0").put("apiVersion", api).put("entry", "plugin.js")
        .put("description", "Desc $id").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve"))).apply(extra).toString()

    private fun repo(owner: String, name: String, stars: Int) = JSONObject().put("full_name", "$owner/$name")
        .put("html_url", "https://github.com/$owner/$name").put("fork", false).put("stargazers_count", stars)
        .put("owner", JSONObject().put("login", owner))

    private fun ok(vararg items: JSONObject) = GithubResponse(200, JSONObject().put("items", JSONArray(items.toList())).toString(), emptyMap())

    private val github = FakeGithub()
    private val raw = FakeRaw().apply { manifests["a/one"] = manifest("one") }
    private val file get() = File(tmp.root, "plugin-discovery/discovery.json")
    private fun discovery() = PluginDiscovery(github, raw, file, clock = { now })

    @Test fun `a search keeps only repos whose manifest is valid, supported and discoverable`() = runBlocking {
        github.answer = { ok(repo("a", "one", 9), repo("a", "hidden", 8), repo("a", "future", 7), repo("a", "broken", 6), repo("a", "gone", 5), repo("b", "two", 4)) }
        raw.manifests["a/hidden"] = manifest("hidden") { put("discoverable", false) }
        raw.manifests["a/future"] = manifest("future", api = 99)
        raw.manifests["a/broken"] = "{}"
        raw.manifests["b/two"] = manifest("two")
        val r = discovery().load(force = false)
        assertEquals(DiscoveryOrigin.FRESH, r.origin)
        assertEquals(
            listOf(DiscoveredPlugin("a", "one", "one", "Name one", "Desc one", 9), DiscoveredPlugin("b", "two", "two", "Name two", "Desc two", 4)),
            r.plugins,
        )
        assertTrue(file.isFile)
    }

    @Test fun `within 12 hours the disk copy answers, across instances too`() = runBlocking {
        discovery().load(force = false)
        now += 11 * hour
        val again = discovery().load(force = false)
        assertEquals(DiscoveryOrigin.CACHE, again.origin)
        assertEquals(listOf("one"), again.plugins.map { it.id })
        assertEquals(1, github.calls)
        now += 2 * hour
        discovery().load(force = false)
        assertEquals(2, github.calls)
    }

    @Test fun `Actualizar searches again, but never twice within a minute`() = runBlocking {
        val d = discovery()
        d.load(force = false)
        now += 30_000
        assertEquals(DiscoveryOrigin.CACHE, d.load(force = true).origin)
        assertEquals(1, github.calls)
        now += 31_000
        assertEquals(DiscoveryOrigin.FRESH, d.load(force = true).origin)
        assertEquals(2, github.calls)
    }

    @Test fun `a 403 with X-RateLimit-Reset keeps GitHub away until the reset, across restarts`() = runBlocking {
        discovery().load(force = false)
        now += 13 * hour
        val reset = now / 1000 + 600
        github.answer = { GithubResponse(403, null, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset")) }
        val blocked = discovery().load(force = false)
        assertEquals(DiscoveryOrigin.CACHE, blocked.origin)
        assertEquals(listOf("one"), blocked.plugins.map { it.id })
        assertEquals(2, github.calls)
        now += 5 * 60_000
        discovery().load(force = true)
        assertEquals(2, github.calls)
        now += 6 * 60_000
        github.answer = { ok(repo("a", "one", 5)) }
        assertEquals(DiscoveryOrigin.FRESH, discovery().load(force = true).origin)
        assertEquals(3, github.calls)
    }

    @Test fun `a 429 with Retry-After is honoured, with nothing cached yet`() = runBlocking {
        github.answer = { GithubResponse(429, null, mapOf("retry-after" to "120")) }
        val d = discovery()
        assertEquals(DiscoveryResult.NONE, d.load(force = false))
        now += 119_000
        d.load(force = true)
        assertEquals(1, github.calls)
        now += 2_000
        d.load(force = true)
        assertEquals(2, github.calls)
    }

    @Test fun `backoff reads Retry-After, then X-RateLimit-Reset, else 15 minutes, clamped to 1 minute - 24 hours`() {
        val t = 1_000_000_000_000L
        assertEquals(t + 120_000, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "120")))
        assertEquals(t + 120_000, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "120", "x-ratelimit-reset" to "${t / 1000 + 900}")))
        assertEquals(t + 900_000, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${t / 1000 + 900}")))
        assertEquals(t + 15 * 60_000, PluginDiscovery.backoffUntil(t, emptyMap()))
        assertEquals(t + 15 * 60_000, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "soon")))
        assertEquals(t + 60_000, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "0")))
        assertEquals(t + 60_000, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${t / 1000 - 50}")))
        assertEquals(t + 24 * hour, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${t / 1000 + 10 * 86_400}")))
    }

    @Test fun `offline gives the last good list, or nothing, and never throws`() = runBlocking {
        github.answer = { throw IOException("offline") }
        assertEquals(DiscoveryResult.NONE, discovery().load(force = true))
        now += 2 * 60_000
        github.answer = { ok(repo("a", "one", 5)) }
        discovery().load(force = true)
        now += 13 * hour
        github.answer = { throw IOException("offline") }
        val r = discovery().load(force = false)
        assertEquals(DiscoveryOrigin.CACHE, r.origin)
        assertEquals(listOf("one"), r.plugins.map { it.id })
        github.answer = { GithubResponse(500, null, emptyMap()) }
        now += 2 * 60_000
        assertEquals(DiscoveryOrigin.CACHE, discovery().load(force = true).origin)
        github.answer = { GithubResponse(200, "garbage", emptyMap()) }
        now += 2 * 60_000
        assertEquals(DiscoveryOrigin.CACHE, discovery().load(force = true).origin)
    }

    @Test fun `a manifest GitHub could not serve right now keeps its previous entry, a 404 drops it`() = runBlocking {
        github.answer = { ok(repo("a", "one", 5), repo("b", "two", 3)) }
        raw.manifests["b/two"] = manifest("two")
        discovery().load(force = false)
        now += 13 * hour
        github.answer = { ok(repo("a", "one", 6), repo("b", "two", 4)) }
        raw.failures["a/one"] = IOException("reset")
        raw.manifests.remove("b/two")
        val r = discovery().load(force = false)
        assertEquals(listOf(DiscoveredPlugin("a", "one", "one", "Name one", "Desc one", 6)), r.plugins)
    }

    @Test fun `concurrent loads share one search`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        github.gate = gate
        val d = discovery()
        val first = async { d.load(force = false) }
        val second = async { d.load(force = false) }
        withTimeout(5_000) { while (github.calls == 0) yield() }
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, github.calls)
    }

    @Test fun `a corrupt cache is ignored, a cache dated in the future is stale, cached never searches`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        assertEquals(DiscoveryResult.NONE, discovery().cached())
        assertEquals(0, github.calls)
        assertEquals(DiscoveryOrigin.FRESH, discovery().load(force = false).origin)
        assertEquals(listOf("one"), discovery().cached().plugins.map { it.id })
        now -= 2 * hour
        discovery().load(force = false)
        assertEquals(2, github.calls)
    }

    @Test fun `a persisted backoff further away than the maximum is ignored (clock skew, restored backup)`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText(JSONObject().put("schema", 1).put("fetchedAt", 0).put("blockedUntil", now + 10 * 24 * hour).put("plugins", JSONArray()).toString())
        assertEquals(DiscoveryOrigin.FRESH, discovery().load(force = false).origin)
        assertEquals(1, github.calls)
    }

    @Test fun `a 403 received while the clock ran ahead does not block beyond the maximum once it is corrected`() = runBlocking {
        discovery().load(force = false)
        val trueNow = now
        now += 30 * 24 * hour
        github.answer = { GithubResponse(403, null, mapOf("retry-after" to "3600")) }
        discovery().load(force = false)
        assertEquals(2, github.calls)
        now = trueNow + 2 * 60_000
        github.answer = { ok(repo("a", "one", 5)) }
        assertEquals(DiscoveryOrigin.FRESH, discovery().load(force = true).origin)
        assertEquals(3, github.calls)
    }

    @Test fun `absurd backoff headers are clamped, never overflow to the minimum`() {
        val t = 1_000_000_000_000L
        assertEquals(t + 24 * hour, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "${Long.MAX_VALUE}")))
        assertEquals(t + 24 * hour, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "${Long.MAX_VALUE / 999}")))
        assertEquals(t + 24 * hour, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${Long.MAX_VALUE}")))
        assertEquals(t + 24 * hour, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${Long.MAX_VALUE / 999}")))
        assertEquals(t + 60_000, PluginDiscovery.backoffUntil(t, mapOf("retry-after" to "-5")))
        assertEquals(t + 60_000, PluginDiscovery.backoffUntil(t, mapOf("x-ratelimit-reset" to "${Long.MIN_VALUE}")))
    }

    @Test fun `a manifest too big or gone for good (410, 451) is a verdict, a 5xx or offline is transient`() = runBlocking {
        github.answer = { ok(repo("a", "one", 5), repo("b", "big", 4), repo("c", "gone", 3), repo("d", "legal", 2), repo("e", "down", 1)) }
        listOf("b/big", "c/gone", "d/legal", "e/down").forEach { raw.manifests[it] = manifest(it.substringAfter('/')) }
        assertEquals(5, discovery().load(force = false).plugins.size)
        now += 13 * hour
        raw.failures["a/one"] = IOException("offline")
        raw.failures["b/big"] = PluginFileTooBigException()
        raw.failures["c/gone"] = PluginFetchStatusException(410)
        raw.failures["d/legal"] = PluginFetchStatusException(451)
        raw.failures["e/down"] = PluginFetchStatusException(503)
        assertEquals(listOf("one", "down"), discovery().load(force = false).plugins.map { it.id })
    }
}
