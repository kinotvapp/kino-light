package com.arkiv.player.data

/**
 * When a chapter counts as watched.
 *
 * Lives here and not inline in `savePlayback` because it isn't a persistence detail: it's the
 * product rule that decides TWO visible things at once —whether the chapter stays in "Continuar
 * viendo" and whether the series' detail offers this chapter or the next one
 * ([ItemDetail.resumeEpisode])—, and because a product rule with no test is the kind that breaks
 * without anyone noticing.
 *
 * ### Why "how much is left" and not "what fraction I'm at"
 *
 * The old rule was `positionMs >= duration * 0.6`. Reported on device (2026-08-11) and measured
 * right there: a 24-minute chapter got marked watched at minute 15 and **disappeared from the
 * home while it was still being watched**, with nine minutes ahead; and the detail screen started
 * offering the next chapter, which feels like losing your progress.
 *
 * A percentage doesn't mean the same thing across content of different lengths: at 90% of a
 * two-hour movie there are still twelve minutes left. Three minutes, though, are three minutes in
 * both cases, and it's roughly how long an ending or some credits last.
 */
object WatchedThreshold {

    /** How much can be left before the end and it already counts as watched. */
    const val REMAINING_MS = 3 * 60 * 1000L

    /**
     * Percentage floor, which is NOT redundant: without it, anything shorter than [REMAINING_MS]
     * would be born watched (the subtraction goes negative and position 0 already beats it). It
     * also keeps "three minutes" from being almost all of a short piece of content.
     */
    const val MINIMUM_FRACTION = 0.9

    /**
     * Returns the LATER of the two thresholds, which is the conservative one: marking watched too
     * early is exactly the bug this fixes.
     *
     * In practice: below ~30 min of duration the fraction wins (a 24-min chapter gets marked at
     * 21:36); above it the remaining-time one wins (a 2h movie, at 1:57).
     */
    fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val byRemaining = durationMs - REMAINING_MS
        val byFraction = (durationMs * MINIMUM_FRACTION).toLong()
        return positionMs >= maxOf(byRemaining, byFraction)
    }
}
