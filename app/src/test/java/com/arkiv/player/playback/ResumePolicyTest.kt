package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where to resume an episode from, based on what was saved.
 *
 * Pulled out of the ViewModel and pinned down here because it's the rule that decides whether
 * opening something drops you where you left off or back at minute zero, and until now it lived
 * tangled up with a query to the torrent engine that made it impossible to test.
 */
class ResumePolicyTest {

    @Test
    fun `resumes where it left off`() {
        assertEquals(600_000, ResumePolicy.startPosition(savedPositionMs = 600_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `the first ten seconds don't count`() {
        // Opening something, changing your mind and leaving shouldn't leave a mark you'd later
        // have to skip past.
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 9_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `exactly ten seconds doesn't either`() {
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 10_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `if it was already almost finished, it starts over`() {
        // At 90% it counts as watched: resuming there would leave you at the credits.
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 4_900_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `with no saved duration it still resumes`() {
        // Not knowing how long it is isn't a reason to lose the position.
        assertEquals(600_000, ResumePolicy.startPosition(savedPositionMs = 600_000, savedDurationMs = 0))
    }

    @Test
    fun `a torrent resumes even if that zone isn't downloaded`() {
        // The behavior that changed: before, if the zone wasn't downloaded it returned 0 and the
        // torrent ALWAYS started from zero. Now it's respected and the engine prioritizes those
        // pieces when the range is requested. The rule doesn't depend on the download's state.
        assertEquals(3_000_000, ResumePolicy.startPosition(savedPositionMs = 3_000_000, savedDurationMs = 5_400_000))
    }
}
