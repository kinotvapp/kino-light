package com.arkiv.player.ui.tv

import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TvSettingsContentExitTest {
    // Found on the Fire TV: with the "Ajustes" title beside the chips, Up from Subtítulos' first row landed on
    // "Cuenta" (the chip above the middle of the row), not on the tab the person was on.
    @Test fun `Up out of a tab's content goes to the selected tab`() {
        assertEquals("selected", contentExitTarget(FocusDirection.Up, "selected", "default"))
    }

    @Test fun `every other key leaving the content takes its usual course`() {
        listOf(FocusDirection.Down, FocusDirection.Left, FocusDirection.Right).forEach {
            assertEquals("default", contentExitTarget(it, "selected", "default"))
        }
    }
}
