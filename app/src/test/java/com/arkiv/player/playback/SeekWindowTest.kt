package com.arkiv.player.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resuming can't cost ten connections to the CDN.
 *
 * Measured on the Fire TV on 2026-08-13 resuming a film at 13:26: libVLC doesn't seek once, it
 * **bisects**. It requested ten ranges in a row -`bytes=62148288-`, `63899508-`, `63533848-`,
 * `63443420-`…- reading a few hundred KB from each and cutting the connection right away, each with
 * its own connection at ~300 ms. And all ten landed within 1.8 MB of the file.
 *
 * This reproduces that read pattern -request, read a little, cut, request again nearby- and checks
 * that the second round no longer touches the network.
 */
class SeekWindowTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    private val TOTAL = 32 * 1024 * 1024
    private val file: ByteArray by lazy {
        // Non-uniform content: if the proxy served bytes from ANOTHER spot in the file, constant
        // filler would let it slide. Every byte depends on its position.
        ByteArray(TOTAL) { (it % 251).toByte() }
    }

    private val requestsToOrigin = AtomicInteger(0)

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                val isSuffix = range.startsWith("bytes=-")
                if (!isSuffix) requestsToOrigin.incrementAndGet()
                val (from, until) = when {
                    isSuffix -> (TOTAL - range.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    range.startsWith("bytes=") -> {
                        val p = range.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                val chunk = file.copyOfRange(from, until + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$until/$TOTAL")
                    .setHeader("Content-Length", chunk.size.toString())
                    .setBody(okio.Buffer().write(chunk))
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { origin.shutdown() }
    }

    /**
     * Requests a range, reads [toRead] bytes and CUTS OFF -- same as libVLC bisecting.
     *
     * Fails LOUDLY if the probe itself doesn't go well, and that's the fix for a real flake: the
     * `runCatching` used to swallow any problem and return whatever had been read. A probe that blew
     * up was indistinguishable from one served from memory -both leave the origin counter at zero-,
     * so the test failed with "the first probe had to hit the origin exactly once, expected 1 but
     * was 0", pointing at the proxy when the probe itself was what had broken. Seen in 1 of 4 full
     * suite runs (never in isolation), on 2026-08-14.
     */
    private fun probe(proxyUrl: String, from: Long, toRead: Int): ByteArray {
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$from-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val code = runCatching { conn.responseCode }
            .getOrElse { throw AssertionError("the probe from $from got no response from the proxy", it) }
        assertEquals("the proxy didn't serve the range from $from", 206, code)
        val buf = ByteArray(toRead)
        var n = 0
        runCatching {
            conn.inputStream.use { ins ->
                while (n < toRead) {
                    val l = ins.read(buf, n, toRead - n)
                    if (l < 0) break
                    n += l
                }
            }
        }.getOrElse { throw AssertionError("the probe from $from cut off while reading ($n of $toRead bytes)", it) }
        conn.disconnect()
        assertEquals("the probe from $from read short", toRead, n)
        return buf.copyOf(n)
    }

    @Test
    fun `seeks near the first one are answered from memory`() = runBlocking {
        proxy.start()
        val url = origin.url("/v.ts").toString()
        // The proxy learns the total from the tail's pre-warm, and without it can't build the
        // Content-Range of a response served from memory.
        proxy.preWarm(url, waitForTail = true)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), direct = true)
        requestsToOrigin.set(0)

        val base = 20L * 1024 * 1024
        // First probe: this one DOES go to the origin, and leaves the window as a side effect.
        probe(proxyUrl, base, 64 * 1024)
        val afterTheFirst = requestsToOrigin.get()

        // The window fills on the same thread that handled the probe; it needs a moment.
        Thread.sleep(2_000)

        // The next ones, near the first, like in the measured bisection.
        val results = listOf(1_500_000L, 900_000L, 400_000L, 120_000L).map { d ->
            d to probe(proxyUrl, base + d, 32 * 1024)
        }

        assertEquals(
            "the first probe had to hit the origin exactly once", 1, afterTheFirst,
        )
        assertEquals(
            "the nearby probes went back to the network instead of coming from the window",
            afterTheFirst, requestsToOrigin.get(),
        )
        // And what it delivered has to be the CORRECT stretch, not bytes from just anywhere.
        for ((d, bytes) in results) {
            assertTrue("the probe at +$d came back empty", bytes.isNotEmpty())
            val expected = ((base + d) % 251).toByte()
            assertEquals("the probe at +$d delivered bytes from somewhere else", expected, bytes[0])
        }
    }

    @Test
    fun `the main read does not spend a window`() = runBlocking {
        // `bytes=0-` is the sequential read and the player NEVER cuts that one off: saving it a
        // window would be 4 MB of RAM per playback for nothing.
        proxy.start()
        val url = origin.url("/w.ts").toString()
        proxy.preWarm(url, waitForTail = true)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), direct = true)

        probe(proxyUrl, 0L, 64 * 1024)
        Thread.sleep(500)

        // If it had saved a window from 0, this request would come from memory and not touch the origin.
        requestsToOrigin.set(0)
        probe(proxyUrl, 100_000L, 16 * 1024)
        assertEquals("the main read left a window that's of no use to anyone", 1, requestsToOrigin.get())
    }
}
