package com.arkiv.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a chapter counts as watched.
 *
 * The real bug (reported on device, 2026-08-11): the rule was `positionMs >= duration * 0.6`, and
 * "Continuar viendo" excludes what's watched. So at minute 15 of a 24-minute chapter, the chapter
 * DISAPPEARED from the home while it was still being watched, with nine minutes ahead. And since
 * `ItemDetail.resumeEpisode` offers the NEXT chapter once the current one is watched, the detail
 * screen started proposing a different chapter starting from zero — it felt like losing progress.
 *
 * The new rule is "how much is LEFT until the end", not "what fraction I'm at": three minutes mean
 * the same thing in a 24-minute chapter as in a two-hour movie, and a percentage doesn't.
 */
class WatchedThresholdTest {

    private val min = 60_000L

    /**
     * The exact case measured on the TV: Dragon Ball E125, 1479s duration, being watched at
     * second 1119 (75.7%). Under the old rule it was ALREADY marked watched and out of the home.
     */
    @Test
    fun `the real reported case no longer counts as watched`() {
        assertFalse(WatchedThreshold.isWatched(positionMs = 1_119_000, durationMs = 1_479_000))
    }

    @Test
    fun `a 24-minute chapter isn't watched at 15`() {
        assertFalse(WatchedThreshold.isWatched(positionMs = 15 * min, durationMs = 24 * min))
    }

    /** The ending has already started: it counts as watched, and only then is the next one offered. */
    @Test
    fun `a 24-minute chapter is watched at 22 and a half`() {
        assertTrue(WatchedThreshold.isWatched(positionMs = 22 * min + 30_000, durationMs = 24 * min))
    }

    /**
     * Where the percentage used to fail: at 90% of a two-hour movie there are still 12 minutes
     * left, which is half an episode. That's why "how much is left" wins, not the fraction.
     */
    @Test
    fun `a two-hour movie isn't watched at 90 percent`() {
        assertFalse(WatchedThreshold.isWatched(positionMs = 108 * min, durationMs = 120 * min))
    }

    @Test
    fun `a two-hour movie is watched when less than three minutes are left`() {
        assertTrue(WatchedThreshold.isWatched(positionMs = 118 * min, durationMs = 120 * min))
    }

    /**
     * Without the percentage floor, anything shorter than three minutes would be born watched:
     * the subtraction would go negative and position 0 would already beat it.
     */
    @Test
    fun `a two-minute clip isn't born watched`() {
        assertFalse(WatchedThreshold.isWatched(positionMs = 0, durationMs = 2 * min))
    }

    @Test
    fun `a two-minute clip is watched near the end`() {
        assertTrue(WatchedThreshold.isWatched(positionMs = 115_000, durationMs = 2 * min))
    }

    /** Unknown duration (Magis takes a while to resolve it): never marked watched blindly. */
    @Test
    fun `with no duration it isn't marked watched`() {
        assertFalse(WatchedThreshold.isWatched(positionMs = 5 * min, durationMs = 0))
    }

    @Test
    fun `reaching the end counts as watched`() {
        assertTrue(WatchedThreshold.isWatched(positionMs = 24 * min, durationMs = 24 * min))
    }
}
