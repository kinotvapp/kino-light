package com.arkiv.player.ui.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MagisHomeRefreshTest {

    private val row = MagisHomeRow(id = "magis_g_series_drama", title = "Drama · Series", shown = emptyList(), all = emptyList())
    private val row2 = MagisHomeRow(id = "magis_new_peliculas", title = "Estrenos · Películas", shown = emptyList(), all = emptyList())
    private val complete = MagisHome(rows = listOf(row), missing = emptySet())
    private val complete2 = MagisHome(rows = listOf(row2), missing = emptySet())
    private val partial = MagisHome(rows = emptyList(), missing = setOf(MagisKind.PELICULAS))
    private val empty = MagisHome(rows = emptyList(), missing = setOf(MagisKind.PELICULAS))

    /** No persisted snapshot: exercises the fetch-first path, as on a first ever launch. */
    private val noCache: suspend () -> CachedRows? = { null }

    /** Maps the legacy reconnect/resume signal onto the flow's typed signal. */
    private fun retry(retry: kotlinx.coroutines.flow.Flow<Unit>) = retry.map { Refetch.IfMissing }

    @Test
    fun `only a pass that left a root out is worth another trip`() {
        assertTrue(shouldRefetch(partial))
        assertFalse(shouldRefetch(complete))
    }

    @Test
    fun `fetches once when collected and not again without a signal`() = runTest {
        var fetches = 0
        val emitted = magisHomeRows(cached = noCache, fetch = { fetches++; partial }, signals = emptyFlow()).toList()

        assertEquals(1, fetches)
        assertEquals(listOf(emptyList<MagisHomeRow>()), emitted)
    }

    @Test
    fun `a signal fetches again while a root is missing`() = runTest {
        val results = ArrayDeque(listOf(partial, complete))
        val emitted = magisHomeRows(cached = noCache, fetch = { results.removeFirst() }, signals = flowOf(Refetch.IfMissing)).toList()

        assertEquals(listOf(emptyList(), listOf(row)), emitted)
    }

    @Test
    fun `signals do nothing once the home is complete`() = runTest {
        var fetches = 0
        magisHomeRows(
            cached = noCache,
            fetch = { fetches++; complete },
            signals = flow { repeat(2) { delay(1_000); emit(Refetch.IfMissing) } },
        ).toList()

        assertEquals(1, fetches)
    }

    @Test
    fun `each later signal tries again until the home is complete`() = runTest {
        val results = ArrayDeque(listOf(partial, partial, complete))
        var fetches = 0
        magisHomeRows(
            cached = noCache,
            fetch = { fetches++; results.removeFirst() },
            signals = flow { repeat(3) { delay(1_000); emit(Refetch.IfMissing) } },
        ).toList()

        assertEquals(3, fetches)
    }

    @Test
    fun `connectivity that comes back mid-fetch still triggers a retry`() = runTest {
        val online = MutableStateFlow(false)
        val results = ArrayDeque(listOf(partial, complete))
        val emitted = mutableListOf<List<MagisHomeRow>>()
        val collecting = launch {
            magisHomeRows(
                cached = noCache,
                fetch = { delay(1_000); results.removeFirst() },
                signals = retry(online.reconnections()),
            ).collect { emitted += it }
        }

        advanceTimeBy(500) // the first pass is still waiting on the portal
        online.value = true
        advanceUntilIdle()
        collecting.cancel()

        assertEquals(listOf(emptyList(), listOf(row)), emitted)
    }

    @Test
    fun `paints the cached snapshot first, then refreshes it when stale`() = runTest {
        var fetches = 0
        val snapshot = CachedRows(rows = listOf(row), fetchedAt = 0)
        val emitted = magisHomeRows(
            cached = { snapshot },
            fetch = { fetches++; complete2 },
            signals = emptyFlow(),
            ttlMs = 1_000,
            now = { 5_000 }, // 5000 - 0 >= 1000 -> stale
        ).toList()

        assertEquals(1, fetches)
        assertEquals(listOf(listOf(row), listOf(row2)), emitted)
    }

    @Test
    fun `a fresh cached snapshot is served without hitting the portal`() = runTest {
        var fetches = 0
        val snapshot = CachedRows(rows = listOf(row), fetchedAt = 4_000)
        val emitted = magisHomeRows(
            cached = { snapshot },
            fetch = { fetches++; complete2 },
            signals = emptyFlow(),
            ttlMs = 1_000,
            now = { 4_500 }, // 4500 - 4000 < 1000 -> fresh
        ).toList()

        assertEquals(0, fetches)
        assertEquals(listOf(listOf(row)), emitted)
    }

    @Test
    fun `a manual reload refetches even over a fresh cache`() = runTest {
        var fetches = 0
        val snapshot = CachedRows(rows = listOf(row), fetchedAt = 4_000)
        val emitted = magisHomeRows(
            cached = { snapshot },
            fetch = { fetches++; complete2 },
            signals = flowOf(Refetch.Force),
            ttlMs = 1_000,
            now = { 4_500 }, // fresh, so only Force triggers the fetch
        ).toList()

        assertEquals(1, fetches)
        assertEquals(listOf(listOf(row), listOf(row2)), emitted)
    }

    @Test
    fun `an empty refetch never overwrites the cache on screen`() = runTest {
        val snapshot = CachedRows(rows = listOf(row), fetchedAt = 0)
        val emitted = magisHomeRows(
            cached = { snapshot },
            fetch = { empty },
            signals = flowOf(Refetch.Force),
            ttlMs = 1_000,
            now = { 5_000 }, // stale: refreshes on subscribe AND on the Force pulse, both empty
        ).toList()

        assertEquals(listOf(listOf(row)), emitted)
    }

    @Test
    fun `a reconnection is connectivity going from false to true`() = runTest {
        assertEquals(2, flowOf(true, false, true, true, false, true).reconnections().count())
    }

    @Test
    fun `starting offline and coming online is a reconnection`() = runTest {
        assertEquals(1, flowOf(false, true).reconnections().count())
    }

    @Test
    fun `starting online is not a reconnection`() = runTest {
        assertEquals(0, flowOf(true).reconnections().count())
    }
}
