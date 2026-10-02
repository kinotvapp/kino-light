package com.arkiv.player.data.local

import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 0.9.46 rule: a downloaded MP4 keeps at most three audio tracks, the original always among them. */
class Mp4AudioKeepTest {

    private val prefs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.ENGLISH)

    private fun t(label: String?, lang: String?, default: Boolean = false) = AudioTrackInfo(label, lang, default)

    private val latino = t("Español latino", "spa")
    private val castellano = t("Castellano", "spa")
    private val english = t(null, "eng")
    private val korean = t(null, "kor")
    private val german = t(null, "ger")
    private val japanese = t(null, "jpn")

    @Test
    fun threeTracksOrFewerStayUntouched() {
        assertEquals(listOf(0, 1, 2), Mp4AudioKeep.select(listOf(korean, english, latino), prefs))
        assertEquals(listOf(0), Mp4AudioKeep.select(listOf(korean), prefs))
        assertEquals(emptyList<Int>(), Mp4AudioKeep.select(emptyList(), prefs))
    }

    @Test
    fun fourTracksKeepThePreferredInTheirOrder() {
        // The original is the first track (english); preferences: Latino, Castellano, then English.
        val tracks = listOf(english, castellano, latino, german)
        assertEquals(listOf(2, 1, 0), Mp4AudioKeep.select(tracks, prefs))
    }

    @Test
    fun theOriginalIsKeptEvenWhenItLandsLast() {
        // Korean film: spa, spa, kor, eng. Korean is the default (original); English is the one dropped.
        val tracks = listOf(latino, castellano, t(null, "kor", default = true), english)
        assertEquals(listOf(0, 1, 2), Mp4AudioKeep.select(tracks, prefs))
    }

    @Test
    fun duplicateLanguagesEachCountAsATrack() {
        val spa2 = t("Español", "spa")
        val tracks = listOf(latino, castellano, spa2, english, german)
        // Original is the first (Latino); the three Spanish tracks fill the slots (the generic one
        // stands for the first Spanish preference), English is out.
        assertEquals(listOf(0, 2, 1), Mp4AudioKeep.select(tracks, prefs))
    }

    @Test
    fun noPreferredLanguageKeepsTheOriginalPlusTwoInFileOrder() {
        val tracks = listOf(german, korean, t(null, "fre"), t(null, "ita"), t(null, "por"))
        assertEquals(listOf(0, 1, 2), Mp4AudioKeep.select(tracks, prefs))
        // The original is the flagged one, not the first.
        val flagged = listOf(german, korean, t(null, "fre"), t(null, "ita", default = true), t(null, "por"))
        assertEquals(listOf(0, 1, 3), Mp4AudioKeep.select(flagged, prefs))
    }

    @Test
    fun theDefaultFlagMarksTheOriginal() {
        val tracks = listOf(latino, castellano, english, t(null, "jpn", default = true), german)
        assertEquals(listOf(0, 1, 3), Mp4AudioKeep.select(tracks, prefs))
    }

    @Test
    fun theExplicitPickIsAlwaysIncluded() {
        val tracks = listOf(korean, latino, castellano, english, japanese)
        // Pick: Japanese, which is not preferred. Slots: pick + original (Korean) + the first preference.
        val kept = Mp4AudioKeep.select(tracks, prefs, pickedLabel = "Japonés")
        assertEquals(listOf(1, 0, 4), kept)
        // Picked by exact label.
        assertEquals(listOf(1, 2, 0), Mp4AudioKeep.select(tracks, prefs, pickedLabel = "Castellano"))
    }

    @Test
    fun anUnknownPickChangesNothing() {
        val tracks = listOf(korean, latino, castellano, english)
        assertEquals(Mp4AudioKeep.select(tracks, prefs), Mp4AudioKeep.select(tracks, prefs, pickedLabel = "Whatever"))
    }

    @Test
    fun firstPreferenceGoesFirstSoItBecomesTheDefault() {
        val tracks = listOf(english, korean, latino, german)
        val kept = Mp4AudioKeep.select(tracks, listOf(TrackLang.LATINO, TrackLang.ENGLISH))
        // Only what is preferred plus the original: Korean and German are not wanted.
        assertEquals(listOf(2, 0), kept)
    }
}
