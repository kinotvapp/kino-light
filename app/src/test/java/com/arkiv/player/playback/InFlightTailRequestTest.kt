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
import java.util.concurrent.TimeUnit

/**
 * The end of the file is requested ONE single time, even if two want it at once.
 *
 * Since startup stopped waiting on the tail, the pre-warm and libVLC's EOF probe stopped going one
 * after another and started going at the same time -- two connections asking for the SAME bytes.
 * Measured on the Fire TV on 2026-08-13, with the CDN on a bad streak, they rejected each other for
 * 7 s and VLC took 7719 ms to open waiting on its own tail.
 *
 * This checks the one thing that keeps that from coming back: that the second one to arrive WAITS
 * on the first instead of opening its own connection.
 */
class InFlightTailRequestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** How long the origin takes to release the tail: enough for the probe to land in the middle. */
    private val TAIL_MS = 2_000L

    private val TOTAL = 3 * 1024 * 1024
    private val file: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    /**
     * The two ways of requesting the end are counted SEPARATELY, because they mean opposite things:
     *
     * - by SUFFIX (`bytes=-N`) is the pre-warm asking, and that one can legitimately come out
     *   duplicated when the origin is slow (see ColaDuplicadaTest). Two of those is the intended behaviour.
     * - by ABSOLUTE range near the end is the player asking, and that one must NEVER reach the
     *   network: that's the whole point of making it wait on the tail already downloading.
     *
     * Counting them together -like it used to- turned the duplicate into a false failure for this test.
     */
    private val preWarmed = java.util.concurrent.atomic.AtomicInteger(0)
    private val playerProbes = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                val isSuffix = range.startsWith("bytes=-")
                val (from, until) = when {
                    isSuffix -> (TOTAL - range.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    range.startsWith("bytes=") -> {
                        val p = range.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                // The pre-warm asks for a CLOSED range at the end (see ColaPorRangoAbsolutoTest); the
                // player asks OPEN from wherever it wants to read. That's the difference that tells
                // "the tail downloading" apart from "the probe that must not touch the network".
                val closed = Regex("""bytes=\d+-\d+""").matches(range)
                if (isSuffix || (closed && from > TOTAL / 2)) preWarmed.incrementAndGet()
                else if (!closed && from > TOTAL / 2) playerProbes.incrementAndGet()
                val chunk = file.copyOfRange(from, until + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$until/$TOTAL")
                    .setHeader("Content-Length", chunk.size.toString())
                    .setBody(okio.Buffer().write(chunk))
                    .apply { if (isSuffix || (closed && from > TOTAL / 2)) setHeadersDelay(TAIL_MS, TimeUnit.MILLISECONDS) }
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { origin.shutdown() }
    }

    @Test
    fun `the end-of-file probe waits on the tail already downloading, does not open another connection`() = runBlocking {
        proxy.start()
        val url = origin.url("/v.ts").toString()
        // Like on a real startup: pre-warms without waiting on the tail…
        proxy.preWarm(url, waitForTail = false)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), direct = true)

        // …and right away libVLC's EOF probe arrives, with the tail still in flight.
        val requested = TOTAL - 40_000L
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$requested-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val body = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        assertEquals("has to deliver the whole stretch", (TOTAL - requested).toInt(), body.size)
        assertEquals(
            "the player's probe opened its own connection instead of waiting on the one already downloading",
            0, playerProbes.get(),
        )
    }

    @Test
    fun `the head does not wait on the tail`() = runBlocking {
        proxy.start()
        val url = origin.url("/v.ts").toString()
        proxy.preWarm(url, waitForTail = false)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), direct = true)

        // `bytes=0-` is libVLC's FIRST read and gets answered by the hot startup. If it waited on
        // the tail, the race fix would eat into the very startup it came to protect.
        val t0 = System.currentTimeMillis()
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=0-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.inputStream.use { it.read(ByteArray(64 * 1024)) }
        conn.disconnect()
        val ms = System.currentTimeMillis() - t0

        assertTrue("the head took ${ms}ms: it was left waiting on the tail", ms < TAIL_MS)
    }
}
