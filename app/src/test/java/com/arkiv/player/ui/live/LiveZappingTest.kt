package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZappingTest {
    private val list = listOf(
        LiveChannel("c1", "Uno", 1, null),
        LiveChannel("c2", "Dos", 2, null),
        LiveChannel("c3", "Tres", 3, null),
    )

    @Test
    fun `advances and wraps around at the end`() {
        val z = LiveZapping(list, 2)
        assertEquals("c1", z.next().code)
    }

    @Test
    fun `goes back and wraps around at the start`() {
        val z = LiveZapping(list, 0)
        assertEquals("c3", z.previous().code)
    }

    @Test
    fun `the neighbors are the one before and the one after`() {
        assertEquals(setOf("c1", "c3"), LiveZapping(list, 1).neighbors().map { it.code }.toSet())
    }

    @Test
    fun `with a single channel zapping doesn't move or fail`() {
        val z = LiveZapping(listOf(list[0]), 0)
        assertEquals("c1", z.next().code)
        assertEquals(emptyList<LiveChannel>(), z.neighbors())
    }
}
