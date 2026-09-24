package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsPolicyTest {

    private val budget = 16.7f

    private fun frames(fast: Int, slow: Int, fastMs: Float = 10f, slowMs: Float = 40f): List<Float> =
        List(fast) { fastMs } + List(slow) { slowMs }

    // --- static hint --------------------------------------------------------

    @Test
    fun `every TV box and stick seen in the field is hinted as low-end`() {
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 901, isLowRamDevice = false))   // Fire TV Stick Lite
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 1448, isLowRamDevice = false))  // onn. Full HD
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 1670, isLowRamDevice = false))  // 1.7 GB Fire TV sticks
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 1990, isLowRamDevice = false))  // MiTV / MiBox
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 2000, isLowRamDevice = false))  // Google_TV, the largest seen
    }

    @Test
    fun `no phone seen in the field is hinted, the smallest reports 2765 MB`() {
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 2765, isLowRamDevice = false)) // TECNO Mobile BG6
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 3714, isLowRamDevice = false))
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 7296, isLowRamDevice = false))
    }

    @Test
    fun `the threshold is exactly 2 GB, inclusive`() {
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 2048, isLowRamDevice = false))
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 2049, isLowRamDevice = false))
    }

    @Test
    fun `Android's own low-RAM flag is always a hint`() {
        assertTrue(EffectsPolicy.staticHint(totalRamMb = 4096, isLowRamDevice = true))
    }

    @Test
    fun `a device that reports nonsense RAM gets no hint, the measurement decides`() {
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 132_725, isLowRamDevice = false)) // a "TVBOX" that claims 129 GB
        assertFalse(EffectsPolicy.staticHint(totalRamMb = 0, isLowRamDevice = false))       // a "PROJECTOR" that claims none
    }

    // --- frame verdict ------------------------------------------------------

    @Test
    fun `a smooth device is not slow`() {
        assertEquals(false, EffectsPolicy.isSlow(frames(fast = 180, slow = 0), budget))
    }

    @Test
    fun `a device dropping a fifth of its frames is slow`() {
        assertEquals(true, EffectsPolicy.isSlow(frames(fast = 144, slow = 36), budget)) // exactly 20%
    }

    @Test
    fun `an occasional hiccup is not slow`() {
        assertEquals(false, EffectsPolicy.isSlow(frames(fast = 171, slow = 9), budget)) // 5%
    }

    @Test
    fun `a frame only a little over budget does not count as dropped`() {
        // 25 ms is over 16.7 but under 2x: a noticeable but not dropped frame, so it must not condemn a device.
        assertEquals(false, EffectsPolicy.isSlow(List(180) { 25f }, budget))
    }

    @Test
    fun `too few frames is no verdict at all`() {
        assertNull(EffectsPolicy.isSlow(frames(fast = 0, slow = 179), budget))
        assertNull(EffectsPolicy.isSlow(emptyList(), budget))
        assertNull(EffectsPolicy.isSlow(frames(fast = 180, slow = 0), budgetMs = 0f))
    }

    @Test
    fun `the budget follows the display, so a 30 Hz panel is judged against 33 ms not 16 ms`() {
        val frames = List(180) { 50f }
        assertEquals(true, EffectsPolicy.isSlow(frames, budgetMs = 1000f / 60f))  // 50 ms > 33 ms: dropped on 60 Hz
        assertEquals(false, EffectsPolicy.isSlow(frames, budgetMs = 1000f / 30f)) // 50 ms < 66 ms: fine on 30 Hz
    }

    @Test
    fun `half the frames dropped is severe, enough to turn the effects off on one launch`() {
        assertTrue(EffectsPolicy.isSevere(frames(fast = 90, slow = 90), budget)) // exactly 50%
        assertTrue(EffectsPolicy.isSevere(frames(fast = 0, slow = 180), budget))
    }

    @Test
    fun `a merely slow sample is not severe and still needs the second launch`() {
        val borderline = frames(fast = 144, slow = 36) // 20%: slow, not severe
        assertEquals(true, EffectsPolicy.isSlow(borderline, budget))
        assertFalse(EffectsPolicy.isSevere(borderline, budget))
        assertFalse(EffectsPolicy.isSevere(frames(fast = 91, slow = 89), budget)) // just under 50%
    }

    @Test
    fun `nothing to judge is never severe`() {
        assertFalse(EffectsPolicy.isSevere(emptyList(), budget))
        assertFalse(EffectsPolicy.isSevere(frames(fast = 0, slow = 180), budgetMs = 0f))
    }

    @Test
    fun `the dropped share is reported for telemetry`() {
        assertEquals(0.2f, EffectsPolicy.droppedShare(frames(fast = 144, slow = 36), budget), 0.0001f)
        assertEquals(0f, EffectsPolicy.droppedShare(emptyList(), budget), 0f)
    }

    // --- the effective answer ----------------------------------------------

    @Test
    fun `an explicit choice always beats every automatic signal`() {
        assertFalse(EffectsPolicy.resolve(EffectsMode.FULL, autoReduced = true, staticHint = true, systemAnimationsOff = true))
        assertTrue(EffectsPolicy.resolve(EffectsMode.REDUCED, autoReduced = false, staticHint = false, systemAnimationsOff = false))
    }

    @Test
    fun `in automatic mode any automatic signal turns the effects off`() {
        assertFalse(EffectsPolicy.resolve(EffectsMode.AUTO, autoReduced = false, staticHint = false, systemAnimationsOff = false))
        assertTrue(EffectsPolicy.resolve(EffectsMode.AUTO, autoReduced = true, staticHint = false, systemAnimationsOff = false))
        assertTrue(EffectsPolicy.resolve(EffectsMode.AUTO, autoReduced = false, staticHint = true, systemAnimationsOff = false))
        assertTrue(EffectsPolicy.resolve(EffectsMode.AUTO, autoReduced = false, staticHint = false, systemAnimationsOff = true))
    }

    // --- the mode itself ----------------------------------------------------

    @Test
    fun `the mode cycles automatic, full, reduced and back`() {
        assertEquals(EffectsMode.FULL, EffectsMode.AUTO.next())
        assertEquals(EffectsMode.REDUCED, EffectsMode.FULL.next())
        assertEquals(EffectsMode.AUTO, EffectsMode.REDUCED.next())
    }

    @Test
    fun `an unknown or missing saved mode falls back to automatic`() {
        assertEquals(EffectsMode.AUTO, EffectsMode.fromKey(null))
        assertEquals(EffectsMode.AUTO, EffectsMode.fromKey("garbage"))
        assertEquals(EffectsMode.REDUCED, EffectsMode.fromKey("reduced"))
    }
}
