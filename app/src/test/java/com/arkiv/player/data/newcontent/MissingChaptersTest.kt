package com.arkiv.player.data.newcontent

import com.arkiv.player.data.DituEntities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which chapters to ask the source for when checking a series being followed.
 * See [MissingChapters] for the why of each bound.
 */
class MissingChaptersTest {

    @Test fun if_nothing_came_out_nothing_is_requested() {
        assertEquals(
            emptyList<Int>(),
            MissingChapters.toFetch(have = listOf(1, 2, 3), inSource = listOf(1, 2, 3)),
        )
    }

    @Test fun one_new_chapter_is_requested() {
        assertEquals(
            listOf(4),
            MissingChapters.toFetch(have = listOf(1, 2, 3), inSource = listOf(1, 2, 3, 4)),
        )
    }

    @Test fun several_in_a_row_are_requested_in_order() {
        assertEquals(
            listOf(4, 5, 6),
            MissingChapters.toFetch(listOf(1, 2, 3), listOf(1, 2, 3, 4, 5, 6)),
        )
    }

    // ─── the bound that makes the web case cheap ───────────────────────────

    @Test fun an_old_gap_is_NOT_requested_again() {
        // Missing the 2, but has up to the 5. That gap is almost always a chapter the source never
        // had: searching for it again on every startup means paying for a search for nothing, forever.
        assertEquals(
            emptyList<Int>(),
            MissingChapters.toFetch(have = listOf(1, 3, 4, 5), inSource = listOf(1, 2, 3, 4, 5)),
        )
    }

    @Test fun with_an_old_gap_what_is_actually_new_is_still_requested() {
        assertEquals(
            listOf(6),
            MissingChapters.toFetch(have = listOf(1, 3, 4, 5), inSource = listOf(1, 2, 3, 4, 5, 6)),
        )
    }

    // ─── cap per series ─────────────────────────────────────────────────────

    @Test fun an_avalanche_is_brought_in_gradually() {
        // 20 chapters at once (a series not watched in a year). The first ones are brought in and
        // the rest on the next startup: a single series can't eat the whole budget.
        val source = (1..25).toList()
        val requested = MissingChapters.toFetch(have = listOf(1, 2, 3, 4, 5), inSource = source)
        assertEquals(MissingChapters.MAX_PER_SERIES, requested.size)
        assertEquals(listOf(6, 7, 8, 9, 10), requested)
    }

    // ─── edges ────────────────────────────────────────────────────────────

    @Test fun with_nothing_saved_the_source_s_first_ones_are_requested() {
        assertEquals(listOf(1, 2, 3, 4, 5), MissingChapters.toFetch(emptyList(), (1..9).toList()))
    }

    @Test fun an_empty_source_requests_nothing() {
        assertEquals(emptyList<Int>(), MissingChapters.toFetch(listOf(1, 2), emptyList()))
    }

    @Test fun repeats_in_the_source_are_requested_only_once() {
        assertEquals(listOf(4), MissingChapters.toFetch(listOf(1, 2, 3), listOf(4, 4, 4)))
    }

    @Test fun an_unsorted_source_still_returns_in_order() {
        assertEquals(listOf(4, 5, 6), MissingChapters.toFetch(listOf(1, 2, 3), listOf(6, 4, 5)))
    }

    @Test fun a_source_that_lags_behind_requests_nothing() {
        // The source reports fewer than what's already had: nothing new, and NOTHING should be deleted.
        assertEquals(emptyList<Int>(), MissingChapters.toFetch(listOf(1, 2, 3, 4, 5), listOf(1, 2)))
    }

    // ─── season-aware variant (Caracol numbers chapters PER SEASON) ────────
    //
    // Plain `toFetch` compares against the highest NUMBER seen so far, which breaks for a source
    // that restarts numbering every season: with ten chapters in season 1, season 2's chapter 1
    // would look like a duplicate of something already stored. `toFetchBySeason` keys by
    // `(season, number)` instead, so it compares season-first.

    @Test fun a_new_season_is_requested() {
        assertEquals(
            listOf(2 to 1),
            MissingChapters.toFetchBySeason(have = listOf(1 to 10), inSource = listOf(1 to 10, 2 to 1)),
        )
    }

    @Test fun a_new_chapter_in_the_same_season_is_requested() {
        assertEquals(
            listOf(1 to 11),
            MissingChapters.toFetchBySeason(have = listOf(1 to 10), inSource = listOf(1 to 10, 1 to 11)),
        )
    }

    @Test fun a_source_with_fewer_seasons_requests_nothing() {
        // Already have T2E3; the source only reports T1: none of that is "later" than what's stored.
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            MissingChapters.toFetchBySeason(have = listOf(2 to 3), inSource = listOf(1 to 1, 1 to 2)),
        )
    }

    @Test fun the_per_series_cap_also_applies_by_season() {
        val source = (1..8).map { 2 to it }
        val requested = MissingChapters.toFetchBySeason(have = listOf(1 to 10), inSource = source)
        assertEquals(MissingChapters.MAX_PER_SERIES, requested.size)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5), requested)
    }

    @Test fun a_null_season_is_treated_as_zero() {
        assertEquals(
            listOf(0 to 2),
            MissingChapters.toFetchBySeason(have = listOf(null to 1), inSource = listOf(null to 1, null to 2)),
        )
    }

    /**
     * Regression for fix round 1: `NewChapterFinder.checkDitu` keyed the STORED side with the
     * season Room already has (always written through [DituEntities.savedSeason], so never
     * null/0) but the SOURCE side with the raw, unresolved season. A chapter arriving with no
     * season of its own keyed as `0`, which read as "older" than a stored high-water mark of `1` --
     * so a genuinely new chapter was silently dropped. Keying both sides through
     * [DituEntities.savedSeason] (as the fix now does) closes that gap.
     */
    @Test fun keying_both_sides_through_savedSeason_catches_a_seasonless_new_chapter() {
        val have = listOf(DituEntities.savedSeason(null) to 5) // stored as T1E1..T1E5
        val newChapter = DituEntities.savedSeason(null) to 6   // arrives with no season of its own
        assertEquals(
            listOf(newChapter),
            MissingChapters.toFetchBySeason(have = have, inSource = listOf(newChapter)),
        )
    }
}
