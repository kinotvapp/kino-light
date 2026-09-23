package com.arkiv.player.data

/** An episode reduced to what's needed to navigate between neighbors. */
data class NavEpisode(val id: String, val section: String)

/**
 * Next/previous episode within the SAME section (season). Pure: testable without Room.
 * The list already comes sorted from the DAO; this just looks for the neighbor sharing a section.
 */
object EpisodeNavigation {

    fun nextId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.drop(idx + 1) }

    fun prevId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.take(idx).asReversed() }

    private fun neighbour(
        all: List<NavEpisode>,
        currentId: String,
        candidates: (Int) -> List<NavEpisode>,
    ): String? {
        val idx = all.indexOfFirst { it.id == currentId }
        if (idx < 0) return null
        val section = all[idx].section
        return candidates(idx).firstOrNull { it.section == section }?.id
    }
}
