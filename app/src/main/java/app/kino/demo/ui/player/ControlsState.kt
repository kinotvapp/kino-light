package app.kino.demo.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Inactivity after which the overlay hides itself while the video plays. */
private const val INACTIVITY_MS = 4500L

/** Whether the controls overlay is on screen, and its auto-hide. Starts hidden: the video first. */
@Stable
internal class ControlsState {
    var visible by mutableStateOf(false)
        private set

    /** Bumps with each activity signal; relaunches the auto-hide countdown. */
    var activityTick by mutableIntStateOf(0)
        private set

    /** There was activity: brings the overlay and resets the countdown. */
    fun bump() {
        visible = true
        activityTick++
    }

    /** Only resets the countdown (D-pad moves inside the open overlay). */
    fun keepAlive() {
        activityTick++
    }

    fun hide() {
        visible = false
    }

    /** A tap on the video: hides the overlay if it is up, brings it if not. */
    fun toggle() {
        if (visible) hide() else bump()
    }
}

@Composable
internal fun rememberControlsState(): ControlsState = remember { ControlsState() }

/** Hides the overlay after [INACTIVITY_MS] without activity, only while playing: paused, it stays. */
@Composable
internal fun AutoHideEffect(state: ControlsState, playing: Boolean) {
    LaunchedEffect(state.activityTick, playing, state.visible) {
        if (state.visible && playing) {
            delay(INACTIVITY_MS)
            state.hide()
        }
    }
}
