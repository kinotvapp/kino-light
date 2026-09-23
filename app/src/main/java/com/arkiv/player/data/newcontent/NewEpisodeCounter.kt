package com.arkiv.player.data.newcontent

/**
 * How many new chapters to show in a library series' badge.
 *
 * **Why this doesn't count by date**, the obvious first attempt: `EpisodeEntity` doesn't store a
 * creation date — it only has `updatedAt`, which is the sync LWW clock, and any write that
 * touches an episode row bumps it. Any design based on episode timestamps starts broken here.
 *
 * So it's counted against **what was already listed**: the episode count the series had the last
 * time its detail was opened. The difference against the current count is what appeared since
 * then, without depending on clocks that get overwritten.
 */
object NewEpisodeCounter {

    /**
     * [seen] is how many episodes the series had the last time its detail was opened, or `null`
     * if it was never opened since this counter has existed.
     *
     * `null` returns 0 on purpose: it's the state of the WHOLE existing library the day this ships,
     * and starting out showing a badge with every series' total would be pure noise instead of
     * something new.
     */
    fun count(current: Int, seen: Int?): Int {
        if (seen == null) return 0
        return (current - seen).coerceAtLeast(0)
    }

    /** Whether the badge should be drawn. Sugar over [count] so the UI doesn't compare by hand. */
    fun shouldShow(current: Int, seen: Int?): Boolean = count(current, seen) > 0

    /**
     * What to leave in `episodiosVistosEnLista` after saving chapters the USER brought in (not the
     * portal), like saving the whole season to play just one.
     *
     * If the counter was already sealed, it's re-sealed to the current total: the badge is for
     * "new chapters showed up", not for "you just saved the season". If it was `null` (detail
     * never opened) it stays `null`, because sealing it here would turn off the badge for new
     * chapters that haven't happened yet.
     */
    fun reseal(seen: Int?, totalNow: Int): Int? = if (seen == null) null else totalNow
}
