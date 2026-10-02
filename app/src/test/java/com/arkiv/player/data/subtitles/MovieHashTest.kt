package com.arkiv.player.data.subtitles

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** The OSDb hash, its three-read budget, the HTTP range reader and the rules for skipping a stream. */
class MovieHashTest {

    /** A deterministic file of [size] bytes. */
    private fun bytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) xor (i ushr 8)).toByte() }

    /** Independent oracle: BigInteger math, one 8-byte word at a time, modulo 2^64. */
    private fun expected(data: ByteArray): String {
        val mod = BigInteger.ONE.shiftLeft(64)
        var total = BigInteger.valueOf(data.size.toLong())
        fun add(from: Int) {
            for (w in 0 until 8192) {
                var word = BigInteger.ZERO
                for (b in 7 downTo 0) word = word.shiftLeft(8).or(BigInteger.valueOf((data[from + w * 8 + b].toLong() and 0xff)))
                total = total.add(word)
            }
        }
        add(0)
        add(data.size - 65536)
        return total.mod(mod).toString(16).padStart(16, '0')
    }

    private class FakeReader(val data: ByteArray?, val size: Long? = data?.size?.toLong(), val failAt: Int = -1) : MovieHash.RangeReader {
        var lengths = 0
        val reads = mutableListOf<Pair<Long, Int>>()
        override suspend fun length(): Long? { lengths++; return size }
        override suspend fun read(offset: Long, len: Int): ByteArray? {
            reads += offset to len
            if (reads.size == failAt) return null
            return data?.copyOfRange(offset.toInt(), offset.toInt() + len)
        }
    }

    private fun hash(data: ByteArray) = runBlocking { MovieHash.compute(FakeReader(data)) }

    @Test fun `a file under two windows has no hash`() {
        assertNull(hash(bytes(131071)))
        assertNull(hash(ByteArray(0)))
    }

    @Test fun `exactly two windows hashes`() {
        val d = bytes(131072)
        assertEquals(expected(d), hash(d))
    }

    @Test fun `windows that overlap still hash`() {
        val d = bytes(200_000)
        assertEquals(expected(d), hash(d))
    }

    @Test fun `a large file hashes`() {
        val d = bytes(1_000_000)
        assertEquals(expected(d), hash(d))
    }

    @Test fun `the sum wraps around 2 to the 64`() {
        val d = ByteArray(131072) { 0xff.toByte() }
        // Each window sums 8192 words of 2^64-1 = -8192 mod 2^64; plus the size.
        val want = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(131072L - 16384L)).mod(BigInteger.ONE.shiftLeft(64)).toString(16).padStart(16, '0')
        assertEquals(want, hash(d))
        assertEquals(expected(d), hash(d))
    }

    @Test fun `the hash is 16 lowercase hex chars`() {
        val h = hash(bytes(300_000))!!
        assertEquals(16, h.length)
        assertEquals(h.lowercase(), h)
    }

    @Test fun `a local file is read from disk`() {
        val d = bytes(250_000)
        val f = File.createTempFile("hash", ".mp4").apply { writeBytes(d); deleteOnExit() }
        assertEquals(expected(d), runBlocking { MovieHash.compute(MovieHash.LocalReader(f)) })
    }

    @Test fun `at most three reads - the size, the head and the tail`() {
        val r = FakeReader(bytes(400_000))
        runBlocking { MovieHash.compute(r) }
        assertEquals(1, r.lengths)
        assertEquals(listOf(0L to 65536, 400_000L - 65536 to 65536), r.reads)
    }

    @Test fun `an unknown size stops after the size`() {
        val r = FakeReader(bytes(400_000), size = null)
        assertNull(runBlocking { MovieHash.compute(r) })
        assertEquals(0, r.reads.size)
    }

    @Test fun `a failed read gives null without throwing`() {
        assertNull(runBlocking { MovieHash.compute(FakeReader(bytes(400_000), failAt = 2)) })
        assertNull(runBlocking { MovieHash.compute(FakeReader(bytes(400_000), failAt = 1)) })
    }

    @Test fun `a reader that throws gives null`() {
        val boom = object : MovieHash.RangeReader {
            override suspend fun length(): Long = throw IllegalStateException("x")
            override suspend fun read(offset: Long, len: Int): ByteArray? = null
        }
        assertNull(runBlocking { MovieHash.compute(boom) })
    }

    @Test fun `a short read gives null`() {
        val short = object : MovieHash.RangeReader {
            override suspend fun length(): Long = 400_000
            override suspend fun read(offset: Long, len: Int): ByteArray = ByteArray(10)
        }
        assertNull(runBlocking { MovieHash.compute(short) })
    }

    @Test fun `a reader slower than the budget gives null`() {
        val slow = object : MovieHash.RangeReader {
            override suspend fun length(): Long { delay(5_000); return 400_000 }
            override suspend fun read(offset: Long, len: Int): ByteArray? = null
        }
        val t0 = System.nanoTime()
        assertNull(runBlocking { MovieHash.compute(slow, budgetMs = 100) })
        assert((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    // --- HTTP range reader against a mock server ---

    private fun rangeServer(data: ByteArray, ranges: Boolean): MockWebServer = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (!ranges || range == null) return MockResponse().setBody(okio.Buffer().write(data))
                val (a, b) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $a-$b/${data.size}")
                    .setBody(okio.Buffer().write(data.copyOfRange(a, b + 1)))
            }
        }
        start()
    }

    @Test fun `http ranges give the same hash and send the stream's headers`() {
        val d = bytes(300_000)
        val server = rangeServer(d, ranges = true)
        try {
            val reader = HttpRangeReader(OkHttpClient(), server.url("/f/Nosferatu.mp4").toString(), mapOf("Referer" to "https://ref/"))
            assertEquals(expected(d), runBlocking { MovieHash.compute(reader) })
            assertEquals(3, server.requestCount)
            assertEquals("https://ref/", server.takeRequest().getHeader("Referer"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a server without range support is not hashed`() {
        val server = rangeServer(bytes(300_000), ranges = false)
        try {
            assertNull(runBlocking { MovieHash.compute(HttpRangeReader(OkHttpClient(), server.url("/f.mp4").toString())) })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `an unreachable server gives null`() {
        val server = MockWebServer().apply { start() }
        val url = server.url("/f.mp4").toString()
        server.shutdown()
        assertNull(runBlocking { MovieHash.compute(HttpRangeReader(OkHttpClient(), url)) })
    }

    @Test fun `the total comes from Content-Range`() {
        assertEquals(1234L, HttpRangeReader.totalOf("bytes 0-0/1234"))
        assertNull(HttpRangeReader.totalOf("bytes 0-0/*"))
        assertNull(HttpRangeReader.totalOf(null))
    }

    // --- which streams are hashed ---

    private fun skip(url: String, mime: String = "", live: Boolean = false, xuper: Boolean = false, proxied: Boolean = false, drm: Boolean = false) =
        SubtitleHashPolicy.skipReason(url, mime, live, xuper, proxied, drm)

    @Test fun `a plain remote mp4 is hashed`() {
        assertNull(skip("https://archive.org/download/nosferatu/Nosferatu.mp4"))
        assertNull(skip("https://cdn.example/video?id=3", mime = "video/mp4"))
    }

    @Test fun `streams that are not a stable plain file are skipped`() {
        assertEquals(HashSkip.HLS, skip("https://x/a/master.m3u8?t=1"))
        assertEquals(HashSkip.HLS, skip("https://x/a", mime = "application/x-mpegURL"))
        assertEquals(HashSkip.DASH, skip("https://x/a.mpd"))
        assertEquals(HashSkip.TS, skip("https://x/a.ts"))
        assertEquals(HashSkip.TS, skip("https://x/a", mime = "video/mp2t"))
        assertEquals(HashSkip.LIVE, skip("https://x/a.mp4", live = true))
        assertEquals(HashSkip.XUPER, skip("https://x/a.mp4", xuper = true))
        assertEquals(HashSkip.DRM, skip("https://x/a.mp4", drm = true))
        assertEquals(HashSkip.LOCAL_PROXY, skip("http://127.0.0.1:8080/p/abc"))
        assertEquals(HashSkip.LOCAL_PROXY, skip("https://x/a.mp4", proxied = true))
        assertEquals(HashSkip.NOT_REMOTE, skip("file:///data/a.mp4"))
    }

    @Test fun `the file name is the last segment without query or extension`() {
        assertEquals("Nosferatu 1922", SubtitleHashPolicy.fileNameOf("https://u:p@archive.org/download/x/Nosferatu%201922.mp4?sig=secret"))
        assertNull(SubtitleHashPolicy.fileNameOf("https://x/"))
    }
}
