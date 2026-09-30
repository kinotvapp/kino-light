package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContentSourceTest {
    private fun plugin(caps: Set<String> = setOf("search", "episodes", "resolve"), hosts: List<String> = listOf("example.com"), apiVersion: Int = 1) =
        InstalledPlugin(
            PluginManifest("demo", "Demo", "1.0.0", apiVersion, "plugin.js", "", "", "", hosts, caps, "#E0A030", null),
            InstalledRecord("o/r", "1.0.0", "x", hosts, 0L),
            null,
        )

    // --- live channels (apiVersion 2) ---

    private val liveSearch = """[{"id":"c1","ref":"ch-1","title":"Canal Uno","kind":"live","poster":"https://example.com/c1.png","runtimeMinutes":120},{"id":"m1","ref":"R1","title":"Uno","kind":"movie"}]"""

    @Test fun `a v2 plugin's search may carry live channels, wrapped as live refs`() = runTest {
        val results = source(FakeCaller(mapOf("search" to liveSearch)), plugin(apiVersion = 2)).search(GatewaySearchQuery(q = "canal")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item }
        assertEquals(listOf("live", "movie"), results.map { it.kind })
        with(results[0]) {
            assertEquals(PluginRef("demo", "c1", PluginRef.LIVE, "ch-1"), PluginRef.decode(ref))
            assertEquals("c1", extra["pluginItemId"])
            assertEquals("https://example.com/c1.png", extra["poster"])
            // No duration travels with a channel, whatever the plugin said.
            assertEquals(null, extra["runtimeMinutes"])
        }
    }

    @Test fun `a v1 plugin's live channel is dropped from search, browse and paging`() = runTest {
        val caller = FakeCaller(mapOf("search" to liveSearch, "browse" to liveSearch))
        val v1 = source(caller, plugin(caps = setOf("search", "browse", "resolve")))
        assertEquals(listOf("movie"), v1.search(GatewaySearchQuery(q = "canal")).toList().filterIsInstance<SearchEvent.ResultEvent>().map { it.item.kind })
        assertEquals(listOf("movie"), v1.browse("row", null).items.map { it.kind })
        assertEquals(listOf("movie"), v1.searchPage("""{"q":"canal"}""", "2").items.map { it.kind })
        val v2 = source(caller, plugin(caps = setOf("search", "browse", "resolve"), apiVersion = 2))
        assertEquals(listOf("live", "movie"), v2.browse("row", null).items.map { it.kind })
        assertEquals(listOf("live", "movie"), v2.searchPage("""{"q":"canal"}""", "2").items.map { it.kind })
    }

    @Test fun `resolving a live ref plays its stream with no duration, and it never lists episodes`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://example.com/live.m3u8","mime":"application/x-mpegURL","durationMs":7200000}"""))
        val live = PluginRef("demo", "c1", PluginRef.LIVE, "ch-1").encode()
        val play = source(caller, plugin(apiVersion = 2)).resolve(live)
        assertEquals("https://example.com/live.m3u8", play.url)
        assertEquals(0L, play.durationMs)
        assertEquals("\"ch-1\"", caller.calls.single().second)
        val e = runCatching { source(caller, plugin(apiVersion = 2)).seriesListing(live) }.exceptionOrNull()
        assertEquals("Esto no tiene capítulos", e?.message)
    }

    /**
     * A side audio file merged into a live window has nothing to align with (it ends, or drifts):
     * a channel's alternate audio lives inside its manifest. A movie keeps its audioTracks.
     */
    @Test fun `a live channel's audioTracks are ignored, a movie's are kept`() = runTest {
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://example.com/live.m3u8","mime":"application/x-mpegURL",
            "audioTracks":[{"lang":"en","url":"https://example.com/a-en.aac"}]}"""))
        val live = source(caller, plugin(apiVersion = 2)).resolve(PluginRef("demo", "c1", PluginRef.LIVE, "ch-1").encode())
        assertTrue(live.audioTracks.isEmpty())
        val movie = source(caller, plugin(apiVersion = 2)).resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "m-1").encode())
        assertEquals(listOf("en"), movie.audioTracks.map { it.lang })
    }

    // --- Widevine (apiVersion 2, the `drm` capability) ---

    private val widevine = """{"url":"https://example.com/x.mpd","mime":"application/dash+xml",
        "drm":{"type":"widevine","licenseUrl":"https://example.com/lic","licenseHeaders":{"Authorization":"Bearer t"}}}"""
    private val movie = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()

    @Test fun `a plugin that declares drm resolves a protected stream, which is never downloadable`() = runTest {
        val play = source(FakeCaller(mapOf("resolve" to widevine)), plugin(caps = setOf("search", "resolve", "drm"), apiVersion = 2)).resolve(movie)
        assertEquals("https://example.com/x.mpd", play.url)
        assertEquals("https://example.com/lic", play.drmLicenseUrl)
        assertEquals(mapOf("Authorization" to "Bearer t"), play.drmLicenseHeaders)
        assertEquals("Este video no se puede descargar", com.arkiv.player.data.local.PluginDownloadEligibility.refusal(play))
    }

    @Test fun `without the drm capability a protected stream is refused as before, and a clear one carries no license`() = runTest {
        val e = runCatching { source(FakeCaller(mapOf("resolve" to widevine)), plugin(apiVersion = 2)).resolve(movie) }.exceptionOrNull()
        assertEquals("Demo: El video tiene DRM y los plugins no lo soportan", e?.message)
        val clear = source(FakeCaller(mapOf("resolve" to """{"url":"https://example.com/x.mp4"}""")), plugin(caps = setOf("search", "resolve", "drm"), apiVersion = 2)).resolve(movie)
        assertEquals("", clear.drmLicenseUrl)
        assertEquals(emptyMap<String, String>(), clear.drmLicenseHeaders)
    }

    private class FakeCaller(val answers: Map<String, String>) : PluginCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += function to argJson
            return answers[function] ?: throw PluginScriptException("boom")
        }
    }

    private fun source(caller: PluginCaller, p: InstalledPlugin = plugin()) = PluginContentSource(p, caller, log = {})

    // Final review, finding 1: the person approves a new host in the middle of `resolve` (its
    // kino.fetch retries there and succeeds); the stream the call then returns lives on that host.
    // The source reads its hosts AFTER the call, so the stream passes -- a copy taken when the source
    // was built (the registry's hosts before the approval) would refuse it as undeclared.
    @Test fun `a host approved during the call counts for that call's own stream`() = runTest {
        val p = plugin()
        var registryHosts = p.hosts
        val caller = PluginCaller { _, _, _, _ ->
            registryHosts = registryHosts.copy(declared = registryHosts.declared + "new-cdn.example") // PluginRegistry.addApprovedHost
            """{"url":"https://new-cdn.example/v.mp4"}"""
        }
        val ref = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()
        val live = PluginContentSource(p, caller, p.hosts, log = {}, currentHosts = { registryHosts })
        assertEquals("https://new-cdn.example/v.mp4", live.resolve(ref).url)
        // The pre-fix shape (a snapshot only) refuses the very stream the person just allowed.
        registryHosts = p.hosts
        val e = runCatching { PluginContentSource(p, caller, p.hosts, log = {}).resolve(ref) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e is GatewayException)
    }

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

    @Test fun `a load timeout is worded with its own seconds, never the search limit`() = runTest {
        val caller = PluginCaller { _, _, _, _ -> throw PluginTimeoutException("La carga del plugin", 10_000) }
        val err = source(caller).search(GatewaySearchQuery(q = "x")).toList().filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("tardó más de 10 s", err.error)
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

    @Test fun `a listing wraps each sibling season's ref as this plugin's series ref`() = runTest {
        val caller = FakeCaller(
            mapOf(
                "episodes" to """{"episodes":[{"season":2,"number":1,"ref":"E1"}],
                  "seasons":[{"id":"s1","ref":"S1","title":"Temporada 1","number":1},
                             {"id":"s2","ref":"S2","title":"Temporada 2","number":2,"current":true}]}""",
            ),
        )
        val listing = source(caller).seriesListing(PluginRef("demo", "s2", PluginRef.SERIES, "S2").encode())
        assertEquals(listOf(2 to 1), listing.episodes.map { it.season to it.number })
        assertEquals(listOf("s1", "s2"), listing.seasons.map { it.contentId })
        assertEquals(listOf(1, 2), listing.seasons.map { it.number })
        assertEquals(listOf("Temporada 1", "Temporada 2"), listing.seasons.map { it.label })
        assertEquals(listOf(false, true), listing.seasons.map { it.current })
        // The wrapped ref is what a search would give that season's series card: opening it asks the plugin with its own "S1".
        assertEquals(PluginRef("demo", "s1", PluginRef.SERIES, "S1"), PluginRef.decode(listing.seasons[0].ref))
        assertTrue(source(caller).recognizes(listing.seasons[0].ref))
    }

    @Test fun `a listing without seasons has none, and the chapters still come through the pair`() = runTest {
        val caller = FakeCaller(mapOf("episodes" to """{"episodes":[{"season":1,"number":1,"ref":"E1"},{"season":2,"number":1,"ref":"E2"}]}"""))
        val ref = PluginRef("demo", "s1", PluginRef.SERIES, "S1").encode()
        assertEquals(emptyList<com.arkiv.player.data.gateway.SeasonRef>(), source(caller).seriesListing(ref).seasons)
        assertEquals(2, source(caller).episodesWithSeries(ref).first.size)
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

    // --- liveStreamHosts "any" (apiVersion 3) ---

    @Test fun `with liveStreamHosts any approved, only a live ref resolves to an undeclared public server`() = runTest {
        val base = plugin(caps = setOf("home", "resolve", "channels"), apiVersion = 3)
        val any = base.copy(record = base.record.copy(liveStreamHostsAny = true))
        val caller = FakeCaller(mapOf("resolve" to """{"url":"http://cdn.iptv-somewhere.net/1.m3u8"}"""))
        val live = PluginRef("demo", "c1", PluginRef.LIVE, "ch-1").encode()
        assertEquals("http://cdn.iptv-somewhere.net/1.m3u8", source(caller, any).resolve(live).url)
        // A movie of the same plugin stays strict, and so does a live ref without the approval.
        val movie = PluginRef("demo", "m1", PluginRef.MOVIE, "m-1").encode()
        assertTrue(runCatching { source(caller, any).resolve(movie) }.exceptionOrNull() is GatewayException)
        assertTrue(runCatching { source(caller, base).resolve(live) }.exceptionOrNull() is GatewayException)
        // Never the LAN, approval or not.
        val lan = FakeCaller(mapOf("resolve" to """{"url":"http://192.168.1.20/1.m3u8"}"""))
        assertTrue(runCatching { source(lan, any).resolve(live) }.exceptionOrNull() is GatewayException)
    }

    // --- streamHosts "any" (apiVersion 4) ---

    @Test fun `with streamHosts any approved, a movie and a live ref resolve to an undeclared public server`() = runTest {
        val base = plugin(caps = setOf("home", "resolve"), apiVersion = 4)
        val any = base.copy(record = base.record.copy(streamHostsAny = true))
        val caller = FakeCaller(mapOf("resolve" to """{"url":"https://cdn.random-tld.xyz/v.mp4"}"""))
        val movie = PluginRef("demo", "m1", PluginRef.MOVIE, "m-1").encode()
        val live = PluginRef("demo", "c1", PluginRef.LIVE, "ch-1").encode()
        // A movie's under the one broad-video rule: the player's own resolve.
        assertEquals("https://cdn.random-tld.xyz/v.mp4", withContext(InteractivePluginCall) { source(caller, any).resolve(movie) }.url)
        assertEquals("https://cdn.random-tld.xyz/v.mp4", source(caller, any).resolve(live).url)
        // A download's resolve keeps the strict rule, exactly as under the broad video permission.
        assertTrue(runCatching { withContext(BackgroundPluginCall) { source(caller, any).resolve(movie) } }.exceptionOrNull() is GatewayException)
        // Without the approval it stays strict.
        assertTrue(runCatching { withContext(InteractivePluginCall) { source(caller, base).resolve(movie) } }.exceptionOrNull() is GatewayException)
        // Never the LAN, approval or not.
        val lan = FakeCaller(mapOf("resolve" to """{"url":"http://192.168.1.20/v.mp4"}"""))
        assertTrue(runCatching { withContext(InteractivePluginCall) { source(lan, any).resolve(movie) } }.exceptionOrNull() is GatewayException)
    }

    @Test fun `streamHosts any and the broad video permission are one predicate behind the player's VOD gate`() {
        val base = plugin(caps = setOf("home", "resolve"), apiVersion = 4)
        val declared = base.copy(record = base.record.copy(streamHostsAny = true))
        val granted = base.copy(record = base.record.copy(anyVideoHost = true))
        val movie = PluginRef("demo", "m1", PluginRef.MOVIE, "m-1").encode()
        val live = PluginRef("demo", "c1", PluginRef.LIVE, "ch-1").encode()
        for (p in listOf(declared, granted)) {
            assertTrue(p.record.videoFromAnyHost)
            val ready = PluginAccess.Ready("Demo", p.hosts, liveHosts = p.liveHosts, videoHosts = p.videoHosts)
            assertTrue(ready.streamHostsFor("demo", movie).anyPublicVideoHost)
            // The plugin's own hosts (kino.fetch, licenses, downloads) never carry it.
            assertFalse(p.hosts.anyPublicStreamHost)
        }
        assertFalse(base.record.videoFromAnyHost)
        assertFalse(base.videoHosts.anyPublicVideoHost)
        // A live channel: streamHosts "any" relaxes it (main's rule), the person's broad permission never does.
        assertTrue(declared.liveHosts.anyPublicLiveHost)
        assertFalse(granted.liveHosts.anyPublicLiveHost)
        assertFalse(PluginAccess.Ready("Demo", granted.hosts, liveHosts = granted.liveHosts, videoHosts = granted.videoHosts).streamHostsFor("demo", live).anyPublicStreamHost)
    }
}
