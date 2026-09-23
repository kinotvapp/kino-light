package com.arkiv.player.ui.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val complete = MagisHome(rows = listOf(row), missing = emptySet())
    private val partial = MagisHome(rows = emptyList(), missing = setOf(MagisKind.PELICULAS))

    @Test
    fun `only a pass that left a root out is worth another trip`() {
        assertTrue(shouldRefetch(partial))
        assertFalse(shouldRefetch(complete))
    }

    @Test
    fun `fetches once when collected and not again without a signal`() = runTest {
        var fetches = 0
        val emitted = magisHomeRows(fetch = { fetches++; partial }, retry = emptyFlow()).toList()

        assertEquals(1, fetches)
        assertEquals(listOf(emptyList<MagisHomeRow>()), emitted)
    }

    @Test
    fun `a signal fetches again while a root is missing`() = runTest {
        val results = ArrayDeque(listOf(partial, complete))
        val emitted = magisHomeRows(fetch = { results.removeFirst() }, retry = flowOf(Unit)).toList()

        assertEquals(listOf(emptyList(), listOf(row)), emitted)
    }

    @Test
    fun `signals do nothing once the home is complete`() = runTest {
        var fetches = 0
        magisHomeRows(
            fetch = { fetches++; complete },
            retry = flow { repeat(2) { delay(1_000); emit(Unit) } },
        ).toList()

        assertEquals(1, fetches)
    }

    @Test
    fun `each later signal tries again until the home is complete`() = runTest {
        val results = ArrayDeque(listOf(partial, partial, complete))
        var fetches = 0
        magisHomeRows(
            fetch = { fetches++; results.removeFirst() },
            retry = flow { repeat(3) { delay(1_000); emit(Unit) } },
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
                fetch = { delay(1_000); results.removeFirst() },
                retry = online.reconnections(),
            ).collect { emitted += it }
        }

        advanceTimeBy(500) // the first pass is still waiting on the portal
        online.value = true
        advanceUntilIdle()
        collecting.cancel()

        assertEquals(listOf(emptyList(), listOf(row)), emitted)
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
