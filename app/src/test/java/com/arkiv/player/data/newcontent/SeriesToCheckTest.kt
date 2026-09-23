package com.arkiv.player.data.newcontent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which library series it's worth asking whether something new came out.
 * See [SeriesToCheck] for why each filter exists.
 */
class SeriesToCheckTest {

    private val NOW = 1_754_000_000_000L
    private val DAY = 24 * 60 * 60 * 1000L

    private fun series(
        id: String,
        source: String = "magis",
        watchedAgo: Long = 0L,
        episodeCount: Int = 12,
    ) = SeriesCandidate(id, source, lastWatchedMs = NOW - watchedAgo, episodeCount = episodeCount)

    @Test fun `a series watched yesterday qualifies`() {
        val chosen = SeriesToCheck.choose(listOf(series("a", watchedAgo = DAY)), NOW)
        assertEquals(listOf("a"), chosen.map { it.itemId })
    }

    @Test fun `a series untouched for a year is left out`() {
        // It's not that it doesn't matter: it's that checking 45 dormant series on every launch
        // spends battery and data on things nobody's watching. If you pick it back up, progress
        // brings it back on its own.
        val chosen = SeriesToCheck.choose(listOf(series("a", watchedAgo = 365 * DAY)), NOW)
        assertTrue(chosen.isEmpty())
    }

    @Test fun `right at the window's edge it still qualifies`() {
        val chosen = SeriesToCheck.choose(
            listOf(series("a", watchedAgo = (SeriesToCheck.WINDOW_DAYS - 1) * DAY)), NOW,
        )
        assertEquals(1, chosen.size)
    }

    @Test fun `with no progress it isn't checked`() {
        // lastWatchedMs = 0 is "never played": it's not a series you're watching.
        val chosen = SeriesToCheck.choose(
            listOf(SeriesCandidate("a", "magis", lastWatchedMs = 0L, episodeCount = 12)), NOW,
        )
        assertTrue(chosen.isEmpty())
    }

    @Test fun `a movie never qualifies`() {
        // A single episode = movie. There's no "new chapter" to look for.
        val chosen = SeriesToCheck.choose(listOf(series("movie", episodeCount = 1, watchedAgo = DAY)), NOW)
        assertTrue(chosen.isEmpty())
    }

    @Test fun `torrent is left out for now`() {
        // Out of scope: its series come in packs, "new chapter" means something else there.
        val chosen = SeriesToCheck.choose(listOf(series("t", source = "torrent", watchedAgo = DAY)), NOW)
        assertTrue(chosen.isEmpty())
    }

    @Test fun both_sources_qualify() {
        val chosen = SeriesToCheck.choose(
            listOf(
                series("m", source = "magis", watchedAgo = DAY),
                series("d", source = "ditu", watchedAgo = DAY),
            ),
            NOW,
        )
        assertEquals(2, chosen.size)
    }

    @Test fun a_recently_watched_ditu_series_qualifies() {
        val chosen = SeriesToCheck.choose(listOf(series("d", source = "ditu", watchedAgo = DAY)), NOW)
        assertEquals(listOf("d"), chosen.map { it.itemId })
    }

    @Test fun archive_is_no_longer_checked() {
        // Removed in this branch's pruning: leaving it in SOURCES only cost a real series a slot,
        // since NewChapterFinder does nothing with it.
        val chosen = SeriesToCheck.choose(listOf(series("a", source = "archive", watchedAgo = DAY)), NOW)
        assertTrue(chosen.isEmpty())
    }

    @Test fun web_is_no_longer_checked() {
        val chosen = SeriesToCheck.choose(listOf(series("w", source = "web", watchedAgo = DAY)), NOW)
        assertTrue(chosen.isEmpty())
    }

    @Test fun `the most recently watched one wins`() {
        val chosen = SeriesToCheck.choose(
            listOf(
                series("old", watchedAgo = 20 * DAY),
                series("today", watchedAgo = 1),
                series("middle", watchedAgo = 5 * DAY),
            ),
            NOW,
        )
        assertEquals(listOf("today", "middle", "old"), chosen.map { it.itemId })
    }

    @Test fun `the cap cuts off and keeps the freshest ones`() {
        val many = (1..30).map { series("s$it", watchedAgo = it * 60_000L) }
        val chosen = SeriesToCheck.choose(many, NOW)
        assertEquals(SeriesToCheck.MAX_SERIES, chosen.size)
        assertEquals("s1", chosen.first().itemId)
    }

    @Test fun `with no candidates it doesn't explode`() {
        assertTrue(SeriesToCheck.choose(emptyList(), NOW).isEmpty())
    }
}
