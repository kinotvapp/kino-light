package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleAvailabilityTest {
    private val missing = PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS)
    private val torrents = PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.ONLY_TORRENTS)

    // --- classification ---

    @Test fun `not_found and torrent-only mean the source does not have it`() {
        assertEquals(Availability.Missing, TitleAvailability.classify(missing))
        assertEquals(Availability.Missing, TitleAvailability.classify(torrents))
    }

    @Test fun `a timeout, a site down or a scraper error leave it unknown`() {
        assertTrue(TitleAvailability.classify(PluginTimeoutException("resolve", 20_000)) is Availability.NoAnswer)
        assertTrue(TitleAvailability.classify(PluginErrorException(PluginErrors.UNAVAILABLE, "error del scraper: x")) is Availability.NoAnswer)
        assertTrue(TitleAvailability.classify(PluginScriptException("boom")) is Availability.NoAnswer)
    }

    // --- the parallel-free, budgeted check with fake probes ---

    @Test fun `only confirmed items are kept, in order`() = runTest {
        val r = TitleAvailability.check(listOf("a", "b", "c"), 3, 10_000, { item, _ ->
            if (item == "b") Availability.Missing else Availability.Available("ok-$item")
        })
        assertEquals(listOf("a", "c"), r.available)
        assertEquals(1, r.missing)
        assertNull(r.noAnswer)
    }

    @Test fun `items past the cap are never listed`() = runTest {
        val probed = mutableListOf<String>()
        val r = TitleAvailability.check(listOf("a", "b", "c", "d"), 2, 10_000, { item, _ -> probed += item; Availability.Available("") })
        assertEquals(listOf("a", "b"), probed)
        assertEquals(listOf("a", "b"), r.available)
        assertEquals(2, r.missing)
    }

    @Test fun `each check gets what is left of the shared budget and the rest are dropped when it runs out`() = runTest {
        var clock = 0L
        val limits = mutableListOf<Long>()
        val r = TitleAvailability.check(listOf("a", "b", "c"), 3, 10_000, { item, left ->
            limits += left
            clock += 6_000
            Availability.Available(item)
        }, now = { clock })
        assertEquals(listOf(10_000L, 4_000L), limits)
        assertEquals(listOf("a", "b"), r.available)
        assertEquals(1, r.missing)
    }

    @Test fun `a failure is reported and counts as unknown, not missing`() = runTest {
        val boom = PluginTimeoutException("resolve", 5_000)
        val r = TitleAvailability.check(listOf("a", "b"), 3, 10_000, { item, _ ->
            if (item == "a") Availability.NoAnswer(boom) else Availability.Missing
        })
        assertTrue(r.available.isEmpty())
        assertEquals(boom, r.noAnswer)
        assertEquals(1, r.missing)
    }

    @Test fun `probe turns a call's result and failures into availability`() = runTest {
        assertEquals(Availability.Available("x"), TitleAvailability.probe { "x" })
        assertEquals(Availability.Missing, TitleAvailability.probe { throw missing })
    }

    // --- the stream kept for the tap ---

    @Test fun `a confirmed stream is taken once and only while fresh`() {
        ProbedStreams.clear()
        ProbedStreams.put("p", "r", "out", now = 1_000)
        assertEquals("out", ProbedStreams.take("p", "r", now = 2_000))
        assertNull(ProbedStreams.take("p", "r", now = 2_000))
        ProbedStreams.put("p", "r", "old", now = 0)
        assertNull(ProbedStreams.take("p", "r", now = ProbedStreams.TTL_MS + 1))
    }

    @Test fun `a series is checked through its first episode`() {
        val ref = NuvioPluginConverter.availabilityRef("series", """{"tmdbId":5,"type":"tv"}""")
        assertTrue(ref.contains("\"season\":1") && ref.contains("\"episode\":1") && ref.contains("\"tmdbId\":5"))
        assertEquals("""{"tmdbId":5,"type":"movie"}""", NuvioPluginConverter.availabilityRef("movie", """{"tmdbId":5,"type":"movie"}"""))
    }

    // --- wired into a converted scraper's search ---

    private fun nuvio(answers: (String, String) -> String): Pair<PluginContentSource, MutableList<String>> {
        val resolved = mutableListOf<String>()
        val manifest = PluginManifest("pelis", "Pelis", "1.0.0", 4, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null)
        val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L).copy(nuvioRepo = "o/r", nuvioScraperId = "pelis")
        val caller = object : PluginCaller {
            override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
                if (function == "resolve") resolved += argJson
                return answers(function, argJson)
            }
        }
        return PluginContentSource(InstalledPlugin(manifest, record, null), caller, log = {}) to resolved
    }

    private val searchOut = """[{"id":"1-movie-0-0","ref":"{\"tmdbId\":1,\"type\":\"movie\"}","title":"Uno","kind":"movie"}]"""

    @Test fun `a converted scraper that does not have the title lists nothing`() = runTest {
        val (source, _) = nuvio { fn, _ -> if (fn == "search") searchOut else throw missing }
        val events = source.search(GatewaySearchQuery(q = "Uno", tmdbId = 1, type = "movie")).toList()
        assertTrue(events.filterIsInstance<SearchEvent.ResultEvent>().isEmpty())
        assertEquals(0, events.filterIsInstance<SearchEvent.SourceDone>().single().count)
    }

    @Test fun `a converted scraper that has it lists it and the tap reuses the checked stream`() = runTest {
        ProbedStreams.clear()
        val (source, resolved) = nuvio { fn, _ -> if (fn == "search") searchOut else """{"url":"https://example.com/v.mp4"}""" }
        val events = source.search(GatewaySearchQuery(q = "Uno", tmdbId = 1, type = "movie")).toList()
        val item = events.filterIsInstance<SearchEvent.ResultEvent>().single().item
        assertEquals(1, resolved.size)
        source.resolve(item.ref)
        assertEquals("the tap does not resolve twice", 1, resolved.size)
    }

    @Test fun `a converted scraper that cannot confirm is reported as not answering`() = runTest {
        val (source, _) = nuvio { fn, _ -> if (fn == "search") searchOut else throw PluginTimeoutException("resolve", 20_000) }
        val events = source.search(GatewaySearchQuery(q = "Uno", tmdbId = 1, type = "movie")).toList()
        assertTrue(events.filterIsInstance<SearchEvent.ResultEvent>().isEmpty())
        assertTrue(events.single { it is SearchEvent.SourceError } is SearchEvent.SourceError)
    }

    @Test fun `a plain plugin's search is listed as is, with no extra resolve`() = runTest {
        val manifest = PluginManifest("ia", "IA", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null)
        val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)
        val calls = mutableListOf<String>()
        val caller = object : PluginCaller {
            override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String { calls += function; return searchOut }
        }
        val events = PluginContentSource(InstalledPlugin(manifest, record, null), caller, log = {}).search(GatewaySearchQuery(q = "Uno")).toList()
        assertEquals(1, events.filterIsInstance<SearchEvent.ResultEvent>().size)
        assertEquals(listOf("search"), calls)
    }

    @Test fun `the tap on a source that lost the title says so`() {
        val msg = (PluginCalls.failureOf(missing, "pelis", "Pelis", "resolve") as GatewayException).message
        assertEquals("Esta fuente ya no tiene este título (Pelis)", msg)
    }
}
