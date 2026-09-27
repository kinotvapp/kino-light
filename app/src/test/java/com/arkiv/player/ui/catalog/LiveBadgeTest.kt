package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "EN VIVO" badge a plugin's live channel wears on every card, phone and TV. */
class LiveBadgeTest {

    private fun result(kind: String) = GatewayResult(source = "plugin:demo", title = "T", ref = "plg1:demo:x", kind = kind)

    @Test fun `a live channel wears EN VIVO`() {
        assertEquals("EN VIVO", liveBadge(result("live")))
        assertEquals(LIVE_BADGE, liveBadge(result("live")))
        assertTrue(result("live").isLiveChannel())
    }

    @Test fun `a movie or series wears nothing here`() {
        assertNull(liveBadge(result("movie")))
        assertNull(liveBadge(result("series")))
        assertFalse(result("movie").isLiveChannel())
        // Not a prefix match: only the exact kind.
        assertNull(liveBadge(result("liveish")))
        assertNull(liveBadge(result("")))
    }
}
