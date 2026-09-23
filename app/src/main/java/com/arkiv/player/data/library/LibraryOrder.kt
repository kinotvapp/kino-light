package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow

/**
 * The order of "My library": the last thing you watched first.
 *
 * The rule is `max(last playback, addedAt)`, in a single list (never-watched does NOT go to a
 * separate block): what you just watched moves to the top, and what you just added too, because
 * its `addedAt` is "now". The `max` and not playback alone is what keeps something watched a year
 * ago but re-added today from getting buried right when you just looked it up.
 *
 * "Last playback" includes FINISHED episodes, not just the ones left halfway: finishing E4 last
 * night has to leave the series first today, with E5 one tap away. That's the case this object
 * exists to solve, and that's why the "Continue watching" criterion isn't reused
 * (`observeContinueWatching`, which filters `watched = 0`), which would drop it from the top
 * right as the episode finishes.
 *
 * Lives here and not in the repository so it can be tested without Room, same as [LibraryWatched].
 */
object LibraryOrder {

    /**
     * [latest] is `itemId -> last playback in epoch ms`. An absent item was never played, and
     * there its `addedAt` rules.
     */
    fun recencyOf(row: LibraryRow, latest: Map<String, Long>): Long =
        maxOf(latest[row.identifier] ?: 0L, row.addedAt)

    /**
     * The raw rows, sorted (the phone's grid).
     *
     * `sortedByDescending` is stable, so two items with the same recency keep the incoming order,
     * which comes `addedAt DESC` from `ItemDao.observeLibrary`.
     */
    fun sortedRows(rows: List<LibraryRow>, latest: Map<String, Long>): List<LibraryRow> =
        rows.sortedByDescending { recencyOf(it, latest) }

    /**
     * The groups, sorted (the TV's grid). A group's recency is its most recent member's: a series
     * saved from several sources is ONE card, and watching it through any of them moves the whole
     * thing up (same criterion as [LibraryWatched.cross]).
     */
    fun sortedGroups(groups: List<LibraryGroup>, latest: Map<String, Long>): List<LibraryGroup> =
        groups.sortedByDescending { g -> g.members.maxOf { recencyOf(it, latest) } }
}
