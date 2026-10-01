package com.arkiv.player.playback

import com.arkiv.player.playback.RemuxPacing.MAX_LEAD_SEC
import com.arkiv.player.playback.RemuxPacing.RESUME_LEAD_SEC
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemuxPacingTest {

    private fun pause(anchor: Double?, main: Double, playable: Double = main, headEnd: Double = 0.0, paused: Boolean = false) =
        RemuxPacing.shouldPause(anchor, main, playable, headEnd, paused)

    @Test
    fun `nothing being cast yet - the remux races to the person's position`() {
        assertFalse(pause(anchor = null, main = 5_000.0))
    }

    @Test
    fun `below the lead it keeps going, at the lead it pauses`() {
        assertFalse(pause(anchor = 600.0, main = 600.0 + MAX_LEAD_SEC - 1))
        assertTrue(pause(anchor = 600.0, main = 600.0 + MAX_LEAD_SEC))
    }

    @Test
    fun `paused, it stays paused until the TV has eaten into the lead`() {
        val main = 1_000.0
        assertTrue(pause(anchor = main - RESUME_LEAD_SEC - 5, main = main, paused = true))
        assertFalse(pause(anchor = main - RESUME_LEAD_SEC + 1, main = main, paused = true))
        // Running, that same lead is not enough to stop it: no flapping around one value.
        assertFalse(pause(anchor = main - RESUME_LEAD_SEC - 5, main = main, paused = false))
    }

    @Test
    fun `the TV's position is what counts, not where the remux started`() {
        // The TV moved on to 1500 s: a remux at 1600 s is only 100 s ahead and must work.
        assertFalse(pause(anchor = 1_500.0, main = 1_600.0))
    }

    @Test
    fun `behind a reused head with plenty of time, it rests`() {
        // Head to 1630 s, the TV at 100 s, this run at 1200 s: 430 s of work at 3x = 143 s,
        // and the TV has 1350 s of head left -- no hurry.
        assertTrue(pause(anchor = 100.0, main = 1_200.0, playable = 1_630.0, headEnd = 1_630.0))
    }

    @Test
    fun `behind a reused head with the TV getting close, it works even with a big lead`() {
        // Head to 1630 s, the TV at 642 s, this run just started: 1630 s of work at 3x = 543 s,
        // the TV reaches the head's end (less the lead) in 808 s -- fine for now...
        assertTrue(pause(anchor = 642.0, main = 0.0, playable = 1_630.0, headEnd = 1_630.0))
        // ...but at 1000 s it has only 450 s left: work.
        assertFalse(pause(anchor = 1_000.0, main = 0.0, playable = 1_630.0, headEnd = 1_630.0))
    }

    @Test
    fun `past the head's end it is paced like any remux`() {
        assertTrue(pause(anchor = 1_500.0, main = 1_700.0, playable = 1_700.0, headEnd = 1_630.0))
    }
}
