package com.arkiv.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCircleTest {

    private val eps = 0.01f

    @Test
    fun `the TV circle, its gap and the name add up to exactly the row's card height`() {
        val spec = channelCircleForRow(rowHeightDp = 92f, nameHeightDp = 18f, focusScale = 1.08f, ringDp = 3f)
        assertEquals(92f, spec.diameterDp + spec.nameGapDp + 18f, eps)
    }

    @Test
    fun `the zoomed circle and its ring never reach the name below`() {
        val spec = channelCircleForRow(rowHeightDp = 92f, nameHeightDp = 18f, focusScale = 1.08f, ringDp = 3f)
        val growthBelow = spec.diameterDp * (1.08f - 1f) / 2f + 3f
        assertTrue(spec.nameGapDp >= growthBelow - eps)
    }

    @Test
    fun `without zoom the gap is just the ring`() {
        val spec = channelCircleForRow(rowHeightDp = 92f, nameHeightDp = 18f, focusScale = 1f, ringDp = 3f)
        assertEquals(3f, spec.nameGapDp, eps)
        assertEquals(71f, spec.diameterDp, eps)
    }

    @Test
    fun `the logo sits inside the circle with room around it`() {
        val spec = channelCircle(diameterDp = 72f)
        assertEquals(72f * CHANNEL_LOGO_INSET, spec.logoPaddingDp, eps)
        assertTrue(CHANNEL_LOGO_INSET >= 0.16f && CHANNEL_LOGO_INSET <= 0.18f)
        // A wide logo fitted into the inner square stays inside the circle: the square's corners
        // are at radius (d/2 - p) * sqrt(2), which must not pass d/2.
        val half = 72f / 2f - spec.logoPaddingDp
        assertTrue(half * Math.sqrt(2.0).toFloat() <= 72f / 2f + eps)
    }

    @Test
    fun `the item is wider than the circle so the name and the badge have room`() {
        val spec = channelCircle(diameterDp = 72f)
        assertTrue(spec.itemWidthDp > spec.diameterDp)
        assertEquals(72f * CHANNEL_ITEM_WIDTH_RATIO, spec.itemWidthDp, eps)
    }
}
