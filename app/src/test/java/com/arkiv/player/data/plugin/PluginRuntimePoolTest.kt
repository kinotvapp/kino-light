package com.arkiv.player.data.plugin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginRuntimePoolTest {
    // Only the beginCall/budget-reset integration test below uses this server.
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private class FakeRuntime(val answer: suspend (String) -> String) : ScriptRuntime {
        override val exports = setOf("search")
        override var isDiscarded = false
        var closed = false
        override suspend fun call(function: String, argJson: String, timeoutMs: Long): String =
            try { answer(function) } catch (e: PluginTimeoutException) { isDiscarded = true; throw e }
        override fun close() { closed = true; isDiscarded = true }
    }

    @Test fun `one runtime is reused across calls`() = runTest {
        var opened = 0
        val pool = PluginRuntimePool(open = { opened++; FakeRuntime { "ok" } }, onUnresponsive = {}, scope = backgroundScope)
        repeat(3) { assertEquals("ok", pool.call("p", "search", "{}", 1_000)) }
        assertEquals(1, opened)
    }

    @Test fun `three consecutive timeouts mark the plugin unresponsive once and reopen each time`() = runTest {
        var opened = 0
        val flagged = mutableListOf<String>()
        val pool = PluginRuntimePool(
            open = { opened++; FakeRuntime { throw PluginTimeoutException(it, 10) } },
            onUnresponsive = { flagged += it },
            scope = backgroundScope,
        )
        repeat(3) { assertTrue(runCatching { pool.call("p", "search", "{}", 10) }.exceptionOrNull() is PluginTimeoutException) }
        assertEquals(listOf("p"), flagged)
        assertEquals(3, opened)
    }

    @Test fun `a success in between resets the count`() = runTest {
        var fail = true
        val flagged = mutableListOf<String>()
        val pool = PluginRuntimePool(
            open = { FakeRuntime { if (fail) throw PluginTimeoutException(it, 10) else "ok" } },
            onUnresponsive = { flagged += it },
            scope = backgroundScope,
        )
        repeat(2) { runCatching { pool.call("p", "search", "{}", 10) } }
        fail = false; pool.call("p", "search", "{}", 10); fail = true
        repeat(2) { runCatching { pool.call("p", "search", "{}", 10) } }
        assertEquals(emptyList<String>(), flagged)
    }

    @Test fun `the runtime closes after five idle minutes`() = runTest {
        val rt = FakeRuntime { "ok" }
        val pool = PluginRuntimePool(open = { rt }, onUnresponsive = {}, scope = backgroundScope)
        pool.call("p", "search", "{}", 1_000)
        advanceTimeBy(4 * 60_000L); runCurrent()
        assertFalse(rt.closed)
        advanceTimeBy(2 * 60_000L); runCurrent()
        assertTrue(rt.closed)
    }

    @Test fun `close drops the runtime and beforeCall runs per call`() = runTest {
        val runtimes = mutableListOf<FakeRuntime>()
        var calls = 0
        val pool = PluginRuntimePool(
            open = { FakeRuntime { "ok" }.also { runtimes += it } },
            onUnresponsive = {}, scope = backgroundScope, beforeCall = { calls++ },
        )
        pool.call("p", "search", "{}", 1_000)
        pool.close("p"); runCurrent()
        assertTrue(runtimes[0].closed)
        pool.call("p", "search", "{}", 1_000)
        assertEquals(2, runtimes.size)
        assertEquals(2, calls)
    }

    /**
     * F5/beginCall() controller ruling: the pool must call that plugin's `PluginHttp.beginCall()`
     * exactly once at the start of every top-level capability call, inside the per-plugin mutex, so
     * the 60-request-per-call budget resets between calls instead of accumulating across a runtime's
     * whole lifetime. Exercises the real [PluginHttp] (not a fake counter) against a MockWebServer:
     * a [FakeRuntime] that makes 60 real `PluginHttp.fetch` calls per `pool.call()` would blow the
     * budget on the very first request of the second call if `beginCall()` weren't wired as
     * `beforeCall`.
     */
    @Test fun `beforeCall wired to PluginHttp beginCall resets the request budget between calls`() = runTest {
        repeat(120) { server.enqueue(MockResponse().setBody("x")) }
        val http = PluginHttp(OkHttpClient(), "p", EffectiveHosts(listOf("localhost")), "1.0", allowInsecureLocalhost = true)
        val url = "http://localhost:${server.port}/n"
        val pool = PluginRuntimePool(
            open = { FakeRuntime { repeat(60) { http.fetch(PluginHttp.Request(url)) }; "ok" } },
            onUnresponsive = {},
            scope = backgroundScope,
            beforeCall = { http.beginCall() },
        )
        assertEquals("ok", pool.call("p", "search", "{}", 5_000))
        // Without the reset, this second call's first fetch would be request #61 of an unreset
        // budget and PluginHttp would throw "demasiadas solicitudes en una sola llamada".
        assertEquals("ok", pool.call("p", "search", "{}", 5_000))
        assertEquals(120, server.requestCount)
    }
}
