package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import com.arkiv.player.data.subtitles.PlaybackPrefs
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

    // --- which style the view gets, and how big

    @Test
    fun `a phone always gets the app's own style`() {
        assertEquals(false, usesSystemSubtitleStyle(PlaybackPrefs(), isTv = false))
    }

    @Test
    fun `a TV gets the system style until the person chooses their own`() {
        assertEquals(true, usesSystemSubtitleStyle(PlaybackPrefs(), isTv = true))
        assertEquals(false, usesSystemSubtitleStyle(PlaybackPrefs(tvCustomStyle = true), isTv = true))
    }

    @Test
    fun `text size is the base times the percentage, a bigger base on a TV`() {
        assertEquals(20f, subtitleTextSizeSp(100, isTv = false), 0.001f)
        assertEquals(30f, subtitleTextSizeSp(150, isTv = false), 0.001f)
        assertEquals(28f, subtitleTextSizeSp(100, isTv = true), 0.001f)
        assertEquals(56f, subtitleTextSizeSp(200, isTv = true), 0.001f)
    }
}
