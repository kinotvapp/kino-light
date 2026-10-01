package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The span a player reads off a joined `.ts` (first PCR near the start, last near the end) against
 * the playlist's EXTINF total. Joined segments keep their own clocks, so a splice that jumps the PCR
 * makes that span lie -- or vanish, which is what leaves the player with no duration.
 */
class JoinedTsDurationTest {

    private fun packets(pid: Int, vararg pcrSeconds: Double): ByteArray {
        val out = ByteArray(188 * pcrSeconds.size)
        pcrSeconds.forEachIndexed { n, sec ->
            val i = n * 188
            val base = (sec * 90_000).toLong() and ((1L shl 33) - 1)
            out[i] = 0x47
            out[i + 1] = (pid shr 8 and 0x1F).toByte()
            out[i + 2] = pid.toByte()
            out[i + 3] = 0x30 // adaptation field + payload
            out[i + 4] = 7 // adaptation field length: flags + 6 PCR bytes
            out[i + 5] = 0x10 // PCR flag
            out[i + 6] = (base shr 25).toByte()
            out[i + 7] = (base shr 17).toByte()
            out[i + 8] = (base shr 9).toByte()
            out[i + 9] = (base shr 1).toByte()
            out[i + 10] = ((base and 1) shl 7).toByte()
        }
        return out
    }

    @Test
    fun `a continuous clock spans the whole movie`() {
        val span = JoinedTsDuration.pcrSpanMs(packets(256, 10.0, 10.1, 10.2), packets(256, 7677.0, 7677.5, 7677.9))
        assertEquals(7_667_900L, span)
        assertTrue(JoinedTsDuration.agrees(span, 7_667_000L))
    }

    @Test
    fun `a clock that restarts after a splice gives no believable span`() {
        // A pre-roll whose clock sits 1000 s ahead of the movie's: the span comes out short.
        val short = JoinedTsDuration.pcrSpanMs(packets(256, 1000.0, 1000.1, 1000.2), packets(256, 6600.0, 6600.5, 6600.9))
        assertEquals(5_600_900L, short)
        assertFalse(JoinedTsDuration.agrees(short, 7_667_000L))
        // The movie restarts its clock below the pre-roll's: last < first, which the player treats as
        // "no duration" (and a wrap-aware delta as ~26 h, past the believable ceiling).
        val restarted = JoinedTsDuration.pcrSpanMs(packets(256, 5000.0, 5000.1, 5000.2), packets(256, 100.0, 100.5, 100.9))
        assertEquals(0L, restarted)
        assertFalse(JoinedTsDuration.agrees(restarted, 7_667_000L))
    }

    @Test
    fun `a span far from the playlist does not agree`() {
        assertFalse(JoinedTsDuration.agrees(5_600_000L, 7_667_000L))
        assertTrue(JoinedTsDuration.agrees(7_667_000L + 29_000L, 7_667_000L))
        assertFalse(JoinedTsDuration.agrees(7_667_000L, 0L))
    }
}
