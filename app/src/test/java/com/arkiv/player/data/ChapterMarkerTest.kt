package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which marker wins for an episode.
 *
 * The case that forces this, measured against AniSkip on 2026-08-19: in Demon Slayer episode 1's
 * opening starts at 1270 s and episode 2's at 57 s. With one marker per SERIES, "Skip intro" would
 * throw you into the middle of the episode.
 *
 * Later correction, also measured against the real device: AniSkip SOMETIMES GETS IT WRONG (an
 * episode brought the credits labeled as opening) and there's no reliable way to detect it. The
 * way out is for the person to correct it by hand, so MANUAL has to be able to beat automatic
 * regardless of scope -- otherwise a bad episode time could never be fixed.
 */
class ChapterMarkerTest {

    private fun marker(
        itemId: String,
        episodeId: String,
        opEnd: Long,
        origen: String = ChapterMarker.SOURCE_MANUAL,
    ) = SkipMarkerEntity(
        id = ChapterMarker.idFor(itemId, episodeId),
        itemId = itemId,
        episodeId = episodeId,
        openingStartMs = 0,
        openingEndMs = opEnd,
        endingStartMs = null,
        origen = origen,
    )

    @Test fun the_key_joins_the_item_and_the_episode() {
        assertEquals("magis:ABC|magis:ABC::e1", ChapterMarker.idFor("magis:ABC", "magis:ABC::e1"))
    }

    @Test fun the_whole_series_marker_carries_an_empty_episode() {
        assertEquals("magis:ABC|", ChapterMarker.idFor("magis:ABC", ""))
    }

    @Test fun two_episodes_of_the_same_series_do_not_share_a_key() {
        assertNotEquals(
            ChapterMarker.idFor("magis:ABC", "magis:ABC::e1"),
            ChapterMarker.idFor("magis:ABC", "magis:ABC::e2"),
        )
    }

    /** With the same source (both manual, `marker()`'s default), the episode's wins. */
    @Test fun the_episode_s_wins_over_the_series_s() {
        val chosen = ChapterMarker.choose(
            fromChapter = marker("magis:ABC", "magis:ABC::e1", opEnd = 147_000),
            fromSeries = marker("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(147_000L, chosen!!.openingEndMs)
    }

    /** What was set by hand still applies where there's no automatic data: nobody loses their work. */
    @Test fun with_no_episode_marker_the_series_one_wins() {
        val chosen = ChapterMarker.choose(
            fromChapter = null,
            fromSeries = marker("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(90_000L, chosen!!.openingEndMs)
    }

    @Test fun with_neither_there_is_no_marker() {
        assertNull(ChapterMarker.choose(fromChapter = null, fromSeries = null))
    }

    /** A row with no times at all is not a marker: it would draw a button that goes nowhere. */
    @Test fun a_marker_with_no_times_does_not_count() {
        val empty = SkipMarkerEntity(
            id = "x", itemId = "magis:ABC", episodeId = "magis:ABC::e1",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
        )
        assertNull(ChapterMarker.choose(fromChapter = empty, fromSeries = null))
    }

    // --- Precedence by source: manual beats automatic, regardless of scope ---

    /**
     * THE case that motivates the correction: AniSkip got the episode wrong (credits as opening),
     * the person fixed it by hand in the SERIES dialog -- and that fix has to beat the episode's
     * automatic data, not the other way around.
     */
    @Test fun the_series_manual_wins_over_the_episode_automatic() {
        val chosen = ChapterMarker.choose(
            fromChapter = marker(
                "magis:ABC", "magis:ABC::e1", opEnd = 999_000,
                origen = ChapterMarker.SOURCE_AUTO,
            ),
            fromSeries = marker(
                "magis:ABC", "", opEnd = 90_000,
                origen = ChapterMarker.SOURCE_MANUAL,
            ),
        )
        assertEquals(90_000L, chosen!!.openingEndMs)
    }

    /** An episode manual also beats a series automatic (nothing overrides what's set by hand). */
    @Test fun the_episode_manual_wins_over_the_series_automatic() {
        val chosen = ChapterMarker.choose(
            fromChapter = marker(
                "magis:ABC", "magis:ABC::e1", opEnd = 147_000,
                origen = ChapterMarker.SOURCE_MANUAL,
            ),
            fromSeries = marker(
                "magis:ABC", "", opEnd = 90_000,
                origen = ChapterMarker.SOURCE_AUTO,
            ),
        )
        assertEquals(147_000L, chosen!!.openingEndMs)
    }

    /** With the same automatic source, the episode's still wins (same criterion as manual). */
    @Test fun with_the_same_automatic_source_the_episode_wins_over_the_series() {
        val chosen = ChapterMarker.choose(
            fromChapter = marker(
                "magis:ABC", "magis:ABC::e1", opEnd = 147_000,
                origen = ChapterMarker.SOURCE_AUTO,
            ),
            fromSeries = marker(
                "magis:ABC", "", opEnd = 90_000,
                origen = ChapterMarker.SOURCE_AUTO,
            ),
        )
        assertEquals(147_000L, chosen!!.openingEndMs)
    }

    // --- inOpening/inEnding: the math that decides whether the button shows, extracted from PlayerScreen ---

    private fun withBothStretches(openStart: Long?, openEnd: Long?, endingStart: Long?) = SkipMarkerEntity(
        id = "x", itemId = "magis:ABC", episodeId = "magis:ABC::e1",
        openingStartMs = openStart, openingEndMs = openEnd, endingStartMs = endingStart,
    )

    @Test fun in_opening_inside_the_range() {
        val m = withBothStretches(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertTrue(ChapterMarker.inOpening(m, positionMs = 50_000))
    }

    @Test fun in_opening_at_the_range_s_edges_counts() {
        val m = withBothStretches(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertTrue(ChapterMarker.inOpening(m, positionMs = 10_000))
        assertTrue(ChapterMarker.inOpening(m, positionMs = 90_000))
    }

    @Test fun in_opening_false_before_or_after_the_range() {
        val m = withBothStretches(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertFalse(ChapterMarker.inOpening(m, positionMs = 9_999))
        assertFalse(ChapterMarker.inOpening(m, positionMs = 90_001))
    }

    /** With no `openingStartMs` the range starts at 0: the old manual opening didn't save it. */
    @Test fun in_opening_with_no_start_begins_at_zero() {
        val m = withBothStretches(openStart = null, openEnd = 90_000, endingStart = null)
        assertTrue(ChapterMarker.inOpening(m, positionMs = 0))
        assertFalse(ChapterMarker.inOpening(m, positionMs = 90_001))
    }

    @Test fun in_opening_false_with_no_openingEndMs() {
        val m = withBothStretches(openStart = 10_000, openEnd = null, endingStart = null)
        assertFalse(ChapterMarker.inOpening(m, positionMs = 50_000))
    }

    @Test fun in_opening_false_with_no_marker() {
        assertFalse(ChapterMarker.inOpening(null, positionMs = 50_000))
    }

    @Test fun in_ending_true_from_the_moment_it_starts() {
        val m = withBothStretches(openStart = null, openEnd = null, endingStart = 1_400_000)
        assertTrue(ChapterMarker.inEnding(m, positionMs = 1_400_000))
        assertTrue(ChapterMarker.inEnding(m, positionMs = 1_500_000))
    }

    @Test fun in_ending_false_before_it_starts() {
        val m = withBothStretches(openStart = null, openEnd = null, endingStart = 1_400_000)
        assertFalse(ChapterMarker.inEnding(m, positionMs = 1_399_999))
    }

    @Test fun in_ending_false_with_no_endingStartMs() {
        val m = withBothStretches(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertFalse(ChapterMarker.inEnding(m, positionMs = 999_999_999))
    }

    @Test fun in_ending_false_with_no_marker() {
        assertFalse(ChapterMarker.inEnding(null, positionMs = 999_999_999))
    }
}
