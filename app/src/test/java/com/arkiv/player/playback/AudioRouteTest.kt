package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {
    @Test
    fun `names the outputs, sorted and without repeats`() {
        // 2 built-in speaker, 8 Bluetooth A2DP, 9 HDMI, 8 again
        assertEquals("bt_a2dp,hdmi,speaker", AudioRoute.describe(listOf(2, 8, 9, 8)))
    }

    @Test
    fun `an unknown type is kept as its number so it can still be looked up`() {
        assertEquals("speaker,type99", AudioRoute.describe(listOf(2, 99)))
    }

    @Test
    fun `no outputs says none`() {
        assertEquals("none", AudioRoute.describe(emptyList()))
    }

    @Test
    fun `bluetooth is recognised in all its forms`() {
        assertEquals(true, AudioRoute.hasBluetooth(listOf(2, 7)))
        assertEquals(true, AudioRoute.hasBluetooth(listOf(8)))
        assertEquals(true, AudioRoute.hasBluetooth(listOf(26)))
        assertEquals(false, AudioRoute.hasBluetooth(listOf(2, 9)))
    }
}
