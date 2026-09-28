package com.arkiv.player.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOrientationTest {
    @Test
    fun `requests landscape when currently portrait`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, nextPlayerOrientation(isLandscape = false))
    }

    @Test
    fun `requests portrait when currently landscape`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, nextPlayerOrientation(isLandscape = true))
    }
}
