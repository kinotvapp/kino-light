package com.arkiv.player.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualEffectsTest {

    @Test
    fun `with effects on every transition keeps its own animation`() {
        val full = tween<Float>(450)
        assertSame(full, effectSpec(reduced = false, full = full))
        val enter = fadeIn()
        val exit = fadeOut()
        assertSame(enter, effectEnter(reduced = false, full = enter))
        assertSame(exit, effectExit(reduced = false, full = exit))
    }

    @Test
    fun `with effects off every transition is instant`() {
        assertTrue(effectSpec(reduced = true, full = tween<Float>(450)) is SnapSpec)
        assertTrue(backdropFadeSpec(reduced = true) is SnapSpec)
        assertEquals(EnterTransition.None, effectEnter(reduced = true, full = fadeIn()))
        assertEquals(ExitTransition.None, effectExit(reduced = true, full = fadeOut()))
    }

    @Test
    fun `the card zoom is there with effects on and gone with them off`() {
        assertEquals(TV_CARD_FOCUS_SCALE, cardFocusZoom(reduced = false))
        assertEquals(1f, cardFocusZoom(reduced = true))
        assertNotEquals(1f, TV_CARD_FOCUS_SCALE)
    }
}
