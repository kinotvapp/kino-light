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
 * Resuming can't start by downloading the beginning of the film.
 *
 * Measured on the Fire TV on 2026-08-13 resuming a film at 13:29: libVLC ALWAYS opens at byte 0 and
 * only afterward looks for the saved minute. In between, 2.5 MB of the start that then got thrown
 * away got downloaded, with the picture frozen at `pos=0` for 3.4 s.
 *
 * Pre-warming the destination area is the piece our copy of the original app's design was missing:
 * it tells its download engine where it's going BEFORE seeking.
 *
 * These tests' offsets are the ones really measured, not made up: it's the only thing that backs up
 * estimating the byte by constant rate at all.
 */
class PreWarmSeekTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Like the measured film: ~1.3 GB. A small file is used and scaled with the fraction. */
    private val TOTAL = 40 * 1024 * 1024
    private val file: ByteArray by lazy { ByteArray(TOTAL) { (it % 251).toByte() } }

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

    private fun probe(proxyUrl: String, from: Long, toRead: Int): ByteArray {
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$from-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val buf = ByteArray(toRead)
        var n = 0
        runCatching {
            conn.inputStream.use { ins ->
                while (n < toRead) {
                    val l = ins.read(buf, n, toRead - n); if (l < 0) break; n += l
                }
            }
        }
        conn.disconnect()
        return buf.copyOf(n)
    }

    /** Waits for the seek pre-warm to leave its window ready. */
    private fun waitForWindow() {
        Thread.sleep(2_500)
    }

    @Test
    fun `the pre-warmed seek answers the destination without touching the network`() = runBlocking {
        proxy.start()
        val url = origin.url("/v.ts").toString()
        proxy.preWarm(url, waitForTail = true)   // leaves the file's size on hand
        val fraction = 0.5f
        proxy.preWarmSeek(url, emptyMap(), fraction)

        val proxyUrl = proxy.proxyUrl(url, emptyMap(), direct = true)
        // The exact target and the really measured deviations: -2.7 MB and +3.7 MB.
        val target = (TOTAL * fraction).toLong()
        val deviations = listOf(0L, -2_700_000L, 3_700_000L)

        // Polls instead of a single fixed sleep-then-try: fast on a quiet machine (the common
        // case exits the loop on its first pass), but tolerant of a slow/contended CI runner
        // where the background pre-warm thread (a raw Thread, not awaitable from here -- see
        // ArchiveCacheProxy.preWarmSeek) can take longer than 2.5s to finish. Measured needing
        // this after the fixed 2.5s sleep flaked on a GitHub Actions runner but never locally.
        //
        // Widened from 15s to 30s on 2026-09-15: the release workflow's CI job now compiles a
        // native (NDK/CMake/mbedTLS) module in the same job, right before this test task runs --
        // real, measured extra CPU contention that wasn't there when 15s was tuned. This test's
        // own subject (ArchiveCacheProxy) is untouched by that change; only the CI machine's
        // available headroom during the poll window is.
        //
        // Widened again to 60s the same day: 30s still flaked once the NDK bumped from r26 to r28
        // (see app/build.gradle.kts), which the workflow installs fresh via sdkmanager on every
        // run instead of hitting whatever the runner image already had cached for r26 -- more
        // wall-clock spent downloading before this test task even starts.
        var bytesByDeviation = emptyMap<Long, ByteArray>()
        val deadline = System.currentTimeMillis() + 60_000
        do {
            requestsToOrigin.set(0)
            bytesByDeviation = deviations.associateWith { deviation -> probe(proxyUrl, target + deviation, 16 * 1024) }
            val servedFromCache = requestsToOrigin.get() == 0 && bytesByDeviation.values.all { it.isNotEmpty() }
            if (servedFromCache) break
            Thread.sleep(200)
        } while (System.currentTimeMillis() < deadline)

        for (deviation in deviations) {
            val bytes = bytesByDeviation.getValue(deviation)
            assertTrue("the probe at $deviation came back empty", bytes.isNotEmpty())
            assertEquals(
                "delivered bytes from somewhere else in the file",
                ((target + deviation) % 251).toByte(), bytes[0],
            )
        }
        assertEquals(
            "the seek's destination is still requested over the network: the pre-warm didn't serve",
            0, requestsToOrigin.get(),
        )
    }

    @Test
    fun `with no valid fraction, nothing gets pre-warmed`() = runBlocking {
        // Resuming at 0 (or an absurd fraction) isn't resuming: requesting bytes from the CDN there
        // would spend a connection for nothing.
        proxy.start()
        val url = origin.url("/w.ts").toString()
        proxy.preWarm(url, waitForTail = true)
        requestsToOrigin.set(0)

        proxy.preWarmSeek(url, emptyMap(), 0f)
        proxy.preWarmSeek(url, emptyMap(), 1f)
        proxy.preWarmSeek(url, emptyMap(), -0.3f)
        waitForWindow()

        assertEquals("something was requested from the origin with nowhere to seek to", 0, requestsToOrigin.get())
    }
}
