package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareChannelsTest {

    @Test
    fun `a channel that was added is remembered and adding it again says it was not new`() {
        val c = SoftwareChannels()
        assertFalse(c.contains("live:a"))
        assertTrue(c.add("live:a"))
        assertTrue(c.contains("live:a"))
        assertFalse(c.add("live:a"))
    }

    @Test
    fun `it survives being written down and read back`() {
        val c = SoftwareChannels()
        c.add("live:a"); c.add("live:b")
        val again = SoftwareChannels.decode(c.encode())
        assertTrue(again.contains("live:a") && again.contains("live:b"))
    }

    @Test
    fun `garbage or nothing on disk reads as empty`() {
        assertFalse(SoftwareChannels.decode(null).contains("x"))
        assertFalse(SoftwareChannels.decode("").contains("x"))
        assertFalse(SoftwareChannels.decode("\n\n").contains(""))
    }

    @Test
    fun `when full the oldest channel drops out`() {
        val c = SoftwareChannels(max = 2)
        c.add("a"); c.add("b"); c.add("c")
        assertFalse(c.contains("a"))
        assertTrue(c.contains("b") && c.contains("c"))
        assertEquals("b\nc", c.encode())
    }

    @Test
    fun `an over-long stored list is trimmed to the newest`() {
        val c = SoftwareChannels(max = 2, initial = listOf("a", "b", "c"))
        assertEquals("b\nc", c.encode())
    }
}
