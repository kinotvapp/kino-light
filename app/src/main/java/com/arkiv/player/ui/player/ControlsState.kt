package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Inactivity after which the overlay auto-hides, while the video is playing. */
private const val INACTIVITY_MS = 4500L

/**
 * Whether the VOD controls overlay is on screen, and its auto-hide.
 *
 * Live mode does NOT use this: it has its own visible+tick pair in [LiveState], because the
 * progress bar and transport row these two variables govern don't exist in a live stream.
 *
 * Starts hidden on purpose: on open, the loading spinner shows and then the clean video, without
 * the pause overlay on top. You have to touch the screen (or any key on TV) to bring it.
 */
@Stable
internal class ControlsState {
    var visible by mutableStateOf(false)
        private set

    /**
     * Bumps with each activity signal and resets the auto-hide countdown. It's a tick and not a
     * timestamp because all that's needed is to relaunch the effect: two interactions in the same
     * millisecond have to count as two.
     */
    var activityTick by mutableIntStateOf(0)
        private set

    /** There was activity: brings the overlay and resets the countdown. The usual `bump()`. */
    fun bump() {
        visible = true
        activityTick++
    }

    /**
     * Only resets the countdown, without bringing the overlay. Used by D-pad navigation INSIDE the
     * already-open overlay: moving between buttons shouldn't let it fade mid-navigation.
     */
    fun keepAlive() {
        activityTick++
    }

    fun hide() {
        visible = false
    }

    /** A tap on the video: if the overlay is up it takes it out, and if not, it brings it. */
    fun toggle() {
        if (visible) hide() else bump()
    }
}

@Composable
internal fun rememberControlsState(): ControlsState = remember { ControlsState() }

/**
 * Auto-hides the overlay after [INACTIVITY_MS] of real inactivity.
 *
 * The tick resets with every key while the overlay is open (see the container's
 * onPreviewKeyEvent), so it doesn't fade mid-navigation of buttons or thumbnails.
 *
 * It's NOT fully blocked on purpose: auto-hide is the only way out when the video is paused
 * —BACK also closes the overlay, but only while playing—. That's why it needs [playing]: while
 * paused the overlay stays.
 *
 * [carouselRevealed] goes as a key so opening or closing the chapter carousel starts a fresh
 * timer.
 */
@Composable
internal fun AutoHideEffect(
    state: ControlsState,
    playing: Boolean,
    marking: Boolean,
    carouselRevealed: Boolean,
) {
    LaunchedEffect(state.activityTick, playing, state.visible, carouselRevealed) {
        if (state.visible && playing && !marking) {
            delay(INACTIVITY_MS)
            state.hide()
        }
    }
}
