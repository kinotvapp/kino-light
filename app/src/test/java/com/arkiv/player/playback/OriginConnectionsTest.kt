package com.arkiv.player.playback

import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class OriginConnectionsTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun connections(dns: Dns = Dns.SYSTEM) = OriginConnections(dns, connectTimeoutMs = 2_000, readTimeoutMs = 2_000)

    @Test
    fun `a 200 gives its code, its headers and its body`() {
        server.enqueue(MockResponse().setBody("hello").setHeader("cf-ray", "abc"))
        server.start()

        val r = connections().open("http://${server.hostName}:${server.port}/x", emptyMap())

        assertEquals(200, r.responseCode)
        assertEquals("abc", r.getHeaderField("cf-ray"))
        assertNull(r.getHeaderField("age"))
        assertEquals("hello", r.inputStream.bufferedReader().readText())
    }

    @Test
    fun `a 404 keeps its body in errorStream and refuses inputStream, like HttpURLConnection`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        server.start()

        val r = connections().open("http://${server.hostName}:${server.port}/x", emptyMap())

        assertEquals(404, r.responseCode)
        assertEquals("nope", r.errorStream?.bufferedReader()?.readText())
        assertThrows(IOException::class.java) { r.inputStream }
    }

    @Test
    fun `the headers we set go out`() {
        server.enqueue(MockResponse())
        server.start()

        connections().open(
            "http://${server.hostName}:${server.port}/x",
            mapOf("Content-Auth" to "sig01", "User-Agent" to "kino"),
        ).responseCode

        val sent = server.takeRequest()
        assertEquals("sig01", sent.getHeader("Content-Auth"))
        assertEquals("kino", sent.getHeader("User-Agent"))
    }

    @Test
    fun `a redirect is reported and not followed`() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://elsewhere.invalid/"))
        server.start()

        val r = connections().open("http://${server.hostName}:${server.port}/x", emptyMap())

        assertEquals(301, r.responseCode)
        assertEquals("http://elsewhere.invalid/", r.getHeaderField("Location"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `hosts are resolved with the Dns we hand in`() {
        server.enqueue(MockResponse().setBody("via dns"))
        server.start()
        val asked = mutableListOf<String>()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                asked += hostname
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }

        val r = connections(dns).open("http://cdn.blocked.test:${server.port}/x", emptyMap())

        assertEquals("via dns", r.inputStream.bufferedReader().readText())
        assertEquals(listOf("cdn.blocked.test"), asked)
    }

    @Test
    fun `nothing goes out until the first read of the response, and a failure is thrown from there`() {
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        }

        val r = connections(dns).open("http://cdn.gone.test/x", emptyMap())

        assertThrows(IOException::class.java) { r.responseCode }
    }

    @Test
    fun `disconnect closes the response`() {
        server.enqueue(MockResponse().setBody("x".repeat(1000)))
        server.start()
        val r = connections().open("http://${server.hostName}:${server.port}/x", emptyMap())
        assertEquals(200, r.responseCode)

        r.disconnect()

        assertTrue(runCatching { r.inputStream.read() }.isFailure)
    }
}
