package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContentSourceTest {
    private fun plugin(caps: Set<String> = setOf("search", "episodes", "resolve"), hosts: List<String> = listOf("example.com")) =
        InstalledPlugin(
            PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", hosts, caps, "#E0A030", null),
            InstalledRecord("o/r", "1.0.0", "x", hosts, 0L),
            null,
        )

    private class FakeCaller(val answers: Map<String, String>) : PluginCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += function to argJson
            return answers[function] ?: throw PluginScriptException("boom")
        }
    }

    private fun source(caller: PluginCaller, p: InstalledPlugin = plugin()) = PluginContentSource(p, caller, log = {})

    @Test fun `search wraps items as plugin results`() = runTest {
        val caller = FakeCaller(mapOf("search" to """[{"id":"m1","ref":"R1","title":"Uno","kind":"movie","year":"1927","poster":"https://example.com/p.jpg"},{"id":"s1","ref":"S1","title":"Serie","kind":"series"}]"""))
        val events = source(caller).search(GatewaySearchQuery(q = "uno", type = "tv", year = 1927)).toList()
        assertEquals(SearchEvent.SourceStart("plugin:demo", label = "Demo"), events.first())
        val results = events.filterIsInstance<SearchEvent.ResultEvent>().map { it.item }
        assertEquals(listOf("movie", "series"), results.map { it.kind })
        with(results[0]) {
            assertEquals("plugin:demo", source)
            assertEquals("Demo", extra["pluginName"])
            assertEquals("#E0A030", extra["color"])
            assertEquals("m1", extra["pluginItemId"])
            assertEquals("https://example.com/p.jpg", extra["poster"])
            assertEquals(PluginRef("demo", "m1", PluginRef.MOVIE, "R1"), PluginRef.decode(ref))
        }
        assertEquals(PluginRef.SERIES, PluginRef.decode(results[1].ref)!!.kind)
        assertTrue(events.last() is SearchEvent.SourceDone)
        val arg = JSONObject(caller.calls.single().second)
        assertEquals("series", arg.getString("type"))
        assertEquals(1927, arg.getInt("year"))
    }

    @Test fun `a failing search is a SourceError, not an exception`() = runTest {
        val events = source(FakeCaller(emptyMap())).search(GatewaySearchQuery(q = "x")).toList()
        assertEquals("plugin:demo", events.filterIsInstance<SearchEvent.SourceError>().single().source)
    }

    @Test fun `a search timeout reads in Spanish, never with the capability's English name`() = runTest {
        val caller = PluginCaller { _, function, _, timeoutMs -> throw PluginTimeoutException(function, timeoutMs) }
        val err = source(caller).search(GatewaySearchQuery(q = "x")).toList().filterIsInstance<SearchEvent.SourceError>().single()
        // Shown as "<plugin> no respondió: <error>" by DownSources.
        assertEquals("tardó más de 15 s", err.error)
        assertFalse(err.error, "search" in err.error)
        // The cause stays the timeout: that's what counts toward "no responde".
        assertTrue(err.cause is PluginTimeoutException)
    }

    @Test fun `no search capability means no search at all`() = runTest {
        val events = source(FakeCaller(emptyMap()), plugin(caps = setOf("home", "resolve"))).search(GatewaySearchQuery(q = "x")).toList()
        assertEquals(emptyList<SearchEvent>(), events)
    }

    @Test fun `recognizes only its own refs`() {
        val src = source(FakeCaller(emptyMap()))
        assertTrue(src.recognizes(PluginRef("demo", "a", PluginRef.MOVIE, "r").encode()))
        assertFalse(src.recognizes(PluginRef("other", "a", PluginRef.MOVIE, "r").encode()))
        assertFalse(src.recognizes("ditu1:VOD:1"))
    }

    @Test fun `resolve passes the plugin's own ref and maps the stream`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://example.com/v.m3u8","mime":"application/x-mpegURL","headers":{"Referer":"https://example.com/"},"subtitles":[{"lang":"es","url":"https://example.com/s.vtt","format":"vtt"}],"durationMs":60000}"""))
        val play = source(caller).resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode())
        assertEquals("\"R1\"", caller.calls.single().second)
        assertEquals("https://example.com/v.m3u8", play.url)
        assertEquals("application/x-mpegURL", play.mime)
        assertEquals(mapOf("Referer" to "https://example.com/"), play.headers)
        assertEquals(listOf(GatewaySubtitle("es", "https://example.com/s.vtt", "vtt")), play.subtitles)
        assertEquals(60_000L, play.durationMs)
        assertEquals("", play.drmLicenseUrl)
    }

    @Test fun `resolve refuses an undeclared host with a message naming the plugin`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://evil.example/v.mp4"}"""))
        val e = runCatching { source(caller).resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()) }.exceptionOrNull()
        assertTrue(e is GatewayException)
        assertTrue(e!!.message!!.startsWith("Demo:"))
    }

    @Test fun `episodes wrap each chapter ref with its season and number`() = runTest {
        val caller = FakeCaller(mapOf("episodes" to """{"series":{"title":"Serie","tmdbId":55},"episodes":[{"season":1,"number":1,"ref":"E1","title":"Piloto"},{"season":2,"number":1,"ref":"E2"}]}"""))
        val (eps, series) = source(caller).episodesWithSeries(PluginRef("demo", "s1", PluginRef.SERIES, "S1").encode())
        assertEquals("\"S1\"", caller.calls.single().second)
        assertEquals(listOf(1 to 1, 2 to 1), eps.map { it.season to it.number })
        assertEquals("Capítulo 1", eps[1].title)
        assertEquals(PluginRef("demo", "s1", PluginRef.EPISODE, "E2", 2, 1), PluginRef.decode(eps[1].ref))
        assertEquals(55, series!!.tmdbId)
        assertEquals("Serie", series.title)
    }

    private class FakeAccess(val access: PluginAccess) : PluginPlayback {
        override fun accessFor(pluginId: String?) = access
        override fun nameOf(pluginId: String?) = access.name
    }

    /** A saved title of a disabled/uninstalled plugin must say why, not "no source can open this". */
    @Test fun `an unusable plugin's ref fails with the registry's message`() = runTest {
        val ref = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()
        val disabled = com.arkiv.player.data.gateway.CompositeSource(listOf(UnusablePluginSource(FakeAccess(PluginAccess.Disabled("Demo")))))
        assertEquals("Activa el plugin Demo para ver esto", runCatching { disabled.resolve(ref) }.exceptionOrNull()!!.message)
        val gone = com.arkiv.player.data.gateway.CompositeSource(listOf(UnusablePluginSource(FakeAccess(PluginAccess.Uninstalled("Demo")))))
        assertEquals("Esto venía del plugin Demo, que ya no está instalado", runCatching { gone.episodesWithSeries(ref) }.exceptionOrNull()!!.message)
    }

    @Test fun `a usable plugin's source wins over the unusable fallback`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://example.com/v.mp4"}"""))
        val composite = com.arkiv.player.data.gateway.CompositeSource(
            listOf(source(caller), UnusablePluginSource(FakeAccess(PluginAccess.Disabled("Demo")))),
        )
        assertEquals("https://example.com/v.mp4", composite.resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()).url)
        assertFalse(UnusablePluginSource(FakeAccess(PluginAccess.Disabled("Demo"))).recognizes("ditu1:VOD:1"))
    }

    /**
     * The plugin's own call limit must fire before `CompositeSource`'s backstop: only a
     * [PluginTimeoutException] counts toward "no responde" in `PluginRuntimePool`. The caller here
     * spends a little time "opening the runtime" and then times out at its own limit, like the pool.
     */
    @Test fun `the plugin's own search timeout wins over the composite backstop`() = runTest {
        val caller = PluginCaller { _, function, _, timeoutMs ->
            kotlinx.coroutines.delay(500)
            kotlinx.coroutines.delay(timeoutMs)
            throw PluginTimeoutException(function, timeoutMs)
        }
        val src = source(caller)
        assertTrue(src.searchTimeoutMs!! > PluginContentSource.SEARCH_TIMEOUT_MS)
        val events = com.arkiv.player.data.gateway.CompositeSource(listOf(src)).search(GatewaySearchQuery(q = "x")).toList()
        val err = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("plugin:demo", err.source)
        assertTrue(err.cause is PluginTimeoutException)
    }

    /**
     * The contended case: the search waits on `PluginRuntimePool`'s per-plugin mutex behind one
     * slow same-plugin call (home/episodes/resolve, up to 20 s) before its own 15 s clock starts.
     * Its [PluginTimeoutException] must still arrive before the composite backstop.
     */
    @Test fun `a search queued behind the slowest other call still times out on its own clock`() = runTest {
        val longestOther = maxOf(
            PluginContentSource.HOME_TIMEOUT_MS,
            PluginContentSource.EPISODES_TIMEOUT_MS,
            PluginContentSource.RESOLVE_TIMEOUT_MS,
        )
        val caller = PluginCaller { _, function, _, timeoutMs ->
            kotlinx.coroutines.delay(longestOther) // queued on the pool's mutex
            kotlinx.coroutines.delay(500) // runtime load
            kotlinx.coroutines.delay(timeoutMs)
            throw PluginTimeoutException(function, timeoutMs)
        }
        val events = com.arkiv.player.data.gateway.CompositeSource(listOf(source(caller)))
            .search(GatewaySearchQuery(q = "x")).toList()
        val err = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("plugin:demo", err.source)
        assertTrue(err.cause is PluginTimeoutException)
    }
}
