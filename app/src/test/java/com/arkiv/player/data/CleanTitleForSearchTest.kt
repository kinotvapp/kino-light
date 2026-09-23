package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The title sent to TMDB to resolve the artwork. The real bug: "Naruto — Pack" didn't match
 * anything (the em dash `—` wasn't normalized and "Pack" isn't part of the title), so those items
 * ended up with no `tmdbId` and didn't get grouped with the rest of the series.
 */
class CleanTitleForSearchTest {

    @Test
    fun `strips the pack suffix with an em dash`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto — Pack"))
        assertEquals("Los Simpson", cleanTitleForSearch("Los Simpson — Pack"))
    }

    @Test
    fun `doesn't touch a title that's already clean`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto"))
        assertEquals("DAN DA DAN", cleanTitleForSearch("DAN DA DAN"))
    }

    /** An em dash in the middle of the title isn't a pack suffix: the back part doesn't get cut. */
    @Test
    fun `an em dash that isn't a pack suffix is kept as a separator`() {
        assertEquals("Naruto Shippuden", cleanTitleForSearch("Naruto — Shippuden"))
    }

    /**
     * Extra pin (not requested by the plan, added during self-review): "Pack" with no dash in
     * front of it is NOT the suffix the app adds, so it must not get trimmed. Without this case, a
     * regex that strips "Pack" from the end without requiring the dash would still pass the three
     * tests above.
     */
    @Test
    fun `"pack" with no dash before it isn't trimmed`() {
        assertEquals("Naruto Pack", cleanTitleForSearch("Naruto Pack"))
    }
}
