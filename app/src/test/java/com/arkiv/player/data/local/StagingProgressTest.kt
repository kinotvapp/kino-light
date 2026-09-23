package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StagingProgressTest {

    @Test
    fun `staging occupies the first half`() {
        assertEquals(0f, StagingProgress.fromStaging(0f), 0.001f)
        assertEquals(0.25f, StagingProgress.fromStaging(0.5f), 0.001f)
        assertEquals(0.5f, StagingProgress.fromStaging(1f), 0.001f)
    }

    @Test
    fun `the transfer occupies the second half`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(0, 1000), 0.001f)
        assertEquals(0.75f, StagingProgress.fromTransfer(500, 1000), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(1000, 1000), 0.001f)
    }

    @Test
    fun `with no known total the transfer stays at the midpoint`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(400, 0), 0.001f)
    }

    @Test
    fun `never goes past the limits`() {
        assertEquals(0.5f, StagingProgress.fromStaging(2f), 0.001f)
        assertEquals(0f, StagingProgress.fromStaging(-1f), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(2000, 1000), 0.001f)
    }
}
