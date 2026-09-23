package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapters window with several seasons. What's really protected is the first half: that a
 * Magis list (with no season) looks exactly as it did before.
 */
class ChaptersBySeasonTest {

    private fun chapter(number: Int, season: Int? = null) =
        GatewayEpisode(number = number, title = "Episodio $number", ref = "r-$season-$number", season = season)

    @Test fun `magis, with no season, stays as it was`() {
        val list = listOf(chapter(3), chapter(1), chapter(2))

        assertFalse(ChaptersBySeason.hasMultipleSeasons(list))
        // Same order it arrived in: the window doesn't reorder Magis.
        assertEquals(list, ChaptersBySeason.sorted(list))
        assertEquals("3", ChaptersBySeason.label(chapter(3), multipleSeasons = false))
        assertEquals("E3", ChaptersBySeason.label(chapter(3), multipleSeasons = false, noSeason = "E"))
    }

    @Test fun `a single season doesn't change anything either`() {
        val list = listOf(chapter(2, 1), chapter(1, 1))
        val multiple = ChaptersBySeason.hasMultipleSeasons(list)

        assertFalse(multiple)
        assertEquals(list, ChaptersBySeason.sorted(list))
        assertEquals("2", ChaptersBySeason.label(chapter(2, 1), multiple))
    }

    @Test fun `with several seasons each row states its own`() {
        val list = listOf(chapter(1, 1), chapter(1, 2))
        val multiple = ChaptersBySeason.hasMultipleSeasons(list)

        assertTrue(multiple)
        assertEquals("T2 · E1", ChaptersBySeason.label(chapter(1, 2), multiple))
        assertEquals("T2 · E1", ChaptersBySeason.label(chapter(1, 2), multiple, noSeason = "E"))
        // T1's 1 and T2's 1 no longer look the same.
        assertNotEquals(
            ChaptersBySeason.label(chapter(1, 1), multiple),
            ChaptersBySeason.label(chapter(1, 2), multiple),
        )
    }

    @Test fun `with several seasons it goes by season and then by number`() {
        val list = listOf(chapter(1, 2), chapter(2, 1), chapter(1, 1))

        assertEquals(listOf(chapter(1, 1), chapter(2, 1), chapter(1, 2)), ChaptersBySeason.sorted(list))
    }
}
