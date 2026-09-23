package com.arkiv.player.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMemoryTest {

    private class InMemoryStore : MemoryStore {
        var json: String? = null
        override fun read() = json
        override fun save(json: String) { this.json = json }
    }

    private var now = 1_000_000L
    private val store = InMemoryStore()
    private fun memory() = ModelMemory(store) { now }
    private val a = KiloModel("a")
    private val b = KiloModel("b")
    private val c = KiloModel("c")

    @Test fun `with no history the catalog's order is kept`() {
        assertEquals(listOf(a, b, c), memory().order(listOf(a, b, c)))
    }

    @Test fun `the one that answered well moves up`() {
        val m = memory()
        m.success("c")
        assertEquals(c, m.order(listOf(a, b, c)).first())
    }

    @Test fun `the one that failed moves down`() {
        val m = memory()
        m.failure("a", Failure.Server)
        now += 6 * 60 * 1000L // its 5-minute wait already passed
        assertEquals(a, m.order(listOf(a, b, c)).last())
    }

    @Test fun `a 429 with no Retry-After waits 10 minutes`() {
        val m = memory()
        m.failure("a", Failure.RateLimited(retryAfterMs = null))
        now += 9 * 60 * 1000L
        assertEquals(listOf(b, c), m.order(listOf(a, b, c)))
        now += 2 * 60 * 1000L
        assertTrue(a in m.order(listOf(a, b, c)))
    }

    @Test fun `a 429 with Retry-After waits what it says`() {
        val m = memory()
        m.failure("a", Failure.RateLimited(retryAfterMs = 30_000L))
        now += 29_000L
        assertEquals(listOf(b, c), m.order(listOf(a, b, c)))
        now += 2_000L
        assertTrue(a in m.order(listOf(a, b, c)))
    }

    @Test fun `a 5xx waits 5 minutes`() {
        val m = memory()
        m.failure("a", Failure.Server)
        now += 4 * 60 * 1000L
        assertEquals(listOf(b, c), m.order(listOf(a, b, c)))
        now += 2 * 60 * 1000L
        assertTrue(a in m.order(listOf(a, b, c)))
    }

    /** A client-side failure can't rule out a model (lesson from llm-libre). */
    @Test fun `an unreadable answer does not count against it`() {
        val m = memory()
        m.failure("a", Failure.Unreadable)
        assertEquals(listOf(a, b, c), m.order(listOf(a, b, c)))
    }

    @Test fun `everything on hold gives empty`() {
        val m = memory()
        listOf("a", "b").forEach { m.failure(it, Failure.Server) }
        assertTrue(m.order(listOf(a, b)).isEmpty())
    }

    @Test fun `memory survives across sessions`() {
        memory().success("c")
        assertEquals(c, memory().order(listOf(a, b, c)).first())
    }

    @Test fun `a broken store does not bring anything down`() {
        store.json = "{this is not json"
        assertEquals(listOf(a, b), memory().order(listOf(a, b)))
    }
}
