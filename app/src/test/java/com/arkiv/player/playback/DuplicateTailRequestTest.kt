package com.arkiv.player.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the first connection for the tail hangs, the second one saves startup.
 *
 * Measured on the Fire TV on 2026-08-13 with a new chapter: the CDN didn't answer the tail twice in
 * a row -saying nothing at all, which is how this CDN fails- and each silence costs the MAGIS
 * profile's 3 s deadline. Since libVLC needs the end of the file to open, it was left waiting 7.3 s
 * before showing the first frame.
 *
 * Retrying IN SERIES doesn't fix that: it has to wait for the previous attempt to give up before
 * rolling the dice again. That's why the second request goes out IN PARALLEL.
 */
class DuplicateTailRequestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** How long the FIRST tail request takes. Longer than the duplicate's deadline, on purpose. */
    private val FIRST_TAIL_MS = 5_000L

    private val TOTAL = 3 * 1024 * 1024
    private val file: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    private val tailsRequested = AtomicInteger(0)

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                // The tail is requested by a CLOSED range at the end of the file (see
                // ColaPorRangoAbsolutoTest: this CDN doesn't answer suffixes). What arrives open
                // -`bytes=N-`- is the player reading, not the pre-warm.
                val isTail = Regex("""bytes=(\d+)-(\d+)""").find(range)
                    ?.let { it.groupValues[1].toInt() > TOTAL / 2 } == true
                val (from, until) = when {
                    range.startsWith("bytes=-") -> (TOTAL - range.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    range.startsWith("bytes=") -> {
                        val p = range.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                val chunk = file.copyOfRange(from, until + 1)
                val r = MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$until/$TOTAL")
                    .setHeader("Content-Length", chunk.size.toString())
                    .setBody(okio.Buffer().write(chunk))
                // ONLY the first tail request hangs; the second answers right away. It's the
                // measured case: the same range that a connection didn't answer, answered over another.
                if (isTail && tailsRequested.incrementAndGet() == 1) {
                    r.setHeadersDelay(FIRST_TAIL_MS, TimeUnit.MILLISECONDS)
                }
                return r
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { origin.shutdown() }
    }

    @Test
    fun `if the first tail hangs, the duplicate brings it without waiting out the whole deadline`() = runBlocking {
        val url = origin.url("/v.ts").toString()
        val t0 = System.currentTimeMillis()
        // `waitForTail=true` so this test can measure when the tail was ready. In production
        // startup doesn't wait on it (see PrecalentadoNoBloqueaTest); libVLC is the one waiting on
        // it, and it's libVLC this duplicate saves the seconds for.
        proxy.preWarm(url, waitForTail = true)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "waited out the first hung connection: ${ms}ms of ${FIRST_TAIL_MS}ms",
            ms < FIRST_TAIL_MS - 1_500,
        )
        assertTrue("the duplicate should have gone out", tailsRequested.get() >= 2)
        assertTrue("the tail should be in memory", proxy.durationOfPreWarmed(url) >= 0L)
    }

    @Test
    fun `when the first answers in time, it isn't requested twice`() = runBlocking {
        // The duplicate can't be free for the CDN in the normal case: it goes out late precisely so
        // that, when the first one is doing fine, this thread wakes up and doesn't touch the network.
        tailsRequested.set(1) // the dispatcher only delays request #1: so the next one already answers fast
        val url = origin.url("/rapida.ts").toString()
        proxy.preWarm(url, waitForTail = true)

        assertTrue(
            "the tail was requested extra: ${tailsRequested.get() - 1} requests",
            tailsRequested.get() - 1 == 1,
        )
    }
}
