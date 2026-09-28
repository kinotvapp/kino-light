package com.arkiv.player.ui.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeRevealKeyTest {
    /** A list of [keys] that shows [window] items starting at the last scrolled index. */
    private class FakeList(val keys: List<String>, val window: Int, var first: Int = 0) {
        val scrolls = mutableListOf<Int>()
        fun visible(): List<Any> = keys.drop(first).take(window)
        suspend fun scrollTo(i: Int) { scrolls += i; first = i }
    }

    @Test fun `a key already on screen is not scrolled to`() = runBlocking {
        val list = FakeList(listOf("para_ti", "empty_sources", "pad"), window = 2)
        assertTrue(revealListKey("empty_sources", list.keys.size, list::visible, list::scrollTo))
        assertEquals(emptyList<Int>(), list.scrolls)
    }

    @Test fun `a key below Para ti and live recents is scrolled into view`() = runBlocking {
        val list = FakeList(listOf("para_ti", "live_recientes", "empty_sources", "pad"), window = 1)
        assertTrue(revealListKey("empty_sources", list.keys.size, list::visible, list::scrollTo))
        assertTrue("empty_sources" in list.visible())
    }

    @Test fun `a list scrolled past the key comes back to it`() = runBlocking {
        val list = FakeList(listOf("para_ti", "empty_sources", "a", "b", "pad"), window = 1, first = 3)
        assertTrue(revealListKey("empty_sources", list.keys.size, list::visible, list::scrollTo))
        assertEquals(listOf("empty_sources"), list.visible())
    }

    @Test fun `a key that is not in the list is reported, not looped on`() = runBlocking {
        val list = FakeList(listOf("para_ti", "pad"), window = 1)
        assertFalse(revealListKey("empty_sources", list.keys.size, list::visible, list::scrollTo))
        assertEquals(listOf(0, 1), list.scrolls)
    }
}
