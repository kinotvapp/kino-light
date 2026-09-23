package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeStillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What happens when two sources write to the SAME `episode_still` row.
 *
 * The case that started these tests: a Magis season got saved (with still, name and synopsis the
 * gateway matched against TMDB), the detail screen opened with no network, `ensureEpisodeStills`
 * couldn't query anything and -- since `upsertAll` is REPLACE -- wrote the entire row null on top.
 * The series ended up with no images or names, and since the row still existed, nobody ever asked
 * again.
 */
class StillMergeTest {

    private fun row(
        still: String? = null,
        title: String? = null,
        overview: String? = null,
        fetchedAt: Long = 0L,
    ) = EpisodeStillEntity("magis:ABC::e1", still, fetchedAt, title, overview)

    @Test fun `with no previous row the new one enters as-is`() {
        val updated = row(still = "u", title = "t", overview = "o", fetchedAt = 9L)
        assertEquals(updated, StillMerge.merge(previous = null, updated = updated))
    }

    @Test fun `the new one wins when it brings something`() {
        val r = StillMerge.merge(
            previous = row(still = "viejo.jpg", title = "viejo", overview = "vieja"),
            updated = row(still = "nuevo.jpg", title = "nuevo", overview = "nueva"),
        )
        assertEquals("nuevo.jpg", r.stillUrl)
        assertEquals("nuevo", r.title)
        assertEquals("nueva", r.overview)
    }

    @Test fun `an empty query doesn't erase what was already there`() {
        // The heart of the bug: TMDB failed, the map came back empty, and the row still got
        // written with everything null on top of what Magis had saved correctly.
        val r = StillMerge.merge(
            previous = row(still = "magis.jpg", title = "La conspiración", overview = "Goku…"),
            updated = row(),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
        assertEquals("Goku…", r.overview)
    }

    @Test fun `the merge is field by field, not row by row`() {
        // The two sources complement each other: the gateway only asks TMDB for es-MX, so it can
        // bring a still and a name with no synopsis; the query from here can bring the
        // English-fallback one. With a per-ROW rule, each write would wipe out the other's good half.
        val r = StillMerge.merge(
            previous = row(still = "magis.jpg", title = "La conspiración"),
            updated = row(overview = "Goku entrena…"),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
        assertEquals("Goku entrena…", r.overview)
    }

    @Test fun `a blank value counts as absent`() {
        // TMDB returns "" (not null) for what it doesn't have; a "" overwriting a good title would
        // look in the UI the same as if it had been erased.
        val r = StillMerge.merge(
            previous = row(still = "magis.jpg", title = "La conspiración"),
            updated = row(still = "", title = "   "),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
    }

    @Test fun `with nothing from either side the row still ends up empty`() {
        // It's still the marker for "already asked and TMDB had nothing": what can't happen is
        // *making up* data, or asking again forever.
        val r = StillMerge.merge(previous = row(), updated = row())
        assertNull(r.stillUrl)
        assertNull(r.title)
        assertNull(r.overview)
    }

    @Test fun `the date is that of the latest query`() {
        // `fetchedAt` says when it was asked, not when the data is from: it was just asked.
        val r = StillMerge.merge(previous = row(title = "viejo", fetchedAt = 100L), updated = row(fetchedAt = 500L))
        assertEquals(500L, r.fetchedAt)
        assertEquals("viejo", r.title)
    }

    // --- The whole batch, which is the path Magis's save flow goes through --------------------

    private fun rowFor(
        episodeId: String,
        still: String? = null,
        title: String? = null,
        overview: String? = null,
        fetchedAt: Long = 0L,
    ) = EpisodeStillEntity(episodeId, still, fetchedAt, title, overview)

    @Test fun `saving from magis doesn't erase what TMDB had already completed`() {
        // The bug: "Save" wrote straight through what `MagisEntities.seasonStills` returns,
        // which leaves null every field the gateway didn't resolve, and `upsertAll` is REPLACE. If
        // the detail screen had opened before and `ensureEpisodeStills` completed the name and
        // synopsis, that save silently erased them -- and the row still existed, so nobody ever
        // filled them again.
        val r = StillMerge.mergeAll(
            previous = mapOf("magis:ABC::e1" to rowFor("magis:ABC::e1", title = "La conspiración", overview = "Goku…")),
            updated = listOf(rowFor("magis:ABC::e1", still = "https://img/1.jpg", fetchedAt = 500L)),
        )
        assertEquals(1, r.size)
        assertEquals("https://img/1.jpg", r[0].stillUrl)
        assertEquals("La conspiración", r[0].title)
        assertEquals("Goku…", r[0].overview)
        assertEquals(500L, r[0].fetchedAt)
    }

    @Test fun `each row is merged against the previous one of its own chapter`() {
        // Merged by `episodeId` (the table's PK): merging by position would pass one chapter's data
        // to another when the portal lists chapters in a different order.
        val r = StillMerge.mergeAll(
            previous = mapOf(
                "magis:ABC::e1" to rowFor("magis:ABC::e1", title = "Capítulo uno"),
                "magis:ABC::e2" to rowFor("magis:ABC::e2", title = "Capítulo dos"),
            ),
            updated = listOf(
                rowFor("magis:ABC::e2", still = "https://img/2.jpg"),
                rowFor("magis:ABC::e1", still = "https://img/1.jpg"),
            ),
        )
        assertEquals("Capítulo dos", r[0].title)
        assertEquals("Capítulo uno", r[1].title)
    }

    @Test fun `a chapter with no previous row enters as-is`() {
        // What normally happens when saving a new season: there's nothing to preserve.
        val updated = rowFor("magis:ABC::e3", still = "https://img/3.jpg", title = "Tres")
        assertEquals(listOf(updated), StillMerge.mergeAll(previous = emptyMap(), updated = listOf(updated)))
    }
}
