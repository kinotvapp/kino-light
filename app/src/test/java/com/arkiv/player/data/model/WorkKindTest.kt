package com.arkiv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkKindTest {

    @Test fun `the type is told by the item when it knows it`() {
        assertEquals("tv", WorkKind.of(itemKind = "tv", categoryOverride = null, episode = null))
        assertEquals("movie", WorkKind.of(itemKind = "movie", categoryOverride = null, episode = 7))
    }

    @Test fun `with no type it goes by whether there is an episode number`() {
        assertEquals("tv", WorkKind.of(itemKind = null, categoryOverride = null, episode = 7))
        assertEquals("movie", WorkKind.of(itemKind = "", categoryOverride = null, episode = null))
    }

    /**
     * Measured in production: `movie:82452` was requested for Avatar. On TMDB, tv:82452 is
     * "Avatar: The Last Airbender" and movie:82452 is "Savage Water", a 1979 rafting movie.
     */
    @Test fun `a series with no episode number is NOT requested as a movie`() {
        assertEquals("tv", WorkKind.of(itemKind = null, categoryOverride = "series", episode = null))
    }

    @Test fun `the item's own type wins over categoryOverride`() {
        assertEquals("movie", WorkKind.of(itemKind = "movie", categoryOverride = "series", episode = 3))
    }

    @Test fun `with no signal at all it is still a movie`() {
        assertEquals("movie", WorkKind.of(null, categoryOverride = null, episode = null))
    }
}
