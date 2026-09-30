package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of a Nuvio scraper's streams the converted plugin's `resolve` plays: not simply the first,
 * but by quality -- 1080p, then 720p, then anything else, and 2160p/4K last (most TVs and phones
 * here can't decode 4K HEVC; 4KHDHub's 4K stalled the KALLEY R3). Read defensively from Nuvio's
 * `quality`, `title` and `name`, whatever type a scraper puts there; ties keep the scraper's order.
 */
class NuvioQualityPickTest {
    private val scraper = NuvioScraperEntry(
        id = "qsrc", name = "QSrc", filename = "providers/qsrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie", "tv"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun picked(streamsJs: String): Pair<String, List<String>> {
        val source = "async function getStreams(tmdbId, mediaType, season, episode) {\nreturn $streamsJs;\n}\nmodule.exports = { getStreams: getStreams };"
        val script = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo").script
        val host = NuvioRoutingHost(emptyMap())
        val out = runBlocking {
            val rt = PluginRuntime.open("quality", script, host, PluginEnv(appVersion = "1.0"))
            try { rt.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie"}"""), 5_000) } finally { rt.close() }
        }
        return JSONObject(out).getString("url") to host.logs.toList()
    }

    @Test fun `1080p wins over 4K, 720p and an unlabeled stream`() {
        val (url, logs) = picked(
            """[
              { name: "4KHDHub", title: "Movie 2160p HEVC", quality: "4K", url: "https://a.example/uhd.mkv" },
              { name: "4KHDHub", title: "Movie", quality: "720p", url: "https://a.example/720.mp4" },
              { name: "4KHDHub", title: "Movie", url: "https://a.example/plain.mp4" },
              { name: "4KHDHub - 1080p", title: "Movie", url: "https://b.example/1080.mkv" }
            ]""",
        )
        assertEquals("https://b.example/1080.mkv", url)
        val line = logs.single { "getStreams returned" in it }
        assertTrue(line, "playing b.example" in line && "1080p" in line)
    }

    @Test fun `720p before anything else, anything else before 4K`() {
        assertEquals("https://a.example/720.mp4", picked("""[{ quality: "2160p", url: "https://a.example/4k.mkv" }, { title: "HD 720p", url: "https://a.example/720.mp4" }]""").first)
        assertEquals("https://a.example/hd.mp4", picked("""[{ quality: "UHD", url: "https://a.example/uhd.mkv" }, { quality: "HD", url: "https://a.example/hd.mp4" }]""").first)
        // Only 4K on offer: it still plays.
        assertEquals("https://a.example/4k.mkv", picked("""[{ quality: "4K", url: "https://a.example/4k.mkv" }]""").first)
    }

    @Test fun `a provider named 4K-something is not taken for a 4K stream`() {
        assertEquals(
            "https://a.example/first.mp4",
            picked("""[{ name: "4KHDHub", title: "Movie", url: "https://a.example/first.mp4" }, { name: "4KHDHub", title: "Movie 4K", url: "https://a.example/4k.mkv" }]""").first,
        )
    }

    @Test fun `odd field types never break the pick, and ties keep the scraper's order`() {
        val (url, _) = picked(
            """[
              { quality: null, title: 42, name: { toString: function () { throw new Error("boom"); } }, url: "https://a.example/odd.mp4" },
              { quality: 1080, url: "https://a.example/num1080.mp4" },
              { quality: ["1080p"], url: "https://a.example/list1080.mp4" }
            ]""",
        )
        assertEquals("https://a.example/num1080.mp4", url)
        assertEquals("https://a.example/one.mp4", picked("""[{ quality: "1080p", url: "https://a.example/one.mp4" }, { quality: "1080p", url: "https://a.example/two.mp4" }]""").first)
    }
}
