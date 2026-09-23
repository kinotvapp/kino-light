package com.arkiv.player.ui.player

import com.arkiv.player.data.gateway.GatewayPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DituStateTest {

    private fun resolved(episodeId: String, posMs: Long = 0L) = DituReproducible(
        episodeId = episodeId,
        playable = GatewayPlayable(kind = "ditu", url = "https://cdn/$episodeId.mpd"),
        startPositionMs = posMs,
    )

    /** A state with episode A requested and already playing. */
    private fun playingA(): DituState = DituState().apply {
        newRequest("ditu:A")
        publish(resolved("ditu:A"))
    }

    /**
     * What [DituExoPlayer]'s clock sends: one reading every 500 ms while playing, for [durMs]
     * starting at [fromMs]. Returns the final position.
     */
    private fun DituState.play(fromMs: Long, durMs: Long): Long {
        var pos = fromMs
        advanced(pos, playing = true)
        while (pos < fromMs + durMs) {
            pos += 500
            advanced(pos, playing = true)
        }
        return pos
    }

    // --- the active-request guard ------------------------------------------------------

    @Test
    fun `what was requested gets published`() {
        val state = DituState()
        state.newRequest("ditu:A")
        assertTrue(state.publish(resolved("ditu:A", 5_000)))
        assertEquals("ditu:A", state.current.value?.episodeId)
        assertEquals(5_000L, state.current.value?.startPositionMs)
    }

    /** The race: Caracol was still resolving A when the person requested another episode (here, from Magis). */
    @Test
    fun `a resolution for an episode that is no longer active does not get published`() {
        val state = DituState()
        state.newRequest("ditu:A")
        state.newRequest("magis:B")
        assertFalse(state.publish(resolved("ditu:A")))
        assertNull(state.current.value)
    }

    @Test
    fun `a new request drops whatever from caracol was playing`() {
        val state = playingA()
        state.newRequest("live:canal1")
        assertNull(state.current.value)
    }

    /** A reload of something that was paused reaches the screen paused. */
    @Test
    fun `a reload while paused is published paused`() {
        val state = DituState()
        state.newRequest("ditu:A")
        state.publish(resolved("ditu:A", 1_000).copy(autoStart = false))
        assertFalse(state.current.value!!.autoStart)
    }

    /** If Caracol returns the same URL, the screen still has to rebuild the player. */
    @Test
    fun `two identical publications are not the same value`() {
        val state = DituState()
        state.newRequest("ditu:A")
        state.publish(resolved("ditu:A", 1_000))
        val first = state.current.value
        state.publish(resolved("ditu:A", 1_000))
        assertNotEquals(first, state.current.value)
    }

    // --- reloads ------------------------------------------------------------------------------

    @Test
    fun `two reloads and the third does not happen`() {
        val state = playingA()
        assertEquals("ditu:A", state.requestReload())
        state.publish(resolved("ditu:A", 60_000))
        assertEquals("ditu:A", state.requestReload())
        state.publish(resolved("ditu:A", 61_000))

        // null = no more reloads: the ViewModel sends the error to the person.
        assertNull(state.requestReload())
    }

    /** The old loop: reaching READY replenished the cap, and a stream that just dies never stopped. */
    @Test
    fun `reaching playback and dying right away does not replenish the reloads`() {
        val state = playingA()
        var pos = 0L
        repeat(MAX_DITU_RELOADS) {
            pos = state.play(pos, 2_000)
            assertEquals("ditu:A", state.requestReload())
            state.publish(resolved("ditu:A", pos))
        }
        state.play(pos, 2_000)
        assertNull(state.requestReload())
    }

    @Test
    fun `thirty stable seconds replenish the reloads`() {
        val state = playingA()
        state.requestReload()
        state.requestReload()
        state.play(0L, STABLE_PLAYBACK_MS)
        // A later cut has its two reloads back in full.
        assertEquals("ditu:A", state.requestReload())
        assertEquals("ditu:A", state.requestReload())
        assertNull(state.requestReload())
    }

    @Test
    fun `a new request replenishes the reloads`() {
        val state = playingA()
        state.requestReload()
        state.requestReload()
        state.newRequest("ditu:A")
        state.publish(resolved("ditu:A"))
        assertEquals("ditu:A", state.requestReload())
    }

    @Test
    fun `with nothing from caracol playing there is no reload`() {
        val state = DituState()
        state.newRequest("ditu:A")
        assertNull(state.requestReload())
    }

    // --- re-prepares -------------------------------------------------------------------------

    @Test
    fun `reaching playback and dying right away does not replenish the reprepares`() {
        val state = playingA()
        var pos = 0L
        repeat(MAX_DITU_REPREPARES) {
            pos = state.play(pos, 2_000)
            assertTrue(state.requestReprepare())
        }
        state.play(pos, 2_000)
        assertFalse(state.requestReprepare())
    }

    @Test
    fun `thirty stable seconds replenish the reprepares`() {
        val state = playingA()
        repeat(MAX_DITU_REPREPARES) { assertTrue(state.requestReprepare()) }
        assertFalse(state.requestReprepare())
        state.play(0L, STABLE_PLAYBACK_MS)
        repeat(MAX_DITU_REPREPARES) { assertTrue(state.requestReprepare()) }
        assertFalse(state.requestReprepare())
    }

    /** A new URL is a new stream: its ad breaks get the reprepares back in full. */
    @Test
    fun `a reload replenishes the reprepares but not the reloads`() {
        val state = playingA()
        repeat(MAX_DITU_REPREPARES) { state.requestReprepare() }
        assertEquals("ditu:A", state.requestReload())
        state.publish(resolved("ditu:A"))
        repeat(MAX_DITU_REPREPARES) { assertTrue(state.requestReprepare()) }
        assertEquals("ditu:A", state.requestReload())
        state.publish(resolved("ditu:A"))
        assertNull(state.requestReload())
    }

    // --- "in a row" ------------------------------------------------------------------------

    @Test
    fun `a rebuffer at twenty seconds cuts the streak`() {
        val state = playingA()
        repeat(MAX_DITU_REPREPARES) { state.requestReprepare() }
        var pos = state.play(0L, 20_000)
        state.advanced(pos, playing = false) // stopped to buffer
        pos = state.play(pos, 20_000)
        // 20 + 20 isn't 30 in a row: the cap is still spent.
        assertFalse(state.requestReprepare())
        state.play(pos, STABLE_PLAYBACK_MS)
        assertTrue(state.requestReprepare())
    }

    @Test
    fun `a jump does not count as playing`() {
        val state = playingA()
        repeat(MAX_DITU_REPREPARES) { state.requestReprepare() }
        state.advanced(0L, playing = true)
        state.advanced(STABLE_PLAYBACK_MS * 2, playing = true) // seek forward
        assertFalse(state.requestReprepare())
    }
}
