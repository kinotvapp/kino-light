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

    @Test fun `a card below a tall target is pulled up above the navigation bar`() {
        // Redmi 1080x2400, 3-button bar (~130 px): the library put the card at 2160..2470 for the picker's grid.
        assertEquals(2270 - 2470, miniGuideCardShift(top = 2160, bottom = 2470, minTop = 80, maxBottom = 2270))
    }

    @Test fun `a card already inside the visible band stays where the library put it`() {
        assertEquals(0, miniGuideCardShift(top = 600, bottom = 900, minTop = 80, maxBottom = 2270))
    }

    @Test fun `a card taller than the band keeps its title on screen`() {
        assertEquals(80 - 100, miniGuideCardShift(top = 100, bottom = 2500, minTop = 80, maxBottom = 2270))
    }
}
