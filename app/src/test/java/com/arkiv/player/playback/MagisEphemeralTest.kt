package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path that plays without writing a row. See [MagisEphemeral]'s KDoc for why.
 */
class MagisEphemeralTest {

    @Test fun `the ephemeral id is recognized and still counts as magis`() {
        val id = MagisEphemeral.idFor("C1")

        assertTrue(MagisEphemeral.isEphemeral(id))
        // Without this `PlayerViewModel.load` would send it down the archive path and nothing would play.
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor(id))
    }

    /**
     * A library episodeId must NOT look ephemeral: if it did, a saved item would play through the
     * path that doesn't save progress, and normal content's "continue watching" would be lost
     * without anyone noticing.
     */
    @Test fun `a library id is not confused with an ephemeral one`() {
        assertFalse(MagisEphemeral.isEphemeral("magis:12345::1"))
        assertFalse(MagisEphemeral.isEphemeral("torrent:abc::0"))
    }

    @Test fun `what was left is recovered with its same id`() {
        val id = MagisEphemeral.idFor("C2")
        MagisEphemeral.leave(MagisEphemeral.Pending(id, ref = "abc.def", titulo = "Peli", adulto = true))

        val p = MagisEphemeral.take(id)

        assertEquals("abc.def", p?.ref)
        assertEquals("Peli", p?.titulo)
        assertTrue(p?.adulto == true)
    }

    /**
     * THE EDGE: asking about ANOTHER episode must not return the pending entry left by the
     * previous playback. If it did, opening any random library item right after watching something
     * through this path would resolve the wrong stream.
     */
    @Test fun `asking about a different episode does not return someone else's pending entry`() {
        MagisEphemeral.leave(
            MagisEphemeral.Pending(MagisEphemeral.idFor("C3"), ref = "x.y", titulo = "", adulto = true),
        )

        assertNull(MagisEphemeral.take(MagisEphemeral.idFor("C4")))
        assertNull(MagisEphemeral.take("magis:999::1"))
    }
}
