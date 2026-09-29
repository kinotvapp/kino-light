package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a converted Nuvio scraper's `resolve` says when it has nothing to play, so Kino can tell the
 * person WHY (see [PluginFailureText]): no result at all, only torrents, or the scraper reporting
 * its own error (Nuvio scrapers catch their failures and `console.error` them, then return []).
 * And the log line counting what came back and what was dropped.
 */
class NuvioResolveOutcomeTest {
    private val scraper = NuvioScraperEntry(
        id = "outsrc", name = "OutSrc", filename = "providers/outsrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie", "tv"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun resolve(getStreamsBody: String): Pair<Result<String>, List<String>> {
        val source = "async function getStreams(tmdbId, mediaType, season, episode) {\n$getStreamsBody\n}\nmodule.exports = { getStreams: getStreams };"
        val script = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "k").script
        val host = NuvioRoutingHost(emptyMap())
        val result = runBlocking {
            val rt = PluginRuntime.open("outcome", script, host, PluginEnv(appVersion = "1.0"))
            try { runCatching { rt.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie"}"""), 5_000) } } finally { rt.close() }
        }
        return result to host.logs.toList()
    }

    private fun typed(r: Result<String>) = r.exceptionOrNull() as PluginErrorException

    @Test fun `no stream at all is not_found, sin resultados`() {
        val (r, logs) = resolve("return [];")
        assertEquals(PluginErrors.NOT_FOUND, typed(r).code)
        assertEquals(NuvioPluginConverter.NO_STREAMS, typed(r).message)
        assertTrue(logs.toString(), logs.any { "getStreams returned 0 streams" in it })
    }

    @Test fun `only torrents is unavailable, solo torrents`() {
        val (r, logs) = resolve("""return [{ name: "a", infoHash: "abc" }, { name: "b", url: "magnet:?xt=1", infoHash: "def" }];""")
        assertEquals(PluginErrors.UNAVAILABLE, typed(r).code)
        assertEquals(NuvioPluginConverter.ONLY_TORRENTS, typed(r).message)
        assertTrue(logs.toString(), logs.any { "getStreams returned 2 streams" in it && "2 torrents" in it })
    }

    // Nuvio scrapers catch their own failures, console.error them, and return []: that error is the
    // best reason Kino has, short of a network failure it saw itself.
    @Test fun `a scraper that reported its own error and returned nothing says so`() {
        val (r, _) = resolve("""console.error("[OutSrc] Error in getStreams: unexpected token: ''"); return [];""")
        assertEquals(PluginErrors.UNAVAILABLE, typed(r).code)
        assertEquals(NuvioPluginConverter.SCRAPER_ERROR_PREFIX + "unexpected token: ''", typed(r).message)
    }

    @Test fun `the log counts what came back and what was dropped`() {
        val (r, logs) = resolve(
            """return [{ name: "t", infoHash: "x" }, { name: "n" }, { name: "ok", url: "https://cdn.example/v.m3u8" }];""",
        )
        assertEquals("https://cdn.example/v.m3u8", JSONObject(r.getOrThrow()).getString("url"))
        val line = logs.single { "getStreams returned" in it }
        assertTrue(line, "returned 3 streams" in line && "1 torrent" in line && "1 without url" in line && "cdn.example" in line)
    }
}
