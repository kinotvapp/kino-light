package com.arkiv.player.ui.plugin

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniGuideTest {

    @Test fun `every step but the last says Siguiente, the last says Entendido`() {
        assertEquals("Siguiente", miniGuideActionLabel(isLastStep = false))
        assertEquals("Entendido", miniGuideActionLabel(isLastStep = true))
    }

    @Test fun `the overlay window stops taking key focus so Back reaches the activity`() {
        val libraryFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val flags = miniGuideOverlayFlags(libraryFlags)
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(libraryFlags, flags and libraryFlags)
    }

    @Test fun `flags already non-focusable are left as they are`() {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        assertEquals(flags, miniGuideOverlayFlags(flags))
    }
}
