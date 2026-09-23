package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chapter's data line the home hero shows under the title.
 *
 * The rule that governs all of this: each part is OMITTED when unknown, never made up. The hero
 * is the first thing read on the screen, so a made-up value there is worse than a missing one.
 */
class ChapterLabelHeroTest {

    private fun line(
        isMovie: Boolean = false,
        season: Int? = 1,
        episode: Int? = 5,
        orderIndex: Int = 4,
        itemId: String = "torrent:series:tt0903747",
        section: String = "Temporada 1",
        name: String? = "La conspiración",
        positionMs: Long = 3 * 60_000L,
        durationMs: Long = 15 * 60_000L,
    ) = ChapterLabel.heroLine(
        isMovie = isMovie,
        season = season,
        episode = episode,
        orderIndex = orderIndex,
        itemId = itemId,
        section = section,
        name = name,
        positionMs = positionMs,
        durationMs = durationMs,
    )

    @Test
    fun `with everything resolved brings number, name and what's left`() {
        assertEquals("T1 · E5  ·  La conspiración  ·  te faltan 12 min", line())
    }

    @Test
    fun `with no TMDB name only the number and time are left`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", line(name = null))
    }

    /** A blank name is the same as having none: it doesn't leave a dangling separator. */
    @Test
    fun `a blank name is treated as absent`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", line(name = "   "))
    }

    /** Magis with the duration probe still pending: `durationMs` arrives as 0. */
    @Test
    fun `with no known duration the time isn't made up`() {
        assertEquals("T1 · E5  ·  La conspiración", line(durationMs = 0L))
    }

    @Test
    fun `with no season but with a chapter, the season isn't made up`() {
        assertEquals("E5  ·  La conspiración  ·  te faltan 12 min", line(season = null))
    }

    /** On torrent and web the `orderIndex` encodes season*1000 + chapter. */
    @Test
    fun `with no numbering falls back to a torrent pack's orderIndex`() {
        assertEquals(
            "T2 · E3  ·  La conspiración  ·  te faltan 12 min",
            line(season = null, episode = null, orderIndex = 2003, section = "Temporada 2"),
        )
    }

    /** On archive.org the `orderIndex` is a correlative starting at 0. */
    @Test
    fun `with no numbering falls back to archive's correlative orderIndex`() {
        assertEquals(
            "E1  ·  La conspiración  ·  te faltan 12 min",
            line(season = null, episode = null, orderIndex = 0, itemId = "mi-serie", section = ""),
        )
    }

    /**
     * Specials (season 0). Their `orderIndex` is 0*1000 + N, i.e. BELOW 1000, which is exactly
     * where the old rule confused it with an archive.org correlative and shifted the number up by
     * one: special 3 came out as "E4".
     */
    @Test
    fun `a season 0 special is numbered as T0`() {
        assertEquals(
            "T0 · E3  ·  La conspiración  ·  te faltan 12 min",
            line(season = null, episode = null, orderIndex = 3, section = "Temporada 0"),
        )
    }

    /** A movie has no season or chapter: an "E1" there would be noise. */
    @Test
    fun `a movie only says what's left`() {
        assertEquals("te faltan 12 min", line(isMovie = true))
    }

    @Test
    fun `a movie with no known duration says nothing`() {
        assertEquals("", line(isMovie = true, durationMs = 0L))
    }

    /** What's shown is what's LEFT, not what's elapsed. */
    @Test
    fun `just started, almost the whole chapter is ahead`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 24 min",
            line(positionMs = 60_000L, durationMs = 25 * 60_000L),
        )
    }

    /** Under a minute from the end there's nothing useful left to say about the time. */
    @Test
    fun `almost finished no longer shows the time`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            line(positionMs = 25 * 60_000L - 30_000L, durationMs = 25 * 60_000L),
        )
    }

    // --- Remaining-time arithmetic edges --------------------------------------------------------

    /** The time shown is the whole amount left, not rounded up. */
    @Test
    fun `truncates incomplete minutes downward`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 min",
            line(positionMs = 0L, durationMs = 90_000L),  // remaining = 90_000 ms = 1.5 min → "1 min"
        )
    }

    /** The minimum threshold's edge: exactly 60_000 ms shows. */
    @Test
    fun `at the exact 60-second threshold the time does show`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 min",
            line(positionMs = 14 * 60_000L, durationMs = 15 * 60_000L),  // remaining = exactly 60_000 ms
        )
    }

    /** Just below the threshold: 59_999 ms doesn't show. */
    @Test
    fun `one millisecond below the threshold no longer shows the time`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            line(positionMs = 14 * 60_000L + 1, durationMs = 15 * 60_000L),  // remaining = 59_999 ms
        )
    }

    /** Negative remaining (duration gets re-measured and drops): omitted, never shows negatives. */
    @Test
    fun `negative remaining is omitted without making up a time`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            line(positionMs = 20 * 60_000L, durationMs = 15 * 60_000L),  // remaining = -300_000 ms
        )
    }

    // --- Above an hour reuses formatRuntime, not raw minutes ------------------------------------

    /** A movie just started: above 60 min it switches to hours, like `formatRuntime`. */
    @Test
    fun `more than an hour remaining shows in hours and minutes`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 h 26 min",
            line(positionMs = 0L, durationMs = 86 * 60_000L),
        )
    }

    /** The exactly-60-min edge: with no loose minutes, `formatRuntime` doesn't add them. */
    @Test
    fun `exactly 60 minutes remaining shows as 1 h with no minutes`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 h",
            line(positionMs = 0L, durationMs = 60 * 60_000L),
        )
    }

    // --- The core shared with the detail screen --------------------------------------------------

    @Test
    fun `the number from loose values follows the same rule as the model's`() {
        val torrent = "torrent:series:tt0903747"
        assertEquals("T1 · E5", ChapterLabel.number(1, 5, 4, torrent, "Temporada 1"))
        assertEquals("E5", ChapterLabel.number(null, 5, 4, torrent, "Temporada 1"))
        assertEquals("T2 · E3", ChapterLabel.number(null, null, 2003, torrent, "Temporada 2"))
        assertEquals("T0 · E3", ChapterLabel.number(null, null, 3, torrent, "Temporada 0"))
        assertEquals("E1", ChapterLabel.number(null, null, 0, "mi-serie", ""))
    }
}
