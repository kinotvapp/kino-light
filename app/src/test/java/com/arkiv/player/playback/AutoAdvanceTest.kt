package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdvanceTest {

    private val TWENTY_FOUR_MIN = 24 * 60_000L + 39_000L

    /** The normal case: the chapter reached the end and the next one follows. */
    @Test fun end_at_the_chapter_s_end_advances() {
        assertTrue(AutoAdvance.isEndOfChapter(positionMs = TWENTY_FOUR_MIN, durationMs = TWENTY_FOUR_MIN))
    }

    /** Credits often leave the position a few seconds short of the total, and VLC doesn't always
     * reach the last frame: the end is a band, not an exact number. */
    @Test fun end_in_the_last_few_seconds_advances() {
        assertTrue(AutoAdvance.isEndOfChapter(positionMs = TWENTY_FOUR_MIN - 30_000, durationMs = TWENTY_FOUR_MIN))
    }

    /**
     * The reason this rule exists: VLC emits the SAME EndReached when the stream drops (Magis's
     * CDN going slow, or -- for a source removed in this branch's pruning -- a torrent running out
     * of peers). Without this guard, a network hiccup at minute 3 didn't pause: it skipped to the
     * next chapter, which could stall the same way, cascading through the whole series.
     */
    @Test fun a_cutoff_mid_chapter_does_not_advance() {
        assertFalse(AutoAdvance.isEndOfChapter(positionMs = 3 * 60_000, durationMs = TWENTY_FOUR_MIN))
    }

    /** A cutoff right at the start is as close to a source failure as it gets, never an end. */
    @Test fun a_cutoff_at_the_start_does_not_advance() {
        assertFalse(AutoAdvance.isEndOfChapter(positionMs = 0, durationMs = TWENTY_FOUR_MIN))
    }

    /**
     * With no known duration there's nothing to compare against (happens on magis when the
     * duration probe loses the race against the CDN). It advances anyway —that's what the user
     * expects— but requiring it to have played for a while, which is the only thing that tells an
     * end apart from a failure to open.
     */
    @Test fun with_no_duration_it_advances_if_it_played_for_a_while() {
        assertTrue(AutoAdvance.isEndOfChapter(positionMs = 12 * 60_000, durationMs = 0))
    }

    @Test fun with_no_duration_it_does_not_advance_if_it_just_started() {
        assertFalse(AutoAdvance.isEndOfChapter(positionMs = 4_000, durationMs = 0))
    }
}
