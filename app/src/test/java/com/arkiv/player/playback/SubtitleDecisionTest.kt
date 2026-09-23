package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDecisionTest {

    // Base = the normal case, with both lists equal: what a freshly installed app ships with. There
    // is NO safety net here against accidentally reading `audioLangs` instead of `understoodLangs` —
    // precisely because they're equal, both reads give the same result. That net comes from the two
    // tests that split the lists on purpose: `japaneseAudioStillGetsSubtitlesAfterPromotion`
    // (Japanese ONLY in audioLangs) and `understandingJapaneseTurnsThemOffWithoutTouchingTheAudioOrder`
    // (only in the other one).
    private val prefs = PlaybackPrefs(
        audioLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO),
        understoodLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO),
        subtitleLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    )
    private val subs = listOf(-1 to "Disable", 0 to "English", 1 to "Spanish")

    @Test fun foreignAudioTurnsSubtitlesOnInThePreferredLanguage() {
        assertEquals(1, SubtitleDecision.decide("Japanese", subs, prefs))
    }

    @Test fun audioInMyLanguageLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Español Latino", subs, prefs))
    }

    /** Spanish's wildcard: plain "Spanish" counts as mine with Latino>Castellano. */
    @Test fun genericSpanishAudioCountsAsMine() {
        assertEquals(-1, SubtitleDecision.decide("Track 1 - [Spanish]", subs, prefs))
    }

    /** If you marked that you understand English, English audio stops turning on subs. */
    @Test fun audioInAnAddedLanguageAlsoCountsAsMine() {
        val withEnglish = prefs.copy(understoodLangs = prefs.understoodLangs + TrackLang.ENGLISH)
        assertEquals(-1, SubtitleDecision.decide("English", subs, withEnglish))
    }

    /**
     * The case that motivated splitting the two lists: in a dual anime you pick Japanese by hand,
     * the promotion bumps it to the top of `audioLangs`, and on the next chapter Japanese
     * auto-selects itself. Subtitles HAVE to keep turning on: you never said you understood Japanese.
     */
    @Test fun japaneseAudioStillGetsSubtitlesAfterPromotion() {
        val promoted = prefs.copy(audioLangs = listOf(TrackLang.JAPANESE) + prefs.audioLangs)
        assertEquals(1, SubtitleDecision.decide("Japanese", subs, promoted))
    }

    /** And the other way around: marking Japanese as understood DOES turn them off, even if it's not in audioLangs. */
    @Test fun understandingJapaneseTurnsThemOffWithoutTouchingTheAudioOrder() {
        val understandsJapanese = prefs.copy(understoodLangs = prefs.understoodLangs + TrackLang.JAPANESE)
        assertEquals(-1, SubtitleDecision.decide("Japanese", subs, understandsJapanese))
    }

    /** Track with no label: assumed to be your language. Turning subs on just in case would be worse. */
    @Test fun unknownAudioLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Track 1", subs, prefs))
        assertEquals(-1, SubtitleDecision.decide(null, subs, prefs))
    }

    @Test fun offModeNeverTurnsThemOn() {
        val off = prefs.copy(subtitleMode = SubtitleMode.OFF)
        assertEquals(-1, SubtitleDecision.decide("Japanese", subs, off))
    }

    @Test fun foreignAudioWithNoSubtitleInMyLanguagesStaysOff() {
        val onlyFrench = listOf(-1 to "Disable", 0 to "French")
        assertEquals(-1, SubtitleDecision.decide("Japanese", onlyFrench, prefs))
    }

    /** A single matching subtitle track DOES turn on (unlike audio, no choice is required here). */
    @Test fun aSingleMatchingSubtitleIsSelected() {
        val onlyOne = listOf(0 to "Spanish")
        assertEquals(0, SubtitleDecision.decide("Japanese", onlyOne, prefs))
    }

    /** Injected .srt files are classified by their file name suffix. */
    @Test fun injectedSrtIsPickedByItsFileNameSuffix() {
        val external = listOf(0 to "/data/x/movie.en.srt", 1 to "/data/x/movie.es.srt")
        assertEquals(1, SubtitleDecision.decide("Japanese", external, prefs))
    }

    @Test fun noSubtitleTracksAtAllStaysOff() {
        assertEquals(-1, SubtitleDecision.decide("Japanese", listOf(-1 to "Disable"), prefs))
    }
}
