package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPluginSearchTest {
    @Test fun `with no query and the field closed only the button shows`() {
        assertEquals(TvSearchMode.COLLAPSED, tvSearchMode(expanded = false, query = ""))
        assertEquals(TvSearchMode.COLLAPSED, tvSearchMode(expanded = false, query = "   "))
        assertEquals("Buscar plugins", tvSearchButtonLabel(""))
    }

    @Test fun `OK on the button opens the field with the query it had`() {
        assertEquals(TvSearchStep(expanded = true, query = "", focusButton = false), tvSearchStep("", TvSearchEvent.OPEN))
        assertEquals(TvSearchStep(expanded = true, query = "xuper", focusButton = false), tvSearchStep("xuper", TvSearchEvent.OPEN))
        assertEquals(TvSearchMode.EXPANDED, tvSearchMode(expanded = true, query = "xuper"))
    }

    @Test fun `Back closes the field, keeps the filter and puts focus on the button`() {
        val step = tvSearchStep("xuper", TvSearchEvent.BACK)
        assertEquals(TvSearchStep(expanded = false, query = "xuper", focusButton = true), step)
        assertEquals(TvSearchMode.FILTERED, tvSearchMode(step.expanded, step.query))
        assertEquals("Buscar: xuper", tvSearchButtonLabel(step.query))
    }

    @Test fun `leaving the field by the D-pad closes it and leaves focus where it went`() {
        assertEquals(TvSearchStep(expanded = false, query = "xu", focusButton = false), tvSearchStep("xu", TvSearchEvent.FIELD_LEFT))
        // Cleared and moved away: back to the plain button.
        val cleared = tvSearchStep("", TvSearchEvent.FIELD_LEFT)
        assertEquals(TvSearchMode.COLLAPSED, tvSearchMode(cleared.expanded, cleared.query))
    }

    @Test fun `Quitar drops the filter and puts focus on the button`() {
        val step = tvSearchStep("xuper", TvSearchEvent.CLEAR)
        assertEquals(TvSearchStep(expanded = false, query = "", focusButton = true), step)
        assertEquals(TvSearchMode.COLLAPSED, tvSearchMode(step.expanded, step.query))
    }

    @Test fun `a long query is cut on the button`() {
        assertEquals("Buscar: canales en vivo…", tvSearchButtonLabel("canales en vivo de colombia"))
        assertEquals("Buscar: xuper", tvSearchButtonLabel("  xuper "))
    }
}
