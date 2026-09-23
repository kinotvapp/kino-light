package com.arkiv.player.data.library

import com.arkiv.player.data.LibraryGroup

/**
 * The sections of "My library"'s side menu, in the order they're drawn.
 *
 * This IS the phone's "All / Movies / Series" filter (`LibraryScreen.LibFilter`): on the TV it
 * isn't duplicated as chips up top because with the remote, going up to a row of chips and back
 * down on every section change is a long trip, and two controls for the same thing fight over
 * focus.
 *
 * `ALL_SAVED` is named that and not `ALL` so it doesn't collide on read with `kotlin.TODO()`.
 */
enum class LibrarySection(val label: String) {
    ALL_SAVED("Todo"),
    SERIES("Series"),
    MOVIES("Películas"),
    WATCHED("Ya visto"),
    DOWNLOADS("Descargas"),
}

object LibraryFilter {

    /**
     * The groups that belong to [section], or **null** if that section doesn't come from the
     * saved library.
     *
     * Null and not an empty list on purpose: `WATCHED` and `DOWNLOADS` have their own data
     * source, and returning empty would make a mistaken caller draw "you didn't save anything"
     * over a section that's actually full.
     *
     * Filtered by `primary.isMovie` and not by the whole group because `LibraryGrouping.groupKeyOf`
     * already guarantees a movie is always a single-row group (never grouped: the artwork's
     * tmdbId gets it wrong on movies).
     */
    fun groups(section: LibrarySection, groups: List<LibraryGroup>): List<LibraryGroup>? =
        when (section) {
            LibrarySection.ALL_SAVED -> groups
            LibrarySection.SERIES -> groups.filter { !it.primary.isMovie }
            LibrarySection.MOVIES -> groups.filter { it.primary.isMovie }
            LibrarySection.WATCHED, LibrarySection.DOWNLOADS -> null
        }
}
