package com.arkiv.player.data.plugin

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The person's own server as a stream host, measured on the wire. A typed server can never be
 * loopback, and MockWebServer only listens there, so the typed servers are LAN addresses
 * (10.0.2.2, 10.0.2.3) and [LanToLoopback] — a test-only socket factory — delivers each
 * connection to the MockWebServer on the same port. What is asserted is WHICH server got a
 * request (request counts), not only which exception came back.
 */
class PluginUserHostStreamTest {
    private val typed = MockWebServer()
    private val other = MockWebServer()

    @Before fun start() { typed.start(); other.start() }
    @After fun stop() { typed.shutdown(); other.shutdown() }

    /** Connects wherever OkHttp asked, but on 127.0.0.1: the port picks the MockWebServer. */
    private object LanToLoopback : javax.net.SocketFactory() {
        private class Redirecting : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                val port = (endpoint as java.net.InetSocketAddress).port
                super.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout)
            }
        }
        override fun createSocket() = Redirecting()
        override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    }

    private fun client(hosts: EffectiveHosts) = PluginStreamHttp.client(OkHttpClient.Builder().socketFactory(LanToLoopback).build(), hosts)

    @Test fun `a redirect from the typed server to another typed server never reaches it`() {
        val hosts = EffectiveHosts(emptyList(), listOf(UserHost("http", "10.0.2.2", typed.port), UserHost("http", "10.0.2.3", other.port)))
        typed.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.2.3:${other.port}/x"))
        other.enqueue(MockResponse().setBody("must not be served"))
        assertThrows(HostNotAllowedException::class.java) {
            client(hosts).newCall(Request.Builder().url("http://10.0.2.2:${typed.port}/v.m3u8").build()).execute()
        }
        assertEquals(1, typed.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test fun `a redirect that stays on the typed server is followed`() {
        val hosts = EffectiveHosts(emptyList(), listOf(UserHost("http", "10.0.2.2", typed.port)))
        typed.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        typed.enqueue(MockResponse().setBody("ok"))
        client(hosts).newCall(Request.Builder().url("http://10.0.2.2:${typed.port}/v").build()).execute().use { assertEquals("ok", it.body!!.string()) }
        assertEquals(2, typed.requestCount)
    }

    @Test fun `a redirect from the typed server to loopback is refused before connecting`() {
        val hosts = EffectiveHosts(emptyList(), listOf(UserHost("http", "10.0.2.2", typed.port)))
        typed.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${other.port}/proxy"))
        other.enqueue(MockResponse().setBody("must not be served"))
        assertThrows(HostNotAllowedException::class.java) {
            client(hosts).newCall(Request.Builder().url("http://10.0.2.2:${typed.port}/v").build()).execute()
        }
        assertEquals(1, typed.requestCount)
        assertEquals(0, other.requestCount)
    }
}
