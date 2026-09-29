package com.arkiv.player.ui.settings

import com.arkiv.player.data.subtitles.PlaybackPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleOptionsTest {

    @Test
    fun `the size goes up through the presets and wraps back to the smallest`() {
        assertEquals(100, SubtitleStyleOptions.nextSize(80))
        assertEquals(120, SubtitleStyleOptions.nextSize(100))
        assertEquals(200, SubtitleStyleOptions.nextSize(150))
        assertEquals(80, SubtitleStyleOptions.nextSize(200))
    }

    @Test
    fun `a size from the phone's slider goes to the next preset above it`() {
        assertEquals(120, SubtitleStyleOptions.nextSize(101))
        assertEquals(150, SubtitleStyleOptions.nextSize(137))
        assertEquals(80, SubtitleStyleOptions.nextSize(199 + 1))
    }

    @Test
    fun `a size below the smallest preset goes to the smallest`() {
        assertEquals(80, SubtitleStyleOptions.nextSize(60))
    }

    @Test
    fun `colors cycle white, yellow, cyan, green and back`() {
        val order = listOf(0xFFFFFFFFL, 0xFFFFEB3BL, 0xFF00E5FFL, 0xFF00E676L)
        order.forEachIndexed { i, c ->
            assertEquals(order[(i + 1) % order.size], SubtitleStyleOptions.next(SubtitleStyleOptions.COLORS, c))
        }
    }

    @Test
    fun `a value that is not in the list goes to the first option`() {
        assertEquals(0xFFFFFFFFL, SubtitleStyleOptions.next(SubtitleStyleOptions.COLORS, 0xFF123456L))
    }

    @Test
    fun `borders cycle outline, shadow, none`() {
        assertEquals(PlaybackPrefs.EDGE_SHADOW, SubtitleStyleOptions.next(SubtitleStyleOptions.EDGES, PlaybackPrefs.EDGE_OUTLINE))
        assertEquals(PlaybackPrefs.EDGE_NONE, SubtitleStyleOptions.next(SubtitleStyleOptions.EDGES, PlaybackPrefs.EDGE_SHADOW))
        assertEquals(PlaybackPrefs.EDGE_OUTLINE, SubtitleStyleOptions.next(SubtitleStyleOptions.EDGES, PlaybackPrefs.EDGE_NONE))
    }

    @Test
    fun `labels are in Spanish and an unknown value reads Personalizado`() {
        assertEquals("Amarillo", SubtitleStyleOptions.label(SubtitleStyleOptions.COLORS, 0xFFFFEB3BL))
        assertEquals("Sin fondo", SubtitleStyleOptions.label(SubtitleStyleOptions.BACKGROUNDS, 0x00000000L))
        assertEquals("Personalizado", SubtitleStyleOptions.label(SubtitleStyleOptions.COLORS, 0xFF123456L))
    }

    @Test
    fun `the defaults of the prefs are options in the lists`() {
        val p = PlaybackPrefs()
        assertEquals("Blanco", SubtitleStyleOptions.label(SubtitleStyleOptions.COLORS, p.textColor))
        assertEquals("Semitransparente", SubtitleStyleOptions.label(SubtitleStyleOptions.BACKGROUNDS, p.backgroundColor))
        assertEquals("Contorno", SubtitleStyleOptions.label(SubtitleStyleOptions.EDGES, p.edge))
    }
}
