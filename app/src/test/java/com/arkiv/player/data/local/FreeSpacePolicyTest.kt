package com.arkiv.player.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpacePolicyTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `fits if there's space left over with margin`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 10 * gb, neededBytes = 2 * gb))
    }

    @Test
    fun `doesn't fit if the file is bigger than what's available`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 4 * gb))
    }

    /** Right at the edge doesn't fit either: leaving the system with zero free bytes breaks other things before it breaks Arkiv. */
    @Test
    fun `doesn't fit if it would fit but eats into the margin`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 2 * gb - 1))
    }

    @Test
    fun `unknown size always fits`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = 0))
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = -1))
    }
}
