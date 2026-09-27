package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one reopen policy Magis live and a plugin's live channel share. See [LiveReopenPolicy]. */
class LiveReopenPolicyTest {

    @Test fun `three reopens, waits of 2, 4 and 8 seconds, five seconds of picture to recover`() {
        assertEquals(3, LiveReopenPolicy.MAX_REOPENS)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), (1..3).map { LiveReopenPolicy.waitMs(it) })
        assertEquals(5_000L, LiveReopenPolicy.MIN_HEALTHY_MS)
    }

    @Test fun `a nonsensical attempt number never underflows the wait`() {
        assertEquals(2_000L, LiveReopenPolicy.waitMs(0))
        assertEquals(2_000L, LiveReopenPolicy.waitMs(-3))
    }
}
