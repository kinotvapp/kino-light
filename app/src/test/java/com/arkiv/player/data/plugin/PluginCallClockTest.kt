package com.arkiv.player.data.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCallClockTest {
    @Test fun `a job that finishes inside the budget is awaited`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        val job = async { delay(400); "ok" }
        assertTrue(clock.awaitWithin(job))
        assertEquals(400, testScheduler.currentTime)
    }

    @Test fun `with no pause the budget runs out on time`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        val job = CompletableDeferred<String>()
        assertFalse(clock.awaitWithin(job))
        assertEquals(1_000, testScheduler.currentTime)
    }

    // The person spends 30 s on a dialog in the middle of a 1 s call: none of it counts.
    @Test fun `time spent paused does not count against the budget`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        val job = async {
            delay(600)
            clock.pausedWhile { delay(30_000) }
            delay(300)
            "ok"
        }
        assertTrue(clock.awaitWithin(job))
        assertEquals(30_900, testScheduler.currentTime)
    }

    // A plugin that keeps working after the person answered is still stopped: the budget resumes
    // where it paused.
    @Test fun `after a pause the rest of the budget still stops a stuck call`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        val stuck = CompletableDeferred<Unit>()
        launch {
            delay(600)
            clock.pausedWhile { delay(30_000) }
        }
        assertFalse(clock.awaitWithin(stuck))
        assertEquals(31_000, testScheduler.currentTime)
    }

    @Test fun `overlapping pauses resume only when the last one ends`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        val stuck = CompletableDeferred<Unit>()
        launch { clock.pausedWhile { delay(5_000) } }
        launch { delay(1_000); clock.pausedWhile { delay(10_000) } }
        assertFalse(clock.awaitWithin(stuck))
        // Paused 0..11 000 (the two pauses overlap), then the whole second of budget.
        assertEquals(12_000, testScheduler.currentTime)
    }

    @Test fun `remaining is null while paused`() = runTest {
        val clock = PluginCallClock(1_000) { testScheduler.currentTime }
        clock.pause()
        assertNull(clock.remainingMs())
        delay(5_000)
        clock.resume()
        assertEquals(1_000L, clock.remainingMs())
    }
}
