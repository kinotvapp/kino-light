package com.arkiv.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveRetryTest {
    @Test fun `a failed resolve is tried again, a little later each time, then given up`() {
        assertEquals(listOf(700L, 1400L, 2100L, 2800L, 3500L), (1..5).map { resolveRetryDelayMs(it) })
        assertNull(resolveRetryDelayMs(6))
    }
}
