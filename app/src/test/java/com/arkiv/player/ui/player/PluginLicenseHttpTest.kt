package com.arkiv.player.ui.player

import android.net.JvmUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * What goes on the wire for a plugin stream's Widevine license request, through the very factories
 * `StreamExoPlayer` builds ([pluginHttpFactories]) over the plugin's host-gated client: the
 * `licenseHeaders` and nothing of the Stream's `headers` (the guide promises exactly that), while the
 * stream's own requests keep carrying `headers`.
 */
class PluginLicenseHttpTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private val loopback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    // MockWebServer serves plain http on localhost: the test-only override PluginStreamHttpTest uses.
    private fun gated(hosts: List<String> = listOf("localhost")) =
        PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(hosts), allowInsecureLocalhost = true, delegateDns = loopback)

    private val streamHeaders = mapOf(
        "User-Agent" to "StreamUA/1",
        "Cookie" to "cdn=secret",
        "Referer" to "https://cdn.example/",
        "Authorization" to "Bearer cdn",
    )

    // android.jar's TextUtils.isEmpty is a stub that answers false, so media3's HttpUtil parses a
    // missing Content-Range as present and dies on null: every response here carries a real one.
    private fun ok(body: String) = MockResponse().setBody(body).setHeader("Content-Range", "bytes 0-${body.length - 1}/${body.length}")

    private fun url(path: String) = "http://localhost:${server.port}$path"

    /** Opens [url] the way media3's `HttpMediaDrmCallback` posts a Widevine key request (`DrmUtil.executePost`). */
    private fun postLicense(factory: DataSource.Factory, url: String, licenseHeaders: Map<String, String>): ByteArray {
        val source = factory.createDataSource()
        val spec = DataSpec.Builder()
            .setUri(JvmUri(url))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(1, 2, 3))
            .setHttpRequestHeaders(mapOf("Content-Type" to "application/octet-stream") + licenseHeaders)
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()
        try {
            source.open(spec)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun getStream(factory: DataSource.Factory, url: String) {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(JvmUri(url)).build())
        } finally {
            source.close()
        }
    }

    @Test fun `the license request carries licenseHeaders and none of the stream's headers`() {
        val licenseHeaders = mapOf("Authorization" to "Bearer lic", "X-License-Token" to "t1")
        val factories = pluginHttpFactories(gated(), streamHeaders, licenseHeaders)
        server.enqueue(ok("license-bytes"))

        val body = postLicense(factories.license, url("/wv"), licenseHeaders)

        assertArrayEquals("license-bytes".toByteArray(), body)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer lic", request.getHeader("Authorization"))
        assertEquals("t1", request.getHeader("X-License-Token"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Referer"))
        assertEquals("okhttp/4.12.0", request.getHeader("User-Agent"))
    }

    @Test fun `a User-Agent in licenseHeaders is the one the license request sends`() {
        val licenseHeaders = mapOf("User-Agent" to "LicenseUA/2")
        val factories = pluginHttpFactories(gated(), streamHeaders, licenseHeaders)
        server.enqueue(ok("ok"))

        postLicense(factories.license, url("/wv"), licenseHeaders)

        assertEquals("LicenseUA/2", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `the stream's own requests still carry its headers`() {
        val factories = pluginHttpFactories(gated(), streamHeaders, mapOf("Authorization" to "Bearer lic"))
        server.enqueue(ok("#EXTM3U"))

        getStream(factories.stream, url("/master.m3u8"))

        val request = server.takeRequest()
        assertEquals("StreamUA/1", request.getHeader("User-Agent"))
        assertEquals("cdn=secret", request.getHeader("Cookie"))
        assertEquals("https://cdn.example/", request.getHeader("Referer"))
        assertEquals("Bearer cdn", request.getHeader("Authorization"))
    }

    @Test fun `the license request still meets the host gate`() {
        val factories = pluginHttpFactories(gated(hosts = listOf("cdn.example.com")), streamHeaders, emptyMap())

        assertThrows(IOException::class.java) { postLicense(factories.license, url("/wv"), emptyMap()) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `under liveStreamHosts any the stream relaxes but the license client stays strict`() {
        val any = EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true)
        fun client(hosts: EffectiveHosts) = PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = loopback)
        val factories = pluginHttpFactories(client(any), streamHeaders, emptyMap(), licenseClient = client(any.strict))
        server.enqueue(ok("#EXTM3U"))

        getStream(factories.stream, "http://cdn.iptv-somewhere.test:${server.port}/1.m3u8")
        assertEquals(1, server.requestCount)

        assertThrows(IOException::class.java) { postLicense(factories.license, "http://cdn.iptv-somewhere.test:${server.port}/wv", emptyMap()) }
        assertEquals(1, server.requestCount)
    }
}
