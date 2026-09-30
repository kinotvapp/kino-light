package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** A converted Nuvio scraper gets [PluginHttp.NUVIO_MAX_REQUESTS_PER_CALL]; every other plugin keeps 60. */
class NuvioRequestBudgetTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun record(nuvioScraperId: String?) = InstalledRecord(
        address = "https://example.com/p", version = "1.0.0", sha256 = "x", hosts = listOf("localhost"),
        installedAt = 0L, nuvioScraperId = nuvioScraperId,
    )

    private fun http(record: InstalledRecord) = PluginHttp(
        OkHttpClient(), "test", EffectiveHosts(listOf("localhost")), "1.0", allowInsecureLocalhost = true,
        maxRequestsPerCall = PluginHttp.requestBudgetFor(record),
    )

    private fun url() = "http://localhost:${server.port}/n"

    @Test fun `the budget follows the plugin's origin`() {
        assertEquals(60, PluginHttp.requestBudgetFor(record(null)))
        assertEquals(250, PluginHttp.requestBudgetFor(record("areshd")))
    }

    @Test fun `a Nuvio scraper makes 100 requests in one call`() = runBlocking {
        repeat(100) { server.enqueue(MockResponse().setBody("x")) }
        val h = http(record("areshd"))
        h.beginCall()
        repeat(100) { assertEquals("x", h.fetch(PluginHttp.Request(url())).text) }
        assertEquals(100, server.requestCount)
    }

    @Test fun `a regular plugin still stops at 60`() = runBlocking {
        repeat(61) { server.enqueue(MockResponse().setBody("x")) }
        val h = http(record(null))
        h.beginCall()
        repeat(60) { h.fetch(PluginHttp.Request(url())) }
        val e = assertThrows(PluginFetchException::class.java) { runBlocking { h.fetch(PluginHttp.Request(url())) } }
        assertEquals("invalid_request", e.code)
        assertEquals(60, server.requestCount)
    }
}
