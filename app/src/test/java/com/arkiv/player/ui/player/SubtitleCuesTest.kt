package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure stacking rule behind [stackOverlappingCues]: how the simultaneous bottom (position-less)
 * subtitle cues combine into one multi-line cue so SRT entries overlapping in time stack instead of
 * smearing on top of each other. The Cue partitioning (an Android type) is verified on-device.
 */
class SubtitleCuesTest {

    @Test
    fun twoOverlappingLinesStackTopToBottomInOrder() {
        assertEquals(
            "Episodio 12: verano\nHimeko, el desayuno está listo.",
            mergeBottomCueTexts(listOf("Episodio 12: verano", "Himeko, el desayuno está listo.")),
        )
    }

    @Test
    fun blankAndWhitespaceCuesAreDropped() {
        // An empty overlapping cue must not add a stray blank line.
        assertEquals(
            "línea real",
            mergeBottomCueTexts(listOf("   ", "línea real", "")),
        )
    }

    @Test
    fun eachLineIsTrimmed() {
        assertEquals("uno\ndos", mergeBottomCueTexts(listOf("  uno  ", " dos")))
    }

    @Test
    fun singleLineIsReturnedAsIs() {
        assertEquals("solo esta", mergeBottomCueTexts(listOf("solo esta")))
    }

    @Test
    fun allBlankYieldsEmpty() {
        assertEquals("", mergeBottomCueTexts(listOf("", "   ")))
    }
}
