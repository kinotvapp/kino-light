package com.arkiv.player.data

import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The badge counter at the GROUP level. See [LibraryGroup.newEpisodes] for why it's the maximum
 * across sources and not the sum.
 */
class LibraryGroupingNewEpisodesTest {

    private fun row(id: String, eps: Int, watched: Int?) = LibraryRow(
        identifier = id, title = "Serie", description = null, thumbnailUrl = "",
        episodeCount = eps, durationSeconds = 0.0, addedAt = 0L,
        categoryOverride = "series", source = "archive", episodiosVistosEnLista = watched,
    )

    private fun group(vararg rows: LibraryRow) =
        LibraryGroup(key = "k", primary = rows.first(), members = rows.toList())

    @Test fun `a single source counts its own`() {
        assertEquals(2, group(row("a", eps = 26, watched = 24)).newEpisodes)
    }

    @Test fun `with nothing new there's no badge`() {
        assertEquals(0, group(row("a", eps = 26, watched = 26)).newEpisodes)
    }

    @Test fun `the same chapter in two sources counts ONCE`() {
        // Archive and web have the same series and both showed 2 new chapters. That's 2 new
        // chapters, not 4: summing them would lie the same way it summed to 794 episodes for a
        // series of 220.
        assertEquals(2, group(row("a", 26, 24), row("w", 26, 24)).newEpisodes)
    }

    @Test fun `the source with the most new ones wins`() {
        assertEquals(5, group(row("a", 26, 24), row("w", 30, 25)).newEpisodes)
    }

    @Test fun `a source that was never watched contributes no badge`() {
        // watched = null is "never opened": it can't invent new ones.
        assertEquals(0, group(row("a", 26, null)).newEpisodes)
    }

    @Test fun `an unwatched source doesn't hide the one that does have new ones`() {
        assertEquals(3, group(row("a", 26, null), row("w", 26, 23)).newEpisodes)
    }
}
