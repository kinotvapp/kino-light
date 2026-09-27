package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.CompositeSource
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.magis.MagisRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * [LegacyXuperRefSource] over a REAL [PluginRegistry] (a temp [PluginStore]) and the real
 * [PluginContentSource]/[UnusablePluginSource], wired exactly like `AppGraph.contentSource`: only the
 * plugin's script is faked ([PluginCaller]).
 */
class LegacyXuperRefSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    private fun install(id: String, address: String, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", id).put("name", "Xuper").put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "episodes", "resolve"))).toString()
        val script = "export async function resolve(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L).record())
        store.commit(staging, id)
        registry.reload()
    }

    private class FakeCaller(val answers: Map<String, String>) : PluginCaller {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += Triple(pluginId, function, argJson)
            return answers[function] ?: throw PluginScriptException("boom")
        }
    }

    /** `AppGraph.contentSource`'s list, built the same way. */
    private fun contentSource(caller: PluginCaller): ContentSource {
        val pluginSources = registry.usable().map { PluginContentSource(it, caller, it.hosts, log = {}) } +
            UnusablePluginSource(registry)
        return CompositeSource {
            listOf(LegacyXuperRefSource(registry.plugins.value, CompositeSource(pluginSources))) + pluginSources
        }
    }

    private val movie = MagisRef("4EDDB74BB2714419887F62B903B01D90", "movie", 0).encode()
    private val chapter = MagisRef("7CE83F59C51B4F2E80EC9ECBE49EADC0", "teleplay", 2).encode()
    private val series = MagisRef("7CE83F59C51B4F2E80EC9ECBE49EADC0", "teleplay", 0).encode()

    private val stream = """{"url":"https://example.com/v.ts","durationMs":1000}"""

    @Test fun `recognizes exactly what MagisSource recognized`() {
        val source = LegacyXuperRefSource(emptyList(), CompositeSource(emptyList()))
        assertTrue(source.recognizes(movie))
        assertTrue(source.recognizes(chapter))
        assertTrue(source.recognizes("magis1:variety:3:ABC"))
        // The gateway's older ref: base64url(json).hmac.
        val gateway = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"s":"magis","p":{"content_id":"ABC","program_type":"teleplay","episode":4}}""".toByteArray(),
        ) + ".sig"
        assertTrue(source.recognizes(gateway))
        // Nobody else's.
        assertFalse(source.recognizes(PluginRef("xuper", "ABC", PluginRef.MOVIE, movie).encode()))
        assertFalse(source.recognizes("ditu1:VOD:1"))
        assertFalse(source.recognizes("magis1:movie:0:"))
        assertFalse(source.recognizes(""))
    }

    @Test fun `a legacy movie ref resolves through the Xuper plugin with its own magis1 ref`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        val caller = FakeCaller(mapOf("resolve" to stream))
        val play = contentSource(caller).resolve(movie)
        assertEquals("https://example.com/v.ts", play.url)
        val (pluginId, function, arg) = caller.calls.single()
        assertEquals("xuper", pluginId)
        assertEquals("resolve", function)
        // The plugin script receives the ORIGINAL legacy string as its own ref.
        assertEquals(movie, JSONArray("[$arg]").getString(0))
    }

    @Test fun `a legacy chapter ref resolves as an episode, not a series`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        val caller = FakeCaller(mapOf("resolve" to stream))
        contentSource(caller).resolve(chapter)
        assertEquals(chapter, JSONArray("[${caller.calls.single().third}]").getString(0))
        // A series ref with chapter 0 (= the first chapter) plays too: PluginContentSource refuses
        // only SERIES-kind refs, and this one is wrapped as an episode.
        contentSource(caller).resolve(series)
        assertEquals(series, JSONArray("[${caller.calls.last().third}]").getString(0))
    }

    @Test fun `a legacy gateway-form ref is forwarded untouched`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        val gateway = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"s":"magis","p":{"content_id":"ABC","program_type":"movie"}}""".toByteArray(),
        ) + ".sig"
        val caller = FakeCaller(mapOf("resolve" to stream))
        contentSource(caller).resolve(gateway)
        assertEquals(gateway, JSONArray("[${caller.calls.single().third}]").getString(0))
    }

    @Test fun `legacy episodes come back as legacy refs, without a per-chapter season`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        val c1 = MagisRef("7CE83F59C51B4F2E80EC9ECBE49EADC0", "teleplay", 1).encode()
        val episodes = JSONObject().put(
            "episodes",
            JSONArray()
                .put(JSONObject().put("number", 1).put("title", "Uno").put("ref", c1))
                .put(JSONObject().put("number", 2).put("title", "Dos").put("ref", chapter)),
        ).put("series", JSONObject().put("ids", JSONObject().put("imdb", "tt0944947").put("tmdb", 1399)).put("title", "Juego de tronos"))
        val caller = FakeCaller(mapOf("episodes" to episodes.toString()))

        val (eps, info) = contentSource(caller).episodesWithSeries(series)

        assertEquals(listOf(c1, chapter), eps.map { it.ref })
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals(listOf(null, null), eps.map { it.season })
        assertEquals(1399, info!!.tmdbId)
        assertEquals(series, JSONArray("[${caller.calls.single().third}]").getString(0))
        // Played later, a returned chapter comes back through the same legacy path.
        val play = FakeCaller(mapOf("resolve" to stream))
        contentSource(play).resolve(eps[1].ref)
        assertEquals(chapter, JSONArray("[${play.calls.single().third}]").getString(0))
    }

    @Test fun `a disabled Xuper plugin degrades exactly like UnusablePluginSource`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO) { copy(enabled = false) }
        val caller = FakeCaller(mapOf("resolve" to stream))
        val expected = runCatching {
            UnusablePluginSource(registry).resolve(PluginRef("xuper", "X", PluginRef.MOVIE, movie).encode())
        }.exceptionOrNull()!!
        assertEquals("Activa el plugin Xuper para ver esto", expected.message)

        val legacyResolve = runCatching { contentSource(caller).resolve(movie) }.exceptionOrNull()!!
        val legacyEpisodes = runCatching { contentSource(caller).episodesWithSeries(series) }.exceptionOrNull()!!
        assertEquals(expected.javaClass, legacyResolve.javaClass)
        assertEquals(expected.message, legacyResolve.message)
        assertEquals(expected.message, legacyEpisodes.message)
        assertTrue(caller.calls.isEmpty())
    }

    @Test fun `with no Xuper install the message is UnusablePluginSource's uninstalled one`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to stream))
        val expected = PluginAccess.Uninstalled("Xuper").blockedMessage()
        assertEquals(expected, runCatching { contentSource(caller).resolve(movie) }.exceptionOrNull()!!.message)
        assertEquals(expected, runCatching { contentSource(caller).episodesWithSeries(series) }.exceptionOrNull()!!.message)
        assertTrue(caller.calls.isEmpty())
    }

    /** A copy of the plugin under Xuper's manifest id, from any other repo, is NOT Xuper. */
    @Test fun `a plugin that only claims the xuper id never receives legacy refs`() = runTest {
        install("xuper", "someone-else/kino-plugin-xuper")
        val caller = FakeCaller(mapOf("resolve" to stream))
        val error = runCatching { contentSource(caller).resolve(movie) }.exceptionOrNull()!!
        assertEquals(PluginAccess.Uninstalled("Xuper").blockedMessage(), error.message)
        assertTrue(caller.calls.isEmpty())
    }

    @Test fun `routes to Xuper by its record, whatever its manifest id`() = runTest {
        install("xp-renamed", XuperPrivilege.SOURCE_REPO)
        val caller = FakeCaller(mapOf("resolve" to stream))
        contentSource(caller).resolve(movie)
        assertEquals("xp-renamed", caller.calls.single().first)
    }

    @Test fun `never searches`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        assertTrue(LegacyXuperRefSource(registry.plugins.value, CompositeSource(emptyList())).search(GatewaySearchQuery(q = "x")).toList().isEmpty())
    }

    @Test fun `registry isXuper follows the record, not the id`() {
        install("xuper", "someone-else/kino-plugin-xuper")
        install("xp", XuperPrivilege.SOURCE_REPO) { copy(enabled = false) }
        assertFalse(registry.isXuper("xuper"))
        assertTrue(registry.isXuper("xp"))
        assertFalse(registry.isXuper("missing"))
        assertNull(registry.find("missing"))
    }
}
