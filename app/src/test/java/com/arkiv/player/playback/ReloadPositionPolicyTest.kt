package com.arkiv.player.playback

import com.arkiv.player.playback.ReloadPositionPolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class ReloadPositionPolicyTest {

    private val EP = "local:movie::0"

    /**
     * The bug measured on the Samsung S24+ on 2026-09-11: HOME left the movie playing, the screen
     * got recreated on return, and the playlist still in memory was 11m48s behind the controller's
     * own clock (3540099ms saved vs 3540425ms live, captured 0.3s apart -- the saved value wasn't
     * stale relative to the DB, only relative to what the screen had in memory). The live clock
     * must win.
     */
    @Test
    fun `same episode already live wins over a stale playlist position`() {
        val d = ReloadPositionPolicy.resumePosition(
            episodeId = EP,
            currentMediaId = EP,
            currentPositionMs = 3_573_410L,
            playlistPositionMs = 2_808_158L,
        )
        assertEquals(Source.LIVE, d.source)
        assertEquals(3_573_410L, d.positionMs)
    }

    /**
     * A live position BEHIND the playlist's still wins: nothing in this codebase can write a more
     * current external position while this same controller keeps holding the episode, so magnitude
     * isn't the signal -- identity plus "already moving" is. See the KDoc for why a deliberate
     * restart doesn't take this path.
     */
    @Test
    fun `same episode live wins even if the live position is behind the playlist`() {
        val d = ReloadPositionPolicy.resumePosition(
            episodeId = EP,
            currentMediaId = EP,
            currentPositionMs = 60_000L,
            playlistPositionMs = 600_000L,
        )
        assertEquals(Source.LIVE, d.source)
        assertEquals(60_000L, d.positionMs)
    }

    /** The controller holds this episode but never started it: 0 carries no information, unlike the playlist's value. */
    @Test
    fun `same episode at position zero defers to the playlist`() {
        val d = ReloadPositionPolicy.resumePosition(
            episodeId = EP,
            currentMediaId = EP,
            currentPositionMs = 0L,
            playlistPositionMs = 600_000L,
        )
        assertEquals(Source.PLAYLIST, d.source)
        assertEquals(600_000L, d.positionMs)
    }

    /** The controller is playing something else entirely: its clock says nothing about this episode. */
    @Test
    fun `a different episode currently loaded defers to the playlist`() {
        val d = ReloadPositionPolicy.resumePosition(
            episodeId = EP,
            currentMediaId = "local:other::0",
            currentPositionMs = 3_573_410L,
            playlistPositionMs = 600_000L,
        )
        assertEquals(Source.PLAYLIST, d.source)
        assertEquals(600_000L, d.positionMs)
    }

    /** Cold start: nothing loaded at all. */
    @Test
    fun `nothing loaded defers to the playlist`() {
        val d = ReloadPositionPolicy.resumePosition(
            episodeId = EP,
            currentMediaId = null,
            currentPositionMs = 0L,
            playlistPositionMs = 600_000L,
        )
        assertEquals(Source.PLAYLIST, d.source)
        assertEquals(600_000L, d.positionMs)
    }
}
