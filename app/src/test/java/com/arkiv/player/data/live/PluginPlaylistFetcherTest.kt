package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Playlist and guide downloads: the strict host gate, the playlist's headers, a byte cap. */
class PluginPlaylistFetcherTest {
    private val server = MockWebServer()

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
}
