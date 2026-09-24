package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test fun `accepts MAJOR MINOR PATCH only`() {
        listOf("0.0.1", "1.0.0", "10.20.30").forEach { assertTrue(it, SemVer.isValid(it)) }
        listOf("1.0", "1.0.0-beta", "v1.0.0", "01.0.0", "1.0.0.0", "", "a.b.c").forEach { assertFalse(it, SemVer.isValid(it)) }
    }

    @Test fun `compares numerically, not as text`() {
        assertTrue(SemVer.compare("1.10.0", "1.9.9") > 0)
        assertTrue(SemVer.compare("1.0.0", "2.0.0") < 0)
        assertEquals(0, SemVer.compare("3.2.1", "3.2.1"))
    }
}
