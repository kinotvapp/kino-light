package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While casting, the bar shows the RECEIVER. Measured 2026-10-01: the paused Magis player under
 * the cast rewrote its own 1:47 into the mirror every tick, so the phone's bar sat at 1:47 for a
 * whole cast whose receiver was at 2:56.
 */
class PlayerMirrorTest {

    @Test
    fun `without a cast every player writes the clock as before`() {
        val mirror = PlayerMirror()
        mirror.readClock(107_000L, 7_667_000L)
        assertEquals(107_000L, mirror.positionMs)
        mirror.syncTransport(buffering = false, playing = true, wantsToPlay = true)
        assertTrue(mirror.playing)
    }

    @Test
    fun `while casting the paused local player cannot overwrite the receiver's position`() {
        val mirror = PlayerMirror()
        mirror.remoteActive = true
        // The screen, reading the CastPlayer:
        mirror.readClock(176_152L, 7_667_166L, authoritative = true)
        // The local Magis player's watchdog loop, a moment later:
        mirror.readClock(107_266L, 7_667_166L)
        assertEquals(176_152L, mirror.positionMs)
        // A seek from the phone shows at once and keeps showing.
        mirror.jumpTo(600_000L)
        mirror.readClock(107_266L, 7_667_166L)
        assertEquals(600_000L, mirror.positionMs)
        mirror.readClock(601_000L, 7_667_166L, authoritative = true)
        assertEquals(601_000L, mirror.positionMs)
    }

    @Test
    fun `while casting the local player's pause and buffering do not reach the controls`() {
        val mirror = PlayerMirror()
        mirror.remoteActive = true
        mirror.syncTransport(buffering = false, playing = true, wantsToPlay = true, authoritative = true)
        mirror.updatePlaying(false)
        mirror.updateWantsToPlay(false)
        mirror.updateBuffering(true)
        mirror.syncTransport(buffering = true, playing = false, wantsToPlay = false)
        mirror.resetClock()
        assertTrue(mirror.playing)
        assertTrue(mirror.wantsToPlay)
        assertFalse(mirror.buffering)
        mirror.updateBuffering(true, authoritative = true)
        assertTrue(mirror.buffering)
    }

    @Test
    fun `after the cast the local player owns the clock again`() {
        val mirror = PlayerMirror()
        mirror.remoteActive = true
        mirror.readClock(176_152L, 7_667_166L, authoritative = true)
        mirror.remoteActive = false
        mirror.readClock(176_500L, 7_667_166L)
        assertEquals(176_500L, mirror.positionMs)
    }
}
