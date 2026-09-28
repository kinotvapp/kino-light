package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import com.arkiv.player.data.plugin.PluginPlaylist
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Playlist and guide downloads: the strict host gate, the playlist's headers, a byte cap. */
class PluginPlaylistFetcherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private fun local() = PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(listOf("localhost")), allowInsecureLocalhost = true)
    private fun url(path: String) = "http://localhost:${server.port}$path"
    private val list = "#EXTM3U\n#EXTINF:-1,Uno\nhttps://live.example.com/1.m3u8\n"

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun `downloads with the playlist's headers, refuses oversize and undeclared hosts`() = runBlocking {
        server.enqueue(MockResponse().setBody("#EXTM3U\n"))
        val client = PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(listOf("localhost")), allowInsecureLocalhost = true)
        val fetcher = PluginPlaylistFetcher(client)
        assertEquals("#EXTM3U\n", String(fetcher.fetch(server.url("/l.m3u").toString(), mapOf("X-Token" to "t"), 1024)))
        assertEquals("t", server.takeRequest().getHeader("X-Token"))
        server.enqueue(MockResponse().setBody("x".repeat(2048)))
        assertThrows(IOException::class.java) { runBlocking { fetcher.fetch(server.url("/big.m3u").toString(), emptyMap(), 1024) } }
        val strict = PluginPlaylistFetcher(PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(listOf("lists.example.com"))))
        assertThrows(Exception::class.java) { runBlocking { strict.fetch("https://evil.example.org/l.m3u", emptyMap(), 1024) } }
        Unit
    }

    @Test fun `a server that trickles the body is cut off by the call timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1000)).throttleBody(10, 1, TimeUnit.SECONDS))
        val fetcher = PluginPlaylistFetcher(local(), timeoutMs = 500)
        val start = System.currentTimeMillis()
        assertThrows(IOException::class.java) { runBlocking { fetcher.fetch(url("/slow.m3u"), emptyMap(), 1 shl 20) } }
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test fun `cancelling the caller cancels the download at once`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1000)).throttleBody(10, 1, TimeUnit.SECONDS))
        val fetcher = PluginPlaylistFetcher(local())
        val job = launch(Dispatchers.Default) { fetcher.fetchTo(url("/slow.m3u"), emptyMap(), 1 shl 20, tmp.newFile("x.tmp")) }
        delay(300)
        withTimeout(2_000) { job.cancelAndJoin() }
    }

    @Test fun `a download streams to its file, and one over the cap leaves nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody("x".repeat(4096)))
        val fetcher = PluginPlaylistFetcher(local())
        val out = File(tmp.root, "a.tmp")
        fetcher.fetchTo(url("/a.m3u"), emptyMap(), 1024, out)
        assertEquals(list, out.readText())
        val big = File(tmp.root, "b.tmp")
        assertThrows(IOException::class.java) { runBlocking { fetcher.fetchTo(url("/b.m3u"), emptyMap(), 1024, big) } }
        assertFalse(big.exists())
    }

    @Test fun `a redirect to an undeclared host is refused`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example.org/l.m3u"))
        assertThrows(Exception::class.java) { runBlocking { PluginPlaylistFetcher(local()).fetch(url("/r.m3u"), emptyMap(), 1024) } }
        Unit
    }

    @Test fun `a body cut mid-download keeps the good saved copy`() = runBlocking {
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody(list + "#EXTINF:-1,Dos\nhttps://live.example.com/2.m3u8\n".repeat(2000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val source = PlaylistSource(PluginPlaylist(url("/l.m3u")), PluginPlaylistFetcher(local()), tmp.root, System::currentTimeMillis, {})
        assertEquals(1, source.entries(false)!!.total)
        assertEquals(1, source.entries(true)!!.total)
        assertEquals(list, File(File(tmp.root, "live"), "${source.key}.m3u").readText())
        assertEquals(listOf("${source.key}.m3u"), File(tmp.root, "live").list()!!.toList())
    }
}
