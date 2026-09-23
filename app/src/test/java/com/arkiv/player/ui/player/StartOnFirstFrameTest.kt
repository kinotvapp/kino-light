package com.arkiv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That Caracol starts with the first frame: neither before (audio playing under the spinner) nor
 * never (mute and stuck if the frame never arrives).
 */
class StartOnFirstFrameTest {

    private val wait = 10_000L
    private val t0 = 1_000L

    private fun primed() = StartOnFirstFrame(maxWaitMs = wait).apply { start(t0) }

    @Test fun `primed and without a frame, it does not start early`() {
        val a = primed()
        assertTrue(a.waiting)
        assertFalse(a.expired(t0))
        assertFalse(a.expired(t0 + wait - 1))
        assertTrue(a.waiting)
    }

    @Test fun `the first frame starts it, only once`() {
        val a = primed()
        assertTrue(a.frameArrived())
        assertFalse(a.waiting)
        // Another first frame (after a seek or a re-prime) does not give play again.
        assertFalse(a.frameArrived())
        assertFalse(a.expired(t0 + wait * 3))
    }

    @Test fun `without a frame, the wait expires and it starts anyway`() {
        val a = primed()
        assertTrue(a.expired(t0 + wait))
        assertFalse(a.waiting)
        assertFalse(a.expired(t0 + wait + 500))
        // A frame that arrives late doesn't give it play again either.
        assertFalse(a.frameArrived())
    }

    /** A pause (or a play) from the person is never confused with the wait. */
    @Test fun `if the person decided while waiting, neither the frame nor expiring override it`() {
        val a = primed()
        a.personDecided()
        assertFalse(a.waiting)
        assertFalse(a.frameArrived())
        assertFalse(a.expired(t0 + wait * 3))
    }

    /** With the app in the background it doesn't start: neither the safety exit nor a frame give it play. */
    @Test fun `with the app in the background the wait is suspended`() {
        val a = primed()
        a.suspendWait()
        assertTrue(a.isSuspended)
        assertFalse(a.waiting)
        assertFalse(a.expired(t0 + wait * 3))
        assertFalse(a.frameArrived())
    }

    /** On return it never ends up waiting with no deadline: the wait continues, counted again from the return. */
    @Test fun `on return the wait resumes with its deadline`() {
        val a = primed()
        a.suspendWait()
        val back = t0 + wait * 5
        a.resume(back)
        assertTrue(a.waiting)
        assertFalse(a.expired(back + wait - 1))
        assertTrue(a.expired(back + wait))
    }

    @Test fun `if it had already started, leaving and returning changes nothing`() {
        val a = primed()
        assertTrue(a.frameArrived())
        a.suspendWait()
        a.resume(t0 + wait * 5)
        assertFalse(a.waiting)
        assertFalse(a.isSuspended)
        assertFalse(a.expired(t0 + wait * 10))
    }

    // --- the reload ---------------------------------------------------------------------------

    @Test fun `wanted to play if it was playing or was still starting`() {
        val starting = primed()
        assertTrue(starting.wantedToPlay(playWhenReady = false))
        starting.suspendWait()
        assertTrue(starting.wantedToPlay(playWhenReady = false))

        val playing = primed()
        assertTrue(playing.frameArrived())
        assertTrue(playing.wantedToPlay(playWhenReady = true))
    }

    /** The background case: it paused on exit, failed there, and the reload can't start on its own on return. */
    @Test fun `paused does not want to play`() {
        val pausedOnExit = primed()
        assertTrue(pausedOnExit.frameArrived())
        assertFalse(pausedOnExit.wantedToPlay(playWhenReady = false))

        val pausedByThePerson = primed()
        pausedByThePerson.personDecided()
        assertFalse(pausedByThePerson.wantedToPlay(playWhenReady = false))
    }

    @Test fun `a reloaded player that is paused does not start on its own`() {
        val a = StartOnFirstFrame(maxWaitMs = wait, autoStart = false).apply { start(t0) }
        assertFalse(a.waiting)
        assertFalse(a.frameArrived())
        assertFalse(a.expired(t0 + wait * 3))
        assertFalse(a.wantedToPlay(playWhenReady = false))
    }

    @Test fun `before priming there is no wait`() {
        val a = StartOnFirstFrame(maxWaitMs = wait)
        assertFalse(a.waiting)
        assertFalse(a.expired(t0 + wait * 3))
        assertFalse(a.frameArrived())
    }

    /** Without a cap, a device that doesn't paint while paused would leave the video mute and stuck forever. */
    @Test fun `the default wait has a cap`() {
        val a = StartOnFirstFrame().apply { start(0L) }
        assertTrue(a.expired(MAX_FIRST_FRAME_WAIT_MS))
    }
}
