package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelveTimeTest {

    @Test
    fun `the portal's timestamp is read as China time`() {
        // 2026-09-28 12:45:51 in UTC+8 is 04:45:51 UTC.
        assertEquals(1_790_570_751_000L, ShelveTime.parse("2026-09-28 12:45:51"))
    }

    @Test
    fun `blank, missing or unreadable is 0, never a guess`() {
        assertEquals(0L, ShelveTime.parse(null))
        assertEquals(0L, ShelveTime.parse(""))
        assertEquals(0L, ShelveTime.parse("   "))
        assertEquals(0L, ShelveTime.parse("yesterday"))
        assertEquals(0L, ShelveTime.parse("2026-09-28"))
    }

    @Test
    fun `an upload inside the window is new, right at its edge too`() {
        val shelved = 1_000_000_000_000L
        assertTrue(ShelveTime.isNew(shelved, nowMs = shelved))
        assertTrue(ShelveTime.isNew(shelved, nowMs = shelved + ShelveTime.NEW_WINDOW_MS))
    }

    @Test
    fun `an upload older than the window is not new`() {
        val shelved = 1_000_000_000_000L
        assertFalse(ShelveTime.isNew(shelved, nowMs = shelved + ShelveTime.NEW_WINDOW_MS + 1))
    }

    @Test
    fun `no date means no mark`() {
        assertFalse(ShelveTime.isNew(0L, nowMs = 5_000L))
    }

    @Test
    fun `the window is 48 hours`() {
        assertEquals(48L * 60 * 60 * 1000, ShelveTime.NEW_WINDOW_MS)
    }

    @Test
    fun `the badge is only in a plugin item's badges list`() {
        assertTrue(ShelveTime.hasNewBadge("HD|NUEVO"))
        assertTrue(ShelveTime.hasNewBadge("NUEVO"))
        assertFalse(ShelveTime.hasNewBadge("HD|Latino"))
        assertFalse(ShelveTime.hasNewBadge(null))
        assertFalse(ShelveTime.hasNewBadge("NUEVOS"))
    }
}
