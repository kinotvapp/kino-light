package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The generic strategy behind [DownloadSource.PLUGIN_DOWNLOAD]: resolves the saved ref through the
 * plugin's own `resolve()` (the app's [ContentSource]) and saves the Stream as one file with the
 * Stream's headers on the request; refuses what the downloader cannot save as a file.
 */
class PluginDownloadStrategyTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    private val episodeId = "plugin:demo:m1::0"
    private val savedRef = "plg1:{\"p\":\"demo\"}"

    /** Answers every `resolve` with [playable]; records what it was asked for. */
    private class FakeSource(private val playable: () -> GatewayPlayable) : ContentSource {
        val resolved = mutableListOf<String>()
        override fun recognizes(ref: String) = true
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
        /** Whether each resolve ran as a background call (its timeouts never switch the plugin off). */
        val background = mutableListOf<Boolean>()
        override suspend fun resolve(ref: String): GatewayPlayable {
            resolved += ref
            background += kotlin.coroutines.coroutineContext[com.arkiv.player.data.plugin.BackgroundPluginCall] != null
            return playable()
        }
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> = emptyList<GatewayEpisode>() to null
    }

    private fun playable(path: String, mime: String = "", headers: Map<String, String> = emptyMap(), subtitles: List<GatewaySubtitle> = emptyList()) =
        GatewayPlayable(kind = "plugin", url = server.url(path).toString(), headers = headers, mime = mime, subtitles = subtitles)

    private fun strategy(
        source: ContentSource,
        offers: (String) -> Boolean = { it == "demo" },
        refFor: suspend (String) -> String? = { savedRef },
        downloaders: MutableList<String> = mutableListOf(),
    ) = PluginDownloadStrategy(
        refForEpisode = refFor,
        source = source,
        downloaderFor = { pluginId -> downloaders += pluginId; HttpRangeDownloader(OkHttpClient(), freeSpace = { Long.MAX_VALUE }) },
        offersDownloads = offers,
    )

    private suspend fun DownloadStrategy.run(id: String = episodeId) = download(id, false, tmp.root) { _, _ -> }

    @Test fun `a plugin the person switched off is an expected failure, not a report`() = runBlocking {
        val off = FakeSource { throw com.arkiv.player.data.gateway.PluginBlockedException("Activa el plugin Demo para ver esto") }
        val outcome = strategy(off).run() as DownloadOutcome.Failed
        assertEquals("Activa el plugin Demo para ver esto", outcome.reason)
        assertTrue("never crash-reported", outcome.expected)
        assertFalse(DownloadRetryPolicy.reports(outcome.transient, outcome.permanent, outcome.expected))
    }

    @Test fun `any other resolve failure still reports`() = runBlocking {
        val broken = FakeSource { throw GatewayException("El plugin devolvió basura") }
        val outcome = strategy(broken).run() as DownloadOutcome.Failed
        assertFalse(outcome.expected)
        assertTrue(DownloadRetryPolicy.reports(outcome.transient, outcome.permanent, outcome.expected))
    }

    @Test fun `downloads the resolved stream with its headers into a file named after the episode`() = runBlocking {
        server.enqueue(MockResponse().setBody("VIDEO-BYTES"))
        val source = FakeSource { playable("/v.mp4", headers = mapOf("Referer" to "https://site.example/", "X-Token" to "abc")) }
        val downloaders = mutableListOf<String>()

        val outcome = strategy(source, downloaders = downloaders).run()

        val done = outcome as DownloadOutcome.Done
        assertEquals(File(tmp.root, LocalFilePaths.fileNameFor(episodeId, "plugin.mp4")), done.file)
        assertEquals("VIDEO-BYTES", done.file.readText())
        assertEquals(listOf(savedRef), source.resolved)
        val request = server.takeRequest()
        assertEquals("https://site.example/", request.getHeader("Referer"))
        assertEquals("abc", request.getHeader("X-Token"))
        // The client is the plugin's own (host-gated in the app), asked for by plugin id.
        assertEquals(listOf("demo"), downloaders)
    }

    @Test fun `the download's resolve is a background call, so its timeouts never count as No responde`() = runBlocking {
        server.enqueue(MockResponse().setBody("VIDEO"))
        val source = FakeSource { playable("/v.mp4") }

        strategy(source).run()

        assertEquals(listOf(true), source.background)
    }

    /** A live channel's ref refuses even when its stream is a plain file: it has no end to save. */
    @Test fun `a live channel is refused for good, whatever its stream looks like`() = runBlocking {
        server.enqueue(MockResponse().setBody("VIDEO-BYTES"))
        val liveRef = com.arkiv.player.data.plugin.PluginRef("demo", "c1", com.arkiv.player.data.plugin.PluginRef.LIVE, "ch-1").encode()

        val outcome = strategy(FakeSource { playable("/live.mp4") }, refFor = { liveRef }).run(com.arkiv.player.data.plugin.PluginIds.liveEpisodeId("demo", "c1"))

        val failed = outcome as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, failed.reason)
        assertTrue(failed.permanent)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the partial resumes under the episode id, not the changing url`() = runBlocking {
        val target = File(tmp.root, LocalFilePaths.fileNameFor(episodeId, "plugin.mp4"))
        LocalFilePaths.partOf(target).writeText("AAAA")
        LocalFilePaths.originOf(target).writeText(episodeId)
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val outcome = strategy(FakeSource { playable("/v.mp4?token=fresh") }).run()

        assertTrue(outcome is DownloadOutcome.Done)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `saves the stream's subtitles next to the file`() = runBlocking {
        server.enqueue(MockResponse().setBody("VIDEO"))
        server.enqueue(MockResponse().setBody("WEBVTT"))
        val subs = listOf(GatewaySubtitle("es", server.url("/s.vtt").toString(), "vtt"))

        val outcome = strategy(FakeSource { playable("/v.mp4", subtitles = subs) }).run()

        assertTrue(outcome is DownloadOutcome.Done)
        assertEquals(2, server.requestCount)
        assertTrue(OfflineSubtitleFiles.fileFor(tmp.root, episodeId, 0, "vtt").exists())
        assertEquals(1, OfflineSubtitleFiles.read(tmp.root, episodeId).size)
    }

    /** Online the player sends the Stream's headers with its subtitles; offline must match. */
    @Test fun `the subtitle sidecars are fetched with the stream's headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("VIDEO"))
        server.enqueue(MockResponse().setBody("WEBVTT"))
        val subs = listOf(GatewaySubtitle("es", server.url("/s.vtt").toString(), "vtt"))
        val headers = mapOf("Referer" to "https://site.example/", "X-Token" to "abc")

        val outcome = strategy(FakeSource { playable("/v.mp4", headers = headers, subtitles = subs) }).run()

        assertTrue(outcome is DownloadOutcome.Done)
        server.takeRequest()
        val sub = server.takeRequest()
        assertEquals("/s.vtt", sub.path)
        assertEquals("https://site.example/", sub.getHeader("Referer"))
        assertEquals("abc", sub.getHeader("X-Token"))
    }

    @Test fun `the mime names the extension, then the url, then mp4`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        server.enqueue(MockResponse().setBody("x"))
        server.enqueue(MockResponse().setBody("x"))
        val a = strategy(FakeSource { playable("/v?x=1", mime = "video/x-matroska") }).run() as DownloadOutcome.Done
        assertEquals("plugin_demo_m1__0.mkv", a.file.name)
        a.file.delete()
        val b = strategy(FakeSource { playable("/v.ts") }).run() as DownloadOutcome.Done
        assertEquals("plugin_demo_m1__0.ts", b.file.name)
        b.file.delete()
        val c = strategy(FakeSource { playable("/stream/42") }).run() as DownloadOutcome.Done
        assertEquals("plugin_demo_m1__0.mp4", c.file.name)
    }

    @Test fun `an HLS manifest is refused before any request, permanently`() = runBlocking {
        val outcome = strategy(FakeSource { playable("/master.m3u8") }).run()

        val failed = outcome as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, failed.reason)
        assertFalse("nothing to retry: the stream's shape will not change", failed.transient)
        assertTrue("a final state: no Reintentar, no crash report", failed.permanent)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a DRM stream is refused, permanently`() = runBlocking {
        val outcome = strategy(FakeSource { playable("/v.mp4").copy(drmLicenseUrl = "https://lic.example/wv") }).run()
        val failed = outcome as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, failed.reason)
        assertTrue(failed.permanent)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a manifest in disguise is caught from the response and refused permanently`() = runBlocking {
        // An extensionless URL, no mime: the eligibility check passes, the bytes say otherwise.
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg0.ts\n"))
        val outcome = strategy(FakeSource { playable("/hls/index") }).run()

        val failed = outcome as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, failed.reason)
        assertTrue(failed.permanent)
        assertFalse(failed.transient)
        val target = File(tmp.root, LocalFilePaths.fileNameFor(episodeId, "plugin.mp4"))
        assertFalse(target.exists())
        assertFalse(LocalFilePaths.partOf(target).exists())
        assertFalse(LocalFilePaths.originOf(target).exists())

        server.enqueue(MockResponse().setHeader("Content-Type", "application/dash+xml").setBody("<MPD/>"))
        val byType = strategy(FakeSource { playable("/dash/stream") }).run() as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, byType.reason)
        assertTrue(byType.permanent)
    }

    @Test fun `other failures are not permanent, so they keep Reintentar`() = runBlocking {
        val notOffered = strategy(FakeSource { playable("/v.mp4") }, offers = { false }).run() as DownloadOutcome.Failed
        assertFalse("re-enabling the plugin makes a retry worth it", notOffered.permanent)

        server.enqueue(MockResponse().setResponseCode(403))
        val forbidden = strategy(FakeSource { playable("/v.mp4") }).run() as DownloadOutcome.Failed
        assertFalse(forbidden.permanent)

        val network = strategy(FakeSource { throw IOException("red caída") }).run() as DownloadOutcome.Failed
        assertFalse(network.permanent)
    }

    @Test fun `a plugin that no longer offers downloads never resolves`() = runBlocking {
        val source = FakeSource { playable("/v.mp4") }
        val outcome = strategy(source, offers = { false }).run()

        assertTrue(outcome is DownloadOutcome.Failed)
        assertFalse((outcome as DownloadOutcome.Failed).transient)
        // The person's own doing (plugin disabled or uninstalled): retryable later, never a crash report.
        assertFalse(outcome.permanent)
        assertTrue(outcome.expected)
        assertTrue(source.resolved.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `a row that is not a plugin episode fails without resolving`() = runBlocking {
        val source = FakeSource { playable("/v.mp4") }
        val outcome = strategy(source, offers = { true }).run("magis:ABC::e1")
        assertTrue(outcome is DownloadOutcome.Failed)
        assertTrue(source.resolved.isEmpty())
    }

    @Test fun `an episode with no saved ref fails`() = runBlocking {
        val source = FakeSource { playable("/v.mp4") }
        val outcome = strategy(source, refFor = { null }).run()
        assertTrue(outcome is DownloadOutcome.Failed)
        assertTrue(source.resolved.isEmpty())
    }

    @Test fun `a resolve failure keeps the plugin's message and its transience`() = runBlocking {
        val network = strategy(FakeSource { throw IOException("red caída") }).run() as DownloadOutcome.Failed
        assertEquals("red caída", network.reason)
        assertTrue(network.transient)

        val plugin = strategy(FakeSource { throw GatewayException("Demo: enlace vencido") }).run() as DownloadOutcome.Failed
        assertEquals("Demo: enlace vencido", plugin.reason)
        assertFalse(plugin.transient)
    }

    @Test fun `a failed download keeps the downloader's transience`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val busy = strategy(FakeSource { playable("/v.mp4") }).run() as DownloadOutcome.Failed
        assertTrue(busy.transient)

        server.enqueue(MockResponse().setResponseCode(403))
        val forbidden = strategy(FakeSource { playable("/v.mp4") }).run() as DownloadOutcome.Failed
        assertFalse(forbidden.transient)
    }
}
