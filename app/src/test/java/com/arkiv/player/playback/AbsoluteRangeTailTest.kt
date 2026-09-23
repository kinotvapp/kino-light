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
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The end of the file is requested by an ABSOLUTE range, never by suffix.
 *
 * It's the most expensive thing found while measuring. On the Fire TV, on 2026-08-13, over 31
 * requests to magis's CDN:
 *
 * ```
 *   range shape              rejections   OK responses
 *   bytes=-262144 (suffix)      19            0
 *   bytes=N-      (absolute)     0           12
 * ```
 *
 * The nineteen rejections were all suffix and none were absolute. And it's not that the stretch
 * isn't there: in the same startup, after six rejections in a row of `bytes=-262144` (two parallel
 * connections, three attempts each, 8.8 s thrown away), the player requested that same end via
 * `bytes=322515168-` and the CDN served it in 267 ms.
 *
 * Since libVLC doesn't open the video until it has the end of the file, those seconds were paid for
 * in full as spinner.
 */
class AbsoluteRangeTailTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    private val TOTAL = 3 * 1024 * 1024
    private val file: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    private val rangesRequested = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                rangesRequested.add(range)
                // Like the real CDN: the suffix is NEVER answered. If the proxy requests it, it
                // hangs until the deadline, which is exactly what cost the seconds.
                if (range.startsWith("bytes=-")) {
                    return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
                val p = range.removePrefix("bytes=").split("-")
                val from = p[0].toIntOrNull() ?: 0
                val until = p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1)
                val chunk = file.copyOfRange(from, until + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$until/$TOTAL")
                    .setHeader("Content-Length", chunk.size.toString())
                    .setBody(okio.Buffer().write(chunk))
                    .setBodyDelay(0, TimeUnit.MILLISECONDS)
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { origin.shutdown() }
    }

    @Test
    fun `the tail is not requested by suffix`() = runBlocking {
        proxy.preWarm(origin.url("/v.ts").toString(), waitForTail = true)

        val suffixes = rangesRequested.filter { it.startsWith("bytes=-") }
        assertTrue(
            "the end was requested by suffix, which this CDN doesn't answer: $suffixes",
            suffixes.isEmpty(),
        )
    }

    @Test
    fun `the tail requests exactly the last stretch, by absolute range`() = runBlocking {
        val url = origin.url("/v.ts").toString()
        proxy.preWarm(url, waitForTail = true)

        val expected = "bytes=${TOTAL - TsDurationProbe.PROBE_BYTES}-${TOTAL - 1}"
        assertTrue(
            "the end wasn't requested by absolute range. Requests: $rangesRequested",
            rangesRequested.any { it == expected },
        )
        // And with that the tail stays in memory, which is what it was downloaded for.
        assertEquals(
            "the end wasn't saved even though the origin served it",
            true, proxy.durationOfPreWarmed(url) >= 0L,
        )
    }
}
