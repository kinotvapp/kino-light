package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup

/**
 * What's been watched of ONE library item. Pure model: the Room row (`WatchedRow`) is mapped to
 * this in the repository, so this object can be tested without bringing Room into the test.
 */
data class ItemWatched(
    val itemId: String,
    val episodes: Int,
    val lastWatchedMs: Long,
)

/** A library group with what's already been watched of it. */
data class WatchedGroup(
    val group: LibraryGroup,
    val episodesWatched: Int,
    val lastWatchedMs: Long,
)

/**
 * Builds the "Already watched" section by crossing per-item progress with the library's groups.
 *
 * Crossed against GROUPS and not against standalone items for the same reason as the home's
 * Series row: a series saved from several sources has to be a single card.
 */
object LibraryWatched {

    /**
     * [watched] arrives by itemId. A group counts as watched if ANY of its members has watched
     * episodes.
     *
     * The count is the **max** across members and not the sum: acquisitions are alternate copies
     * of the same series, not disjoint content (same criterion as `LibraryGroup.episodeCount`,
     * where summing 6 acquisitions gave 794 episodes for a series of ~220).
     *
     * Rows of [watched] whose item no longer belongs to any group are ignored: removing something
     * from the library doesn't erase its `playback` progress, and without this filter it would
     * reappear here forever.
     */
    fun cross(groups: List<LibraryGroup>, watched: List<ItemWatched>): List<WatchedGroup> {
        val byItem = watched.associateBy { it.itemId }
        return groups.mapNotNull { group ->
            val rows = group.members.mapNotNull { byItem[it.identifier] }
            if (rows.isEmpty()) return@mapNotNull null
            WatchedGroup(
                group = group,
                episodesWatched = rows.maxOf { it.episodes },
                lastWatchedMs = rows.maxOf { it.lastWatchedMs },
            )
        }.sortedByDescending { it.lastWatchedMs }
    }

    /** "1 capítulo visto" / "3 capítulos vistos". Singular by hand: without this, one alone said "1 capítulos vistos". */
    fun watchedLabel(episodes: Int): String =
        if (episodes == 1) "1 capítulo visto" else "$episodes capítulos vistos"
}
