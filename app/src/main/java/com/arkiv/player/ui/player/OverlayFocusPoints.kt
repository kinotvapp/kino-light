package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * D-pad (TV) focus landing points in the pause overlay: real navigation between the buttons and
 * the bar, instead of fixed per-key actions.
 *
 * See the screen's `LaunchedEffect(controlsVisible)`: on the overlay showing, Android's focus
 * moves from the video —which intercepted EVERY key— to these requesters; on hiding, it goes back
 * to the video, for the "any key = show the controls" behavior.
 *
 * Kept together in a single object because they're twelve things from the same mechanism and
 * always travel to the same place. Loose, any composable drawing a piece of the overlay would
 * have to receive them one by one, and that signature grows with every new button.
 */
@Immutable
internal class OverlayFocusPoints {
    /** Icon row: subtitles/audio. */
    val subtitles = FocusRequester()

    /** Night mode's two steps. */
    val dimDown = FocusRequester()
    val dimUp = FocusRequester()

    val trivia = FocusRequester()

    /** Transport row. */
    val rewind = FocusRequester()
    val playPause = FocusRequester()
    val forward = FocusRequester()

    /** Series only, and each one only if that neighbor exists. */
    val previousEpisode = FocusRequester()
    val nextEpisode = FocusRequester()

    /** The progress bar: the point focus enters at when the overlay opens. */
    val bar = FocusRequester()

    /** Manually correct the current chapter's intro/outro times. Last in the row. */
    val markers = FocusRequester()

    /**
     * The floating "Saltar intro"/"Saltar outro" button. The only one in this list that lives
     * OUTSIDE the pause overlay —it shows with the controls hidden, which is when it's needed— and
     * the only one that grabs focus on its own on appearing (see [SkipButtonFocus]).
     */
    val skip = FocusRequester()
}

@Composable
internal fun rememberOverlayFocusPoints(): OverlayFocusPoints = remember { OverlayFocusPoints() }
