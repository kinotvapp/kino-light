package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode

/**
 * Decides which subtitle track to activate, as a PURE function so it can be tested without a real
 * player instance.
 *
 * The rule: subtitles turn on only if the audio that ended up PLAYING isn't in a language you said
 * you understand ([PlaybackPrefs.understoodLangs]). If the audio is already understood, subtitling
 * it is redundant. NOTE that [PlaybackPrefs.audioLangs] is NOT consulted: that list is only the
 * order used to pick the track, and manually picking an anime's Japanese reorders it -- it doesn't
 * mean you understand Japanese.
 */
object SubtitleDecision {

    /**
     * SPU track id to activate. `-1` = off.
     *
     * [spuClassifier] can be overridden for external tracks whose name doesn't give away the
     * language (the ones from a web source, which arrive as an opaque CDN URL but with their
     * language declared separately).
     */
    fun decide(
        audioTrackName: String?,
        spuTracks: List<Pair<Int, String>>,
        prefs: PlaybackPrefs,
        spuClassifier: (String) -> TrackLang = LangTokens::classifyFileName,
    ): Int {
        if (prefs.subtitleMode == SubtitleMode.OFF) return OFF
        val audioLang = audioTrackName?.let { LangTokens.classify(it) } ?: TrackLang.UNKNOWN
        // Untagged track ("Track 1"): assume it's your language. The alternative would show
        // subtitles on any normal film whose MKV doesn't tag its audio.
        if (audioLang == TrackLang.UNKNOWN) return OFF
        if (LangTokens.satisfies(audioLang, prefs.understoodLangs)) return OFF
        // Foreign audio -> look for a subtitle. requireChoice=false: a single subtitle in your
        // language must be turned on regardless, even with nothing else to choose between.
        return TrackSelector.select(
            tracks = spuTracks,
            order = prefs.subtitleLangs,
            requireChoice = false,
            classifier = spuClassifier,
        ) ?: OFF
    }

    const val OFF = -1
}
