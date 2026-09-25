package com.arkiv.player.ui.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone Home's plugin opener: a double tap must not start playback (and navigate) twice. */
@OptIn(ExperimentalCoroutinesApi::class)
class SinglePlaybackTest {
    @Test fun `a second start while the first is preparing is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val gate = SinglePlayback(this)
        val release = CompletableDeferred<Unit>()
        var runs = 0
        assertTrue(gate.start { runs++; release.await() })
        assertFalse(gate.start { runs++ })
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runs)
        assertTrue(gate.start { runs++ })
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test fun `a failed start frees the guard`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val gate = SinglePlayback(scope)
        assertTrue(gate.start { throw IllegalStateException("boom") })
        var ran = false
        assertTrue(gate.start { ran = true })
        assertTrue(ran)
    }
}
