package com.arkiv.player.ui.update

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchFirstAvailableTest {
    @Test fun `the first screen the device has is the one opened`() {
        val opened = mutableListOf<String>()
        assertTrue(launchFirstAvailable(listOf("unknown-sources", "security")) { opened += it })
        assertEquals(listOf("unknown-sources"), opened)
    }

    @Test fun `a TV without the first screen falls to the next one instead of crashing`() {
        val opened = mutableListOf<String>()
        val ok = launchFirstAvailable(listOf("unknown-sources", "security")) {
            if (it == "unknown-sources") throw ActivityNotFoundException("No Activity found") else opened += it
        }
        assertTrue(ok)
        assertEquals(listOf("security"), opened)
    }

    @Test fun `a device with none of them says so`() {
        assertFalse(launchFirstAvailable(listOf("a", "b")) { throw ActivityNotFoundException() })
    }

    @Test fun `only a missing screen is swallowed, any other failure still shows`() {
        val failure = runCatching { launchFirstAvailable(listOf("a")) { error("boom") } }.exceptionOrNull()
        assertEquals("boom", failure?.message)
    }
}
