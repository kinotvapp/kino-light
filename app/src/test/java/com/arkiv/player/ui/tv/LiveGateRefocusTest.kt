package com.arkiv.player.ui.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV Home: when the Xuper live gate removes the focused "En vivo"/"Xuper" button or channel card,
 * Compose clears focus and the D-pad strands -- focus must go back to the top bar then, and only then.
 */
class LiveGateRefocusTest {
    @Test fun `the gate closing with no focus left on the screen refocuses the bar`() {
        assertTrue(liveGateNeedsRefocus(wasOn = true, isOn = false, screenHasFocus = false))
    }

    @Test fun `the gate closing while focus is elsewhere on the screen leaves it alone`() {
        assertFalse(liveGateNeedsRefocus(wasOn = true, isOn = false, screenHasFocus = true))
    }

    @Test fun `no transition to closed, no refocus`() {
        assertFalse(liveGateNeedsRefocus(wasOn = false, isOn = false, screenHasFocus = false))
        assertFalse(liveGateNeedsRefocus(wasOn = false, isOn = true, screenHasFocus = false))
        assertFalse(liveGateNeedsRefocus(wasOn = true, isOn = true, screenHasFocus = false))
    }

    /**
     * The live module adds two more surfaces that can vanish under focus: the "En vivo" button (the
     * module emptied) and the channels row (its last channel's provider went, while another stays).
     */
    @Test fun `any live surface vanishing with no focus left refocuses the bar`() {
        val all = HomeLiveSurfaces(xuperButton = true, liveButton = true, channelsRow = true)
        assertTrue(homeLiveNeedsRefocus(all, all.copy(liveButton = false), screenHasFocus = false))
        assertTrue(homeLiveNeedsRefocus(all, all.copy(channelsRow = false), screenHasFocus = false))
        assertTrue(homeLiveNeedsRefocus(all, all.copy(xuperButton = false), screenHasFocus = false))
    }

    @Test fun `a live surface vanishing while focus is elsewhere, or appearing, leaves focus alone`() {
        val all = HomeLiveSurfaces(xuperButton = true, liveButton = true, channelsRow = true)
        val none = HomeLiveSurfaces(xuperButton = false, liveButton = false, channelsRow = false)
        assertFalse(homeLiveNeedsRefocus(all, none, screenHasFocus = true))
        assertFalse(homeLiveNeedsRefocus(none, all, screenHasFocus = false))
        assertFalse(homeLiveNeedsRefocus(all, all, screenHasFocus = false))
    }
}
