package com.arkiv.player.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two guards that prevent saving an unusable frame. They matter because a capture OVERWRITES:
 * a bad frame doesn't get added alongside a good one, it replaces it.
 */
class FrameGuardsTest {

    private fun filled(color: Int, count: Int = 100) = IntArray(count) { color }

    /** The first 60 s are distributor logos and black screens. */
    @Test
    fun `does not capture before the position floor`() {
        assertFalse(FrameGuards.positionQualifies(0))
        assertFalse(FrameGuards.positionQualifies(59_999))
        assertTrue(FrameGuards.positionQualifies(60_000))
        assertTrue(FrameGuards.positionQualifies(15 * 60_000))
    }

    @Test
    fun `pure black luminance is zero and pure white is 255`() {
        assertEquals(0, FrameGuards.averageLuminance(filled(0xFF000000.toInt())))
        assertEquals(255, FrameGuards.averageLuminance(filled(0xFFFFFFFF.toInt())))
    }

    /** Pausing on a fade would leave the card black, overwriting the previous good frame. */
    @Test
    fun `a black frame is discarded`() {
        assertFalse(FrameGuards.isNotNearBlack(filled(0xFF000000.toInt())))
    }

    /** Almost black but not quite: a real night scene has to pass. */
    @Test
    fun `right at the threshold it's accepted, right below it's discarded`() {
        assertTrue(FrameGuards.isNotNearBlack(filled(0xFF0A0A0A.toInt())))   // luminance 10
        assertFalse(FrameGuards.isNotNearBlack(filled(0xFF080808.toInt())))  // luminance 8
    }

    /** A dark scene with a bright spot (a lamp, a subtitle) averages above the threshold. */
    @Test
    fun `a dark scene with some light is accepted`() {
        val pixels = IntArray(100) { i -> if (i < 90) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertTrue(FrameGuards.isNotNearBlack(pixels))
    }

    /** With no pixels there's nothing to judge: discarded instead of dividing by zero. */
    @Test
    fun `an empty array is discarded`() {
        assertFalse(FrameGuards.isNotNearBlack(IntArray(0)))
    }
}
