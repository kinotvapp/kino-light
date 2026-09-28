package com.arkiv.player.ui.home

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFreshnessTest {

    private val min = 60_000L

    @Test fun `resume reloads only once the last settled pass is older than the threshold`() {
        assertFalse(HomeFreshness.shouldReloadOnResume(lastPassAt = 1_000L, now = 1_000L + 29 * min))
        assertTrue(HomeFreshness.shouldReloadOnResume(lastPassAt = 1_000L, now = 1_000L + 30 * min))
        assertTrue(HomeFreshness.shouldReloadOnResume(lastPassAt = 1_000L, now = 1_000L + 600 * min))
    }

    @Test fun `resume never reloads while a pass is still in flight`() {
        assertFalse(HomeFreshness.shouldReloadOnResume(lastPassAt = null, now = 999 * min))
    }

    @Test fun `a clock that jumped backwards counts as stale`() {
        assertTrue(HomeFreshness.shouldReloadOnResume(lastPassAt = 100 * min, now = 10 * min))
    }

    @Test fun `a forced reload is accepted the first time and then only 60 s after the last accepted one`() {
        assertTrue(HomeFreshness.acceptForcedReload(lastForcedAt = null, now = 0L))
        assertFalse(HomeFreshness.acceptForcedReload(lastForcedAt = 5_000L, now = 5_000L + 59_999L))
        assertTrue(HomeFreshness.acceptForcedReload(lastForcedAt = 5_000L, now = 5_000L + 60_000L))
    }

    @Test fun `the gate lets one forced reload through per minute and ignores the presses in between`() {
        var now = 0L
        val gate = ForcedReloadGate { now }
        assertTrue(gate.tryAcquire())
        now = 30_000L
        assertFalse(gate.tryAcquire())
        now = 61_000L
        // Measured from the last ACCEPTED press (0), not the ignored one (30 s).
        assertTrue(gate.tryAcquire())
        now = 100_000L
        assertFalse(gate.tryAcquire())
    }

    @Test fun `back online fires only on an offline to online transition`() = runTest {
        val events = HomeFreshness.backOnline(flowOf(true, true, false, false, true, true, false, true)).toList()
        assertEquals(2, events.size)
    }

    @Test fun `starting online is not a reconnection`() = runTest {
        assertEquals(0, HomeFreshness.backOnline(flowOf(true)).toList().size)
    }
}
