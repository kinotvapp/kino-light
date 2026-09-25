package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveErrorRecoveryTest {

    @Test
    fun `the three playlist-level errors are fixed in place and everything else is not`() {
        assertTrue(LiveErrorKind.BEHIND_LIVE_WINDOW.recoverableInPlace)
        assertTrue(LiveErrorKind.PLAYLIST_RESET.recoverableInPlace)
        assertTrue(LiveErrorKind.PLAYLIST_STUCK.recoverableInPlace)
        assertFalse(LiveErrorKind.OTHER.recoverableInPlace)
    }

    @Test
    fun `three tries are allowed in a minute and the fourth is not`() {
        val b = InPlaceRecoveryBudget()
        assertTrue(b.tryConsume(0))
        assertTrue(b.tryConsume(10_000))
        assertTrue(b.tryConsume(20_000))
        assertFalse("a feed that keeps falling behind goes to the full reopen", b.tryConsume(30_000))
    }

    @Test
    fun `tries come back once the minute has passed`() {
        val b = InPlaceRecoveryBudget()
        repeat(3) { assertTrue(b.tryConsume(it * 1_000L)) }
        assertFalse(b.tryConsume(5_000))
        assertTrue("the oldest try is 61 s old", b.tryConsume(61_500))
    }

    @Test
    fun `a refused try does not use up the budget`() {
        val b = InPlaceRecoveryBudget(max = 1, windowMs = 1_000)
        assertTrue(b.tryConsume(0))
        assertFalse(b.tryConsume(500))
        assertFalse(b.tryConsume(900))
        assertTrue(b.tryConsume(1_100))
    }
}
