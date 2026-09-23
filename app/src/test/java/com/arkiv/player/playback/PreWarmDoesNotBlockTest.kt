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

/**
 * The deadline for waiting on the tail has to be a real deadline.
 *
 * Measured on the Fire TV on 2026-08-13, playing a magis series chapter: the log said `tail didn't
 * arrive in 2000ms → playing without duration` and the pre-warm phase still took **9218 ms**. The
 * deadline was being honoured and nobody moved on.
 *
 * The reason is about coroutines, not the network: the tail used to be launched with `async` INSIDE
 * `preWarm`'s `withContext(Dispatchers.IO)`. `withTimeoutOrNull { tail.await() }` cancels the WAIT,
 * not the work -- and a `withContext` doesn't return until all its children finish. So once the
 * deadline expired, the notice got printed, the block was left… and there the `withContext` sat
 * still waiting on the very tail it had just walked away from.
 *
 * The symptom is expensive twice over: first because it's seconds of spinner, and second because
 * they're seconds spent on data libVLC calculates on its own anyway (measured on the same device:
 * `dur=3831168ms` against the probe's 3831000 ms).
 */
class PreWarmDoesNotBlockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** How long the origin takes to release the TAIL. Longer than the wait deadline, on purpose. */
    private val SLOW_TAIL_MS = 6_000L

    private fun tsPacket(): ByteArray = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }

    private val file: ByteArray by lazy {
        ByteArray(3 * 1024 * 1024).also { out ->
            val p = tsPacket()
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                val total = file.size
                val isTail = range.startsWith("bytes=-")
                val (from, until) = when {
                    isTail -> (total - range.removePrefix("bytes=-").toInt()) to (total - 1)
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
                    // The HEAD arrives right away; the TAIL is the one that takes its time.
                    .apply { if (isTail) setHeadersDelay(SLOW_TAIL_MS, TimeUnit.MILLISECONDS) }
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        // `runCatching` and not a bare shutdown: when the test ends, the tail is STILL downloading
        // -that's exactly what these two cases came to check- and MockWebServer complains about not
        // being able to close the request queue. That thread surviving the test is the intended
        // behaviour, not a leak: it's what leaves the tail in memory for libVLC's EOF probes without
        // holding up startup. It's one server per test, so they don't step on each other.
        runCatching { origin.shutdown() }
    }

    @Test
    fun `with a slow tail, the pre-warm still returns as soon as the head serves`() = runBlocking {
        val t0 = System.currentTimeMillis()
        proxy.preWarm(origin.url("/v.ts").toString(), waitForTail = false)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "waitForTail=false can't take as long as the tail does; took ${ms}ms of ${SLOW_TAIL_MS}ms",
            ms < SLOW_TAIL_MS / 2,
        )
    }

    @Test
    fun `waiting on the tail has a deadline and the deadline is honoured`() = runBlocking {
        // With `waitForTail=true` the tail gets its chance, but a BOUNDED one: past the deadline it
        // plays without duration. The notice printing while the function keeps waiting is the same
        // as having no deadline at all.
        val t0 = System.currentTimeMillis()
        proxy.preWarm(origin.url("/v.ts").toString(), waitForTail = true)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "the tail's wait deadline wasn't honoured: took ${ms}ms, the tail took ${SLOW_TAIL_MS}ms",
            ms < SLOW_TAIL_MS - 1_000,
        )
    }
}
