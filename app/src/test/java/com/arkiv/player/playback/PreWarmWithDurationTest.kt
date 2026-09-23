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

/**
 * The pre-warm downloads BOTH ends and the duration comes from that, without asking for anything more.
 *
 * It used to be four trips to the CDN to start: the duration probe asked for the head and tail on
 * its own, and `preWarm` asked again for those same two ends -- the 256 KB at the end DUPLICATED and
 * competing with itself. Measured on 2026-08-11 on the Fire TV over eight startups, `pre-warm` was
 * the dominant phase in 6 of 8 (1443-6647 ms) and in two runs the probe lost its race and the film
 * came out with no duration.
 *
 * The bytes the probe needs are EXACTLY the ones the pre-warm already has in hand.
 */
class PreWarmWithDurationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** 188-byte TS packet, with optional PCR (90 kHz base) in the adaptation field. */
    private fun packet(pid: Int, base90k: Long?): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (base90k == null) { p[3] = 0x10; return p }   // payload only, no PCR
        p[3] = 0x20                                       // adaptation field only
        p[4] = 183.toByte()
        p[5] = 0x10                                       // PCR-present flag
        p[6] = ((base90k shr 25) and 0xFF).toByte()
        p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte()
        p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = ((base90k and 1L) shl 7).toByte()
        p[11] = 0
        return p
    }

    /**
     * A ~3 MB TS with a PCR ONLY on the first packet and the last: that way the expected duration
     * doesn't depend on exactly which byte the tail gets cut at.
     */
    private val PACKETS = 15_958
    private val FINAL_PCR = 900_000L                      // 10 s at 90 kHz
    private val file: ByteArray by lazy {
        val out = ByteArray(PACKETS * 188)
        for (i in 0 until PACKETS) {
            val base = when (i) {
                0 -> 0L
                PACKETS - 1 -> FINAL_PCR
                else -> null
            }
            packet(0x100, base).copyInto(out, i * 188)
        }
        out
    }

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        // By Range, not by arrival order: head and tail are requested IN PARALLEL, so with
        // `enqueue` (FIFO) either one could get the other's response.
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                val total = file.size
                val (from, until) = when {
                    range.startsWith("bytes=-") ->
                        (total - range.removePrefix("bytes=-").toInt()) to (total - 1)
                    range.startsWith("bytes=") -> {
                        val p = range.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (total - 1))
                    }
                    else -> 0 to (total - 1)
                }
                val chunk = file.copyOfRange(from, until + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$until/$total")
                    .setHeader("Content-Length", chunk.size.toString())
                    .setBody(okio.Buffer().write(chunk))
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        origin.shutdown()
    }

    @Test
    fun `the duration comes from the pre-warm without requesting anything extra`() = runBlocking {
        val url = origin.url("/v.ts").toString()

        assertTrue("the pre-warm had to work", proxy.preWarm(url))

        assertEquals(10_000L, proxy.durationOfPreWarmed(url))
    }

    @Test
    fun `preWarm makes exactly two trips to the origin`() = runBlocking {
        // One per end. Three would mean someone re-requested what was already in memory, which is
        // exactly what this came to erase.
        proxy.preWarm(origin.url("/v.ts").toString())

        assertEquals(2, origin.requestCount)
    }

    @Test
    fun `with no pre-warm there is no duration to give`() = runBlocking {
        // Not an error: the caller has to be able to tell it apart to fall back to the network probe.
        assertEquals(0L, proxy.durationOfPreWarmed(origin.url("/otra.ts").toString()))
    }
}
