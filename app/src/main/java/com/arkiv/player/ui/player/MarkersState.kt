package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Which end of the chapter is being marked. */
internal enum class MarkMode { INTRO, OUTRO }

/**
 * Mark the end of the intro and the start of the credits (archive only), so the "Skip
 * intro/outro" buttons know where they are.
 *
 * While marking, the player enters a separate mode: the normal controls hide —any guard that says
 * `marking == null` is about this— and the editor panel with its own slider shows in their place.
 */
@Stable
internal class MarkersState {
    /** The end being marked right now, or null if we're not marking. */
    var mode by mutableStateOf<MarkMode?>(null)
        private set

    /** The dropdown menu to choose what to mark is open. */
    var menuOpen by mutableStateOf(false)
        private set

    /**
     * The "fix this chapter's times" menu, the only one reachable on Fire TV. It's a separate
     * state from [menuOpen] (the old SERIES editor, which stays behind its flag on the phone):
     * sharing it would open both menus at once the day that flag turns on.
     */
    var chapterMenuOpen by mutableStateOf(false)
        private set

    /**
     * We were already marking on this screen. Exists only so the exit `play()` does NOT run on
     * the initial composition: without this flag, opening a new web source would have that play
     * revive the previous video —still loaded in the service— behind the "Resolving…" overlay
     * while the new one resolves.
     */
    private var wasMarking = false

    /** We're marking: the normal controls give way to the editor panel. */
    val marking: Boolean get() = mode != null

    fun openMenu() {
        menuOpen = true
    }

    fun closeMenu() {
        menuOpen = false
    }

    fun openChapterMenu() {
        chapterMenuOpen = true
    }

    fun closeChapterMenu() {
        chapterMenuOpen = false
    }

    /** Choosing what to mark closes the menu and enters the mode. */
    fun mark(which: MarkMode) {
        menuOpen = false
        mode = which
    }

    fun finish() {
        mode = null
    }

    /**
     * What to do when entering or leaving the mode, for the effect that accompanies it: `true` =
     * we just entered (pause and place the slider), `false` = we just left (resume), `null` = no
     * transition happened and the player isn't touched.
     */
    fun transition(): Boolean? = when {
        mode != null -> {
            wasMarking = true
            true
        }

        wasMarking -> {
            wasMarking = false
            false
        }

        else -> null
    }
}

@Composable
internal fun rememberMarkersState(): MarkersState = remember { MarkersState() }
