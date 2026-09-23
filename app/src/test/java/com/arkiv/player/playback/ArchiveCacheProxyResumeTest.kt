package com.arkiv.player.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * The proxy's DIRECT path, which is what magis uses (and, since the light-magis branch's
 * archive.org pruning, the ONLY real path: see [ArchiveCacheProxy]'s KDoc).
 *
 * Until that pruning there was a second ("classic", archive.org) path that downloaded the whole
 * file into the cache and served from there while it grew. With magis that was poison: these are
 * ~1 GB VODs behind a token-gated CDN, the download cuts off, and on cutting off the proxy USED TO
 * DELETE the file and the next request started over at byte 0 -- while whoever was serving VLC
 * kept reading at the old offset, where there was now a different part of the film. VLC saw it as
 * an MPEG-TS with gaps ("TS discontinuity"), the clock jumped by minutes and the video died.
 *
 * Direct does the same thing as the Python replica that played the TS back correctly: relay every
 * Range to the origin as-is and return the body. No cache, no growing file, nothing to truncate.
 */
class ArchiveCacheProxyResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy
    private lateinit var cacheDir: java.io.File

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        cacheDir = temp.newFolder("cache")
        proxy = ArchiveCacheProxy(cacheDir)
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origin.shutdown()
    }

    private fun body(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun request(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            if (range != null) setRequestProperty("Range", range)
        }
        val code = c.responseCode
        val data = runCatching { c.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
        c.disconnect()
        return code to data
    }

    @Test
    fun `direct relays the Range as-is and returns that stretch`() {
        val data = body(100_000)
        val from = 40_000
        origin.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                .setHeader("Content-Length", (data.size - from).toString())
                .setBody(okio.Buffer().write(data.copyOfRange(from, data.size))),
        )

        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), mapOf("A" to "b"), direct = true)
        val (code, received) = request(url, "bytes=$from-")

        assertEquals(206, code)
        assertArrayEquals(data.copyOfRange(from, data.size), received)
        assertEquals("bytes=$from-", origin.takeRequest().getHeader("Range"))
    }

    @Test
    fun `direct leaves nothing in the cache`() {
        val data = body(50_000)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )

        request(proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true))

        assertEquals(
            "direct must not write cache files",
            0,
            cacheDir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `direct sends the CDN's headers`() {
        val data = body(20_000)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )

        request(
            proxy.proxyUrl(
                origin.url("/v.ts").toString(),
                mapOf("Content-Auth" to "TOKEN", "Content-License" to "LIC"),
                direct = true,
            ),
        )

        val r = origin.takeRequest()
        assertEquals("TOKEN", r.getHeader("Content-Auth"))
        assertEquals("LIC", r.getHeader("Content-License"))
    }

    @Test
    fun `without d=1 the proxy falls back to plain passthrough, leaving nothing in the cache`() {
        // The "classic" path (archive.org's disk cache, which did leave a file in `cacheDir`) was
        // deleted in this branch's pruning: without `d=1` all that's left is the same plain
        // passthrough as direct, and so it doesn't cache anything on disk either.
        val data = body(50_000)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )

        val (code, received) = request(proxy.proxyUrl(origin.url("/v.ts").toString()))

        assertEquals(200, code)
        assertArrayEquals(data, received)
        assertEquals(
            "with no disk cache, no file is left in cacheDir",
            0,
            cacheDir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `the direct url leaves the origin at the end so it keeps parsing fine`() {
        val u = proxy.proxyUrl("https://ejemplo/v.ts", mapOf("A" to "b"), direct = true)
        assertEquals(
            "https://ejemplo/v.ts",
            java.net.URLDecoder.decode(u.substringAfter("u=").substringBefore('&'), "UTF-8"),
        )
        assertEquals(0f, proxy.bufferedFraction(u), 0.0001f)
    }
}
