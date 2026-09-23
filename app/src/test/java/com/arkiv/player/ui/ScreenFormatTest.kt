package com.arkiv.player.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFormatTest {

    @Test
    fun `a landscape tablet is a landscape tablet`() {
        assertTrue(isLandscapeTablet(800, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `a portrait tablet is not`() {
        assertFalse(isLandscapeTablet(800, Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `a large phone in landscape is NOT a tablet`() {
        // A Galaxy S24+ lying flat measures 1040dp WIDE, but its smallest side is 480dp.
        // That's why the rule looks at the smallest side: if it looked at the current width,
        // the phone would get the tablet layout every time the user rotates it.
        assertFalse(isLandscapeTablet(480, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `the threshold is 600dp`() {
        assertFalse(isLandscapeTablet(599, Configuration.ORIENTATION_LANDSCAPE))
        assertTrue(isLandscapeTablet(600, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `the grid doubles columns in wide mode`() {
        assertEquals(6, gridColumns(base = 3, isWide = true))
        assertEquals(4, gridColumns(base = 2, isWide = true))
    }

    @Test
    fun `the phone grid doesn't change`() {
        assertEquals(3, gridColumns(base = 3, isWide = false))
        assertEquals(2, gridColumns(base = 2, isWide = false))
    }
}
