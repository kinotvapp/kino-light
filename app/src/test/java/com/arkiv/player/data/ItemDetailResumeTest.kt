package com.arkiv.player.data

import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where you're at in a series.
 *
 * With a single saved chapter this didn't show. Now that playing one brings the whole season
 * (see `addMagisSeason`), "Play" and the detail text point at a chapter among twenty, and the two
 * old ways of getting it wrong show up right away: falling back to E1 the moment you finish E5,
 * and falling back to E1 when you hit play on E5 three seconds ago and there's no saved progress yet.
 */
class ItemDetailResumeTest {

    private fun ep(n: Int) = Episode(
        id = "magis:ABC::e$n", itemId = "magis:ABC", section = "", displayName = "E$n",
        orderIndex = n, durationSeconds = 0.0, thumbPath = null,
        season = null, episode = n,
    )

    private fun detail(vararg progress: Pair<Int, PlaybackEntity>) = ItemDetail(
        identifier = "magis:ABC",
        title = "Dragon Ball Daima T1",
        description = null,
        thumbnailUrl = "",
        episodes = (1..5).map { ep(it) },
        progress = progress.associate { (n, p) -> ep(n).id to p },
    )

    private fun seen(now: Long) =
        PlaybackEntity("", positionMs = 600_000L, durationMs = 600_000L, watched = true, lastPlayedAt = now)

    private fun partiallyWatched(now: Long) =
        PlaybackEntity("", positionMs = 120_000L, durationMs = 600_000L, watched = false, lastPlayedAt = now)

    /** Just hit play: there's a row, but still no position or duration. */
    private fun justTapped(now: Long) =
        PlaybackEntity("", positionMs = 0L, durationMs = 0L, watched = false, lastPlayedAt = now)

    @Test fun `with nothing started yet, you're on the first one`() {
        assertEquals("magis:ABC::e1", detail().resumeEpisode?.id)
    }

    @Test fun `a chapter that's partway through is where you're at`() {
        val d = detail(1 to seen(10L), 2 to partiallyWatched(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
        assertEquals("magis:ABC::e2", d.resumeEpisode?.id)
    }

    @Test fun `a chapter just tapped is already where you're at`() {
        // `saveProgress` writes nothing until it knows the duration, and on Magis the probe takes
        // a while: without this, leaving three seconds into E3 left the detail saying "you're on E1".
        val d = detail(3 to justTapped(30L))
        assertEquals("magis:ABC::e3", d.inProgressEpisode?.id)
    }

    @Test fun `a chapter that was only tapped doesn't beat one with real progress`() {
        // Dragon Ball, seen on the Fire TV's database on 2026-08-12: e126 had 3:30 played, and
        // e127/e128 were left with a `markInProgress` row (position and duration at 0) from opening
        // them without anything ever playing. Since those rows are MORE recent, "where am I at"
        // was answering e128 while "Continue watching" —which does filter by position— kept
        // offering e126: two surfaces with two answers to the same question.
        val d = detail(2 to partiallyWatched(20L), 4 to justTapped(90L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
        assertEquals("magis:ABC::e2", d.resumeEpisode?.id)
    }

    @Test fun `if you finished the third one, you're on the fourth`() {
        // It used to fall back to E1 as soon as the chapter switched to `watched`.
        val d = detail(1 to seen(10L), 2 to seen(20L), 3 to seen(30L))
        assertEquals(null, d.inProgressEpisode)
        assertEquals("magis:ABC::e4", d.resumeEpisode?.id)
    }

    @Test fun `with everything watched, it goes back to the first one`() {
        val d = detail(1 to seen(10L), 2 to seen(20L), 3 to seen(30L), 4 to seen(40L), 5 to seen(50L))
        assertEquals("magis:ABC::e1", d.resumeEpisode?.id)
    }

    @Test fun `the last one tapped wins, not the one with the highest number`() {
        val d = detail(2 to partiallyWatched(90L), 5 to partiallyWatched(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
    }

    @Test fun `you finished a chapter in the middle, you're on the next one`() {
        // The case that motivated the fix: with the whole season saved at once (see
        // `addMagisSeason`), tapping and finishing ONLY E3 leaves no trace on E1-E2 or E4-E5 --
        // it used to fall back to E1 because "the first one unwatched" by order always won.
        val d = detail(3 to seen(30L))
        assertEquals("magis:ABC::e4", d.resumeEpisode?.id)
    }

    @Test fun `with watched episodes skipped around, the most recent one wins, not the highest-numbered one`() {
        // Watching E5 on its own (spoiler/curiosity) a while ago and then starting in order and
        // finishing E2 has to offer E3 -- NOT E6. Reasoning by position in the list (the "furthest
        // along" one watched) instead of by `lastPlayedAt` gives E6 here, skipping over E3 and E4.
        val d = detail(5 to seen(10L), 2 to seen(90L))
        assertEquals("magis:ABC::e3", d.resumeEpisode?.id)
    }
}
