package com.arkiv.player.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What image shows on a card. The captured frame wins ONLY where it exists, and it exists only if
 * the chapter had progress: that's why "only wins on what's started" doesn't need a progress
 * parameter, it comes from whether the frame is null or not.
 */
class ThumbnailChoiceTest {

    @Test
    fun `the frame beats every fallback`() {
        assertEquals(
            "/data/frames/a.jpg",
            ThumbnailChoice.choose("/data/frames/a.jpg", "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    @Test
    fun `without a frame the first non-empty fallback wins`() {
        assertEquals(
            "https://tmdb/still.jpg",
            ThumbnailChoice.choose(null, "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    /** An empty path is "no frame", not "there is a frame and it's the empty string". */
    @Test
    fun `a blank frame is ignored and falls back`() {
        assertEquals("https://tmdb/still.jpg", ThumbnailChoice.choose("", "https://tmdb/still.jpg"))
        assertEquals("https://tmdb/still.jpg", ThumbnailChoice.choose("   ", "https://tmdb/still.jpg"))
    }

    /** Same rule for the fallbacks: blank ones are skipped instead of showing nothing. */
    @Test
    fun `blank fallbacks are skipped`() {
        assertEquals("https://cdn/caratula.jpg", ThumbnailChoice.choose(null, null, "", "https://cdn/caratula.jpg"))
    }

    @Test
    fun `with nothing at all, returns null`() {
        assertNull(ThumbnailChoice.choose(null, null, ""))
    }
}
