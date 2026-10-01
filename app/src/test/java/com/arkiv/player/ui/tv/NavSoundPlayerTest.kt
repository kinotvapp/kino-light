package com.arkiv.player.ui.tv

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSoundPlayerTest {
    /** An executor that only runs what it was given when told to: "the background thread". */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class FakeClick : ClickSound {
        var plays = 0
        var released = false
        override fun play() { plays++ }
        override fun release() { released = true }
    }

    @Test
    fun `building, playing and releasing all happen on the executor, never on the caller`() {
        val executor = ManualExecutor()
        var built = 0
        val click = FakeClick()
        val player = NavSoundPlayer(executor) { built++; click }
        player.play()
        // Nothing ran on the calling (UI) thread.
        assertEquals(0, built)
        assertEquals(0, click.plays)
        executor.drain()
        assertEquals(1, built)
        assertEquals(1, click.plays)
        player.release()
        assertEquals(false, click.released)
        executor.drain()
        assertTrue(click.released)
    }

    /** ERRORES-19: a play stuck in the audio HAL must not let taps pile up behind it. */
    @Test
    fun `taps while a play is still queued are coalesced`() {
        val executor = ManualExecutor()
        val click = FakeClick()
        val player = NavSoundPlayer(executor) { click }
        executor.drain()
        repeat(10) { player.play() }
        assertEquals(1, executor.queue.size)
        executor.drain()
        assertEquals(1, click.plays)
        player.play()
        executor.drain()
        assertEquals(2, click.plays)
    }

    @Test
    fun `nothing plays after release and a failing pool does not throw`() {
        val executor = ManualExecutor()
        val click = FakeClick()
        val player = NavSoundPlayer(executor) { click }
        player.release()
        player.play()
        executor.drain()
        assertEquals(0, click.plays)

        val broken = NavSoundPlayer(executor) { error("no audio") }
        broken.play()
        broken.release()
        executor.drain()
    }
}
