package com.arkiv.player.playback

/**
 * How a live channel that cut out is reopened, shared by Magis live (`PlayerViewModel.reopenLiveAfterCut`)
 * and a plugin's live channel (`PluginLiveReopens`), so the two can never drift apart.
 *
 * Three reopens with doubling waits (2 s, 4 s, 8 s) cover a gap of ~15 s, in the ballpark of the cuts
 * measured on the Fire TV on 2026-08-14 (RCN FHD's origin failing intermittently and recovering on its
 * own within seconds); on the fourth cut the person is warned instead. Retrying with no cap would leave
 * a dead channel looping forever, burning data and never saying what's going on. The budget is
 * replenished once the channel has genuinely played for [MIN_HEALTHY_MS]: a reopen that dies at once
 * must not count as a recovery, or the cap would never be reached.
 */
internal object LiveReopenPolicy {
    /** Reopens before the person is warned. */
    const val MAX_REOPENS = 3

    /** Wait before the FIRST reopen; each next one doubles it. */
    const val FIRST_WAIT_MS = 2_000L

    /** How long a reopened channel has to play to count as recovered and get its whole budget back. */
    const val MIN_HEALTHY_MS = 5_000L

    /** The wait before reopen number [attempt] (1-based): 2 s, 4 s, 8 s. */
    fun waitMs(attempt: Int): Long = FIRST_WAIT_MS shl (attempt - 1).coerceAtLeast(0)
}
