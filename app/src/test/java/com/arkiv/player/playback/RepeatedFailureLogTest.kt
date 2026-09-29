package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatedFailureLogTest {
    private val log = RepeatedFailureLog(logEvery = 10)

    @Test
    fun `the first failure is logged`() {
        assertEquals(1, log.onFailure("playlist:409:c"))
    }

    @Test
    fun `the repeats are skipped until the tenth, then every tenth`() {
        assertEquals(1, log.onFailure("k"))
        (2..9).forEach { assertNull("failure #$it", log.onFailure("k")) }
        assertEquals(10, log.onFailure("k"))
        (11..19).forEach { assertNull("failure #$it", log.onFailure("k")) }
        assertEquals(20, log.onFailure("k"))
    }

    @Test
    fun `keys count apart`() {
        assertEquals(1, log.onFailure("a"))
        assertEquals(1, log.onFailure("b"))
        assertNull(log.onFailure("a"))
    }

    @Test
    fun `a success ends the run and says how long it was`() {
        repeat(25) { log.onFailure("k") }
        assertEquals(25, log.onSuccess("k"))
        assertEquals(1, log.onFailure("k"))
    }

    @Test
    fun `a success with no failures before is zero`() {
        assertEquals(0, log.onSuccess("k"))
    }
}
