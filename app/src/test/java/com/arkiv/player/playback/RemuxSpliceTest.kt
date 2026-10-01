package com.arkiv.player.playback

import com.arkiv.player.playback.RemuxHls.Splice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Joining the remux left on disk by an earlier cast (the head) onto the one being written now.
 * Video in ticks of 1/1000 s (track 1), audio on track 2 whose cuts may differ between runs.
 */
class RemuxSpliceTest {

    /** Fragments whose video lasts [videoMs] each, audio slightly off by [audioSkew] ticks. */
    private fun run(vararg videoMs: Long, audioSkew: Long = 0): List<Fmp4Index.Fragment> {
        var at = 0L
        var v = 0L
        var a = 0L
        return videoMs.mapIndexed { i, d ->
            val audioDur = d + if (i == 0) audioSkew else 0
            val f = Fmp4Index.Fragment(
                start = at, moofSize = 100, end = at + 1000,
                trafs = listOf(Fmp4Index.Traf(1, d, v), Fmp4Index.Traf(2, audioDur, a)),
                durationSec = d / 1000.0,
            )
            at += 1000; v += d; a += audioDur
            f
        }
    }

    @Test
    fun `the new remux has not reached the end of the head yet`() {
        val head = run(3000, 3000, 3000, 3000)
        val main = run(3000, 3000)
        assertEquals(Splice.Waiting, RemuxHls.splice(head, main, videoTrack = 1))
        assertEquals(Splice.Waiting, RemuxHls.splice(head, emptyList(), videoTrack = 1))
    }

    @Test
    fun `the whole head is kept and the new remux continues at the same video instant`() {
        val head = run(3000, 3000, 3000, 3000)
        // Another run of the same title: same video cuts, audio split differently.
        val main = run(3000, 3000, 3000, 3000, 3000, 3000, audioSkew = 21)
        assertEquals(Splice.Joined(headCount = 4, mainFrom = 4), RemuxHls.splice(head, main, videoTrack = 1))
    }

    @Test
    fun `reaching exactly the end of the head joins with nothing new yet`() {
        val head = run(3000, 3000)
        assertEquals(Splice.Joined(2, 2), RemuxHls.splice(head, run(3000, 3000), videoTrack = 1))
    }

    @Test
    fun `a tail of the head nobody has been shown can be dropped to find a shared cut`() {
        // Head: 3+3 = the published segment, then a 2 s fragment not yet in any segment.
        val head = run(3000, 3000, 2000)
        // Main cut differently after 6 s: no cut at 8 s, but there is one at 6 s.
        val main = run(3000, 3000, 2500, 2500)
        assertEquals(Splice.Joined(2, 2), RemuxHls.splice(head, main, videoTrack = 1))
    }

    @Test
    fun `fragments the receiver may already hold are never taken back`() {
        val head = run(3000, 3000, 3000, 3000)
        val main = run(2000, 2000, 2000, 2000, 2000, 2000, 2000)
        // The head's two published segments end at 12 s: a run with no cut at 12 s cannot follow
        // it (going back to 6 s would take back a segment already in the playlist).
        assertEquals(Splice.Impossible, RemuxHls.splice(head, run(2500, 2500, 2500, 2500, 2500, 2500), videoTrack = 1))
        assertEquals(Splice.Joined(4, 6), RemuxHls.splice(head, main, videoTrack = 1))
    }

    @Test
    fun `no head is joined from the start`() {
        assertEquals(Splice.Joined(0, 0), RemuxHls.splice(emptyList(), run(3000), videoTrack = 1))
    }

    @Test
    fun `the served timeline is the head's kept part then the new remux's`() {
        val head = run(3000, 3000, 3000)
        val main = run(3000, 3000, 3000, 3000, audioSkew = 7)
        val pieces = RemuxHls.timeline(head, main, Splice.Joined(3, 3))
        assertEquals(listOf(true, true, true, false), pieces.map { it.fromHead })
        assertEquals(main[3], pieces.last().fragment)
        assertEquals(head, RemuxHls.timeline(head, main, Splice.Waiting).map { it.fragment })
        assertEquals(main, RemuxHls.timeline(head, main, Splice.Impossible).map { it.fragment })
    }
}
