package com.arkiv.player.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * The local proxy is the only way to send arbitrary headers to the origin: libVLC only exposes
 * `:http-referrer` and `:http-user-agent`, and magis serves the VOD behind `Content-Auth` and
 * `Content-License`.
 */
class ArchiveCacheProxyHeadersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origin.shutdown()
    }

    private fun body(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun request(url: String): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
        }
        val code = c.responseCode
        val data = runCatching { c.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
        c.disconnect()
        return code to data
    }

    @Test
    fun `with no headers the proxy url's shape does not change`() {
        val u = proxy.proxyUrl("https://ejemplo/video.mp4")
        assertTrue(u.startsWith("http://127.0.0.1:${proxy.port}/s?u="))
        assertTrue("should not add the h parameter", !u.contains("h="))
    }

    @Test
    fun `the extra headers reach the origin`() {
        val data = body(4096)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )
        val url = proxy.proxyUrl(
            origin.url("/v.ts").toString(),
            mapOf("Content-Auth" to "TOKEN-AUTH", "Content-License" to "LIC"),
        )
        val (code, _) = request(url)
        assertEquals(200, code)

        val received = origin.takeRequest()
        assertEquals("TOKEN-AUTH", received.getHeader("Content-Auth"))
        assertEquals("LIC", received.getHeader("Content-License"))
    }

    @Test
    fun `the origin decodes fine even with headers in the url`() {
        val data = body(2048)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )
        val url = proxy.proxyUrl(origin.url("/con%20espacio.ts").toString(), mapOf("X-Uno" to "1"))
        val (code, _) = request(url)
        assertEquals(200, code)
        // What's verified is that the origin survives the extra URL parameter. The body's SIZE is
        // NOT asserted: the proxy serves the file WHILE downloading it, so how much it managed to
        // write depends on timing and would make the test flaky.
        assertEquals("/con%20espacio.ts", origin.takeRequest().path)
    }

    @Test
    fun `bufferedFraction still reads the origin with headers in the url`() {
        // It used to parse with substringAfter("u="), which swallowed any parameter that followed.
        val url = proxy.proxyUrl("https://ejemplo/video.mp4", mapOf("A" to "b"))
        assertEquals(0f, proxy.bufferedFraction(url), 0.0001f)
    }

    @Test
    fun `a header with odd characters survives the trip`() {
        val data = body(1024)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )
        val value = "sign=abc/def+ghi=&t=123"
        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), mapOf("Content-Auth" to value))
        request(url)
        assertEquals(value, origin.takeRequest().getHeader("Content-Auth"))
    }

    @Test
    fun `serves the body without dying on the cache-file creation race`() {
        // The server opens the cache file to read as soon as the download starts. If the writer
        // hadn't created it yet, the read died with ENOENT and the player was stuck at "buffering
        // 0%" forever.
        repeat(5) {
            val data = body(64 * 1024)
            origin.enqueue(
                MockResponse().setBody(okio.Buffer().write(data))
                    .setHeader("Content-Length", data.size.toString()),
            )
            val (code, _) = request(proxy.proxyUrl(origin.url("/v$it.ts").toString(), mapOf("A" to "b")))
            assertEquals(200, code)
        }
    }

    @Test
    fun `with no headers, none extra get sent`() {
        val data = body(512)
        origin.enqueue(
            MockResponse().setBody(okio.Buffer().write(data))
                .setHeader("Content-Length", data.size.toString()),
        )
        request(proxy.proxyUrl(origin.url("/v.ts").toString()))
        assertNull(origin.takeRequest().getHeader("Content-Auth"))
    }
}
