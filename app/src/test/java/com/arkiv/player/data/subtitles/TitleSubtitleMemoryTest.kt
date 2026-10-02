package com.arkiv.player.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleSubtitleMemoryTest {
    private val tracks = listOf(0 to "Español", 1 to "Inglés", 2 to "Inglés (SDH)")

    // The Redmi (2026-10-01): a Xuper title reopened with "Continuar" lost its subtitle.
    @Test fun `a title reopens with the subtitle picked by hand, by its label`() {
        assertEquals(2, TitleSubtitleMemory.restore("Inglés (SDH)", tracks))
        assertEquals(0, TitleSubtitleMemory.restore("Español", tracks))
    }

    @Test fun `a title whose subtitles were turned off reopens with them off`() {
        assertEquals(-1, TitleSubtitleMemory.restore(TitleSubtitleMemory.OFF, tracks))
    }

    @Test fun `a label that changed falls back to the same language`() {
        assertEquals(1, TitleSubtitleMemory.restore("English", tracks))
    }

    @Test fun `nothing remembered or nothing matching leaves it to the language rule`() {
        assertNull(TitleSubtitleMemory.restore(null, tracks))
        assertNull(TitleSubtitleMemory.restore("S9", tracks))
        assertNull(TitleSubtitleMemory.restore("Español", emptyList()))
    }

    @Test fun `the memory round-trips and keeps the latest pick per title`() {
        var entries = TitleSubtitleMemory.remember(emptyList(), "plugin:xuper:movie:1", "Inglés")
        entries = TitleSubtitleMemory.remember(entries, "plugin:xuper:movie:2", TitleSubtitleMemory.OFF)
        entries = TitleSubtitleMemory.remember(entries, "plugin:xuper:movie:1", "Español")
        val back = TitleSubtitleMemory.decode(TitleSubtitleMemory.encode(entries))
        assertEquals(listOf("plugin:xuper:movie:2" to TitleSubtitleMemory.OFF, "plugin:xuper:movie:1" to "Español"), back)
    }

    @Test fun `only the most recent titles are kept`() {
        var entries = emptyList<Pair<String, String>>()
        repeat(TitleSubtitleMemory.MAX_TITLES + 5) { entries = TitleSubtitleMemory.remember(entries, "t$it", "Español") }
        assertEquals(TitleSubtitleMemory.MAX_TITLES, entries.size)
        assertEquals("t5", entries.first().first)
    }

    @Test fun `a broken stored value reads as nothing remembered`() {
        assertEquals(emptyList<Pair<String, String>>(), TitleSubtitleMemory.decode(null))
        assertEquals(emptyList<Pair<String, String>>(), TitleSubtitleMemory.decode("garbage"))
    }
}
