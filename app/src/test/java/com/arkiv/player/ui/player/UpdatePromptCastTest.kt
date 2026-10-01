package com.arkiv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OTA prompt never covers a cast or its trouble dialog. */
class UpdatePromptCastTest {

    @Test
    fun `the update waits while casting or while the cast dialog asks`() {
        assertTrue(updatePromptWaitsForCast(casting = true, troubleShown = false))
        assertTrue(updatePromptWaitsForCast(casting = false, troubleShown = true))
        assertTrue(updatePromptWaitsForCast(casting = true, troubleShown = true))
    }

    @Test
    fun `with no cast going on the update is offered`() {
        assertFalse(updatePromptWaitsForCast(casting = false, troubleShown = false))
    }
}
