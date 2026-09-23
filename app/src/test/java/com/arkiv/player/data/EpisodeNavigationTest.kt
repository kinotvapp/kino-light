package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNavigationTest {

    private val series = listOf(
        NavEpisode("t1e1", "Temporada 1"),
        NavEpisode("t1e2", "Temporada 1"),
        NavEpisode("t1e3", "Temporada 1"),
        NavEpisode("t2e1", "Temporada 2"),
    )

    @Test
    fun `next returns the next one in the same section`() {
        assertEquals("t1e2", EpisodeNavigation.nextId(series, "t1e1"))
    }

    @Test
    fun `next doesn't cross into another section`() {
        assertNull(EpisodeNavigation.nextId(series, "t1e3"))
    }

    @Test
    fun `prev returns the previous one in the same section`() {
        assertEquals("t1e2", EpisodeNavigation.prevId(series, "t1e3"))
    }

    @Test
    fun `prev doesn't cross into another section`() {
        assertNull(EpisodeNavigation.prevId(series, "t2e1"))
    }

    @Test
    fun `an unknown episode returns null`() {
        assertNull(EpisodeNavigation.nextId(series, "nope"))
        assertNull(EpisodeNavigation.prevId(series, "nope"))
    }

    @Test
    fun `a single-episode movie has no neighbors`() {
        val movie = listOf(NavEpisode("solo", ""))
        assertNull(EpisodeNavigation.nextId(movie, "solo"))
        assertNull(EpisodeNavigation.prevId(movie, "solo"))
    }
}
