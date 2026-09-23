package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPrefsTest {

    @Test fun roundTripPreservesEverything() {
        val p = PlaybackPrefs(
            audioLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            understoodLangs = listOf(TrackLang.ENGLISH, TrackLang.JAPANESE),
            subtitleLangs = listOf(TrackLang.LATINO),
            subtitleMode = SubtitleMode.OFF,
            sizePercent = 140, textColor = 0xFFFFEB3B, backgroundColor = 0xCC000000, edge = 2,
        )
        assertEquals(p, PlaybackPrefs.fromJson(p.toJson()))
    }

    /** An old build sends a JSON with no new fields: it has to fall back to the defaults. */
    @Test fun oldJsonWithoutNewFieldsFallsBackToDefaults() {
        val old = """{"language":"es","sizePercent":120,"textColor":4294967295,"backgroundColor":2147483648,"edge":1}"""
        val p = PlaybackPrefs.fromJson(old)!!
        assertEquals(PlaybackPrefs().audioLangs, p.audioLangs)
        assertEquals(PlaybackPrefs().understoodLangs, p.understoodLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(SubtitleMode.AUTO, p.subtitleMode)
        assertEquals(120, p.sizePercent)
    }

    // --- "languages I understand": migration from audioLangs ---

    /**
     * Before the two lists were split, `audioLangs` also carried the "I understand these" meaning.
     * Reading prefs saved in that shape has to seed from there, not from the defaults: otherwise
     * someone who had `[ENGLISH]` configured would start seeing subtitles over their English audio.
     */
    @Test fun understoodLangsIsSeededFromAudioLangsWhenAbsent() {
        val saved = """{"language":"es","audioLangs":["ENGLISH","LATINO"],"subtitleLangs":["LATINO"]}"""
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            PlaybackPrefs.fromJson(saved)!!.understoodLangs,
        )
    }

    @Test fun understoodLangsFallsBackToTheDefaultWhenThereIsNoAudioLangsEither() {
        assertEquals(
            PlaybackPrefs().understoodLangs,
            PlaybackPrefs.fromJson("""{"language":"es"}""")!!.understoodLangs,
        )
    }

    /** With its own field present, that wins, even if audioLangs says something else. */
    @Test fun understoodLangsWinsOverTheSeedWhenItIsPresent() {
        val json = """{"audioLangs":["JAPANESE"],"understoodLangs":["LATINO"]}"""
        assertEquals(listOf(TrackLang.LATINO), PlaybackPrefs.fromJson(json)!!.understoodLangs)
    }

    /**
     * A future build that adds a `TrackLang` sends names this binary doesn't know. Keeping the
     * empty list would mean "never auto-select audio" and "always turn subtitles on".
     */
    @Test fun anArrayOfUnknownNamesFallsBackInsteadOfEmptyingTheList() {
        val json = """{"audioLangs":["KOREAN"],"subtitleLangs":[],"understoodLangs":["KOREAN"]}"""
        val p = PlaybackPrefs.fromJson(json)!!
        assertEquals(PlaybackPrefs().audioLangs, p.audioLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(PlaybackPrefs().understoodLangs, p.understoodLangs)
    }

    @Test fun legacyLanguageOffMigratesToSubtitleModeOff() {
        val p = PlaybackPrefs.fromJson("""{"language":"off"}""")!!
        assertEquals(SubtitleMode.OFF, p.subtitleMode)
    }

    /** And the other way around: an old build has to keep understanding what we write. */
    @Test fun toJsonStillWritesTheLegacyLanguageField() {
        assertEquals("off", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.OFF).toJson()).getString("language"))
        assertEquals("es", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.AUTO).toJson()).getString("language"))
    }

    @Test fun fromJsonReturnsNullOnGarbage() {
        assertEquals(null, PlaybackPrefs.fromJson("no soy json"))
    }

    // --- a style change must not count as a language change ---

    @Test fun styleOnlyChangesDoNotCountAsALanguageChange() {
        val base = PlaybackPrefs()
        assertTrue(base.sameLanguagesAs(base.copy(sizePercent = 180)))
        assertTrue(base.sameLanguagesAs(base.copy(textColor = 0xFFFFEB3B)))
        assertTrue(base.sameLanguagesAs(base.copy(backgroundColor = 0xCC000000)))
        assertTrue(base.sameLanguagesAs(base.copy(edge = PlaybackPrefs.EDGE_SHADOW)))
    }

    @Test fun everyLanguageFieldCountsAsAChange() {
        val base = PlaybackPrefs()
        assertFalse(base.sameLanguagesAs(base.copy(audioLangs = listOf(TrackLang.JAPANESE))))
        assertFalse(base.sameLanguagesAs(base.copy(understoodLangs = listOf(TrackLang.LATINO))))
        assertFalse(base.sameLanguagesAs(base.copy(subtitleLangs = listOf(TrackLang.ENGLISH))))
        assertFalse(base.sameLanguagesAs(base.copy(subtitleMode = SubtitleMode.OFF)))
    }

    /** Order matters: reordering the preference IS a change, even if the set is the same. */
    @Test fun reorderingIsAChangeEvenWithTheSameLanguages() {
        val base = PlaybackPrefs(audioLangs = listOf(TrackLang.LATINO, TrackLang.JAPANESE))
        assertFalse(base.sameLanguagesAs(base.copy(audioLangs = listOf(TrackLang.JAPANESE, TrackLang.LATINO))))
    }

}
