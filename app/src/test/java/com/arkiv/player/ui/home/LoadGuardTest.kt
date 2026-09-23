package com.arkiv.player.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGuardTest {
    @Test fun `only allows loading once per id`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("cartelera"))
        assertFalse(guard.shouldLoad("cartelera"))
    }

    @Test fun `different ids are independent`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("a"))
        assertTrue(guard.shouldLoad("b"))
        assertFalse(guard.shouldLoad("a"))
    }
}
