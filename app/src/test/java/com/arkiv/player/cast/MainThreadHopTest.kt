package com.arkiv.player.cast

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadHopTest {
    @Test
    fun `on the main thread nothing is posted and the caller carries on`() {
        var posted = 0
        var ran = 0
        val hopped = MainThreadHop.run(onMain = true, post = { posted++ }) { ran++ }
        assertFalse(hopped)
        assertEquals(0, posted)
        assertEquals(0, ran)
    }

    @Test
    fun `off the main thread the block is posted once and runs only when the post runs`() {
        val queue = mutableListOf<Runnable>()
        var ran = 0
        val hopped = MainThreadHop.run(onMain = false, post = { queue += it }) { ran++ }
        assertTrue(hopped)
        assertEquals(1, queue.size)
        assertEquals(0, ran)
        queue.single().run()
        assertEquals(1, ran)
    }

    /** ERRORES-8E9: the progress loop runs on a background dispatcher and must not read the CastPlayer there. */
    @Test
    fun `the stuck-loading report is made from the main thread`() {
        val src = File("src/main/java/com/arkiv/player/cast/CastSessionManager.kt").readText()
        assertTrue(src.contains("withContext(Dispatchers.Main) { reportFailure(\"stuck_loading\") }"))
        assertTrue(src.contains("MainThreadHop.run("))
    }
}
