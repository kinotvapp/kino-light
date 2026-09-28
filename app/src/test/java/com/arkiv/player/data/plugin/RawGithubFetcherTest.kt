package com.arkiv.player.data.plugin

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RawGithubFetcherTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    /** A client that sends every raw.githubusercontent.com request to [server] instead. */
    private fun fetcher() = RawGithubFetcher(
        OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val r = chain.request()
                chain.proceed(r.newBuilder().url(server.url(r.url.encodedPath)).build())
            }
            .build(),
    )

    private val url = "https://raw.githubusercontent.com/o/r/HEAD/kino-plugin.json"

    @Test fun `a read is served`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertArrayEquals("{}".toByteArray(), fetcher().fetch(url, 100))
    }

    @Test fun `cancelling a read of a host that never answers returns at once, not at the read timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        try {
            withTimeout(300) { fetcher().fetch(url, 100) }
            fail("a host that never answers cannot be read")
        } catch (e: TimeoutCancellationException) {
            // Expected.
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the read held on for $elapsedMs ms after it was cancelled", elapsedMs < 3_000)
    }
}
