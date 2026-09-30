package com.arkiv.player.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** What the app bar can ask of the En vivo screen (the "+" menu). */
enum class LiveChromeAction { ADD_CHANNEL, ADD_PLAYLIST, MANAGE }

/**
 * The bridge between the phone's app bar (guide toggle, "Recargar", "+") and the En vivo screen below it.
 * The buttons live in the bar, the data lives in the screen's view model: the bar only ASKS (a reload tick, a
 * one-shot action) and the screen answers; the screen tells the bar whether the guide toggle applies.
 */
@Stable
class LiveChromeState {
    var hasGuide by mutableStateOf(false)
        private set
    var guideMode by mutableStateOf(false)
        private set
    var reloadTick by mutableIntStateOf(0)
        private set
    /** What the bar asked for and the screen has not taken yet (observable, so the screen reacts to it). */
    var pendingAction by mutableStateOf<LiveChromeAction?>(null)
        private set

    fun requestReload() {
        reloadTick++
    }

    fun request(action: LiveChromeAction) {
        pendingAction = action
    }

    /** The action the bar asked for, once; null when there is none. */
    fun consumeAction(): LiveChromeAction? = pendingAction.also { pendingAction = null }

    /** The provider on screen can (or cannot) have a guide. Losing it puts the grid back. */
    fun updateHasGuide(value: Boolean) {
        hasGuide = value
        if (!value) guideMode = false
    }

    fun toggleGuide() {
        if (hasGuide) guideMode = !guideMode
    }

    companion object {
        fun save(state: LiveChromeState): Boolean = state.guideMode

        fun restore(guideMode: Boolean): LiveChromeState = LiveChromeState().also {
            // hasGuide comes back with the screen; until then the saved mode is kept as it was.
            it.hasGuide = guideMode
            it.guideMode = guideMode
        }
    }
}

/** One per app-bar + screen pair; the guide mode survives a rotation, as it did when it lived in the screen. */
@Composable
fun rememberLiveChromeState(): LiveChromeState = rememberSaveable(
    saver = Saver(save = { LiveChromeState.save(it) }, restore = { LiveChromeState.restore(it) }),
) { LiveChromeState() }
