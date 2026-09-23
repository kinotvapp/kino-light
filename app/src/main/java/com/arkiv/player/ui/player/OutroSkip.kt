package com.arkiv.player.ui.player

/**
 * What the "Skip outro" button does when tapped -- and so, whether it's worth drawing it.
 *
 * The button always did `seekToNextMediaItem()`, which only works if the playlist has more than
 * one item. And **archive.org (removed in this branch's pruning) was the only multi-item
 * source**: every source today -- magis, Ditu, and the legacy torrent/web/local rows -- publishes
 * `PlaylistData(listOf(item), …)`, a single item. So `PLAYLIST_ADVANCE` no longer triggers
 * in practice; `decide` would still return it correctly if a playlist ever had more than one
 * item, it just never does today. Back when only archive's loader baked a marker into `PlayerData`
 * for this, the bug didn't show; once the markers started being read from Room instead, the
 * button began showing up on every source, and on the Fire TV with magis -the case that motivated
 * the feature- it showed up in a chapter's last minutes and did nothing when tapped.
 *
 * The good path already existed right next to it: `alTerminarElCapitulo()` navigates to the next
 * chapter's route ([PlayerScreen]'s `onNextEpisode`), which is what restarts resolving the
 * source. Advancing within the playlist is preferred when there is one because it doesn't
 * re-resolve anything (the item's already loaded); otherwise it navigates; and if neither applies
 * -a movie, or the last chapter- the button isn't drawn.
 */
internal object OutroSkip {

    enum class Action {
        /** `seekToNextMediaItem()`: there's another item already loaded in the playlist (archive.org used to produce this; today no source builds a playlist with more than one item). */
        PLAYLIST_ADVANCE,

        /** `onNextEpisode(next)`: the same path as end-of-chapter auto-advance. */
        NEXT_CHAPTER,

        /** Nowhere to skip to: the button isn't drawn. */
        NONE,
    }

    fun decide(currentIndex: Int, playlistItems: Int, nextChapter: String?): Action = when {
        currentIndex in 0 until playlistItems - 1 -> Action.PLAYLIST_ADVANCE
        !nextChapter.isNullOrBlank() -> Action.NEXT_CHAPTER
        else -> Action.NONE
    }
}

/** Which of the two floating buttons is on screen. Never both: see [SkipButtonKind.which]. */
internal enum class SkipButtonKind {
    INTRO,
    OUTRO,
    ;

    companion object {
        /**
         * The button that matches this chapter position, or `null` if none applies.
         *
         * Only ONE comes out even if the intervals overlap: since the button grabs focus on
         * appearing (see [SkipButtonFocus]), two buttons at once are two candidates for focus.
         *
         * Casting, the intro one still comes out —it's a `seekTo` the CastPlayer does just as
         * well— but the outro one doesn't, because it changes chapter and the receiver only has
         * one loaded.
         */
        fun which(
            inOpening: Boolean,
            inEnding: Boolean,
            outroAction: OutroSkip.Action,
            casting: Boolean,
        ): SkipButtonKind? = when {
            inOpening -> INTRO
            inEnding && !casting && outroAction != OutroSkip.Action.NONE -> OUTRO
            else -> null
        }
    }
}

/**
 * Who has the D-pad's focus while the skip button is on screen.
 *
 * Tested on the Fire TV: the button showed up but there was no way to reach it, because it's
 * drawn outside the controls block and outside the focus system (`OverlayFocusPoints.kt`). With
 * focus on the progress bar, pressing OK **paused the video** — the natural gesture did the
 * opposite of what's expected. So the button grabs focus on appearing, like on Netflix or
 * Crunchyroll: with the chapter playing and the button visible, a single OK skips the opening.
 *
 * The two conditions that make it tolerable, and why this is a state machine and not a loose
 * `requestFocus()`:
 *
 * - **Steals focus ONCE.** This gets checked on every position tick; if it requested focus on
 *   every pass, going to the controls would be impossible (half a second later it would go back
 *   to the button). That's why it only acts when WHICH button there is CHANGES, and why the
 *   comparison is against the previous button and not a boolean: intro → outro is a new button and
 *   does request focus.
 * - **Returns focus on disappearing.** If the focused node just leaves, focus ends up orphaned and
 *   the remote stops responding, which is much worse than the original bug. It goes back to the
 *   video (which has the "any key = show the controls" behavior) or to the bar if the overlay is
 *   open. And if by then focus was no longer on the button —the person went to the controls—
 *   nothing gets touched: that would mean taking it away from wherever they put it.
 */
internal class SkipButtonFocus {

    enum class Action { REQUEST, RETURN_TO_CONTROLS, RETURN_TO_VIDEO, NONE }

    private var previous: SkipButtonKind? = null

    fun onChange(button: SkipButtonKind?, hadFocus: Boolean, controlsVisible: Boolean): Action {
        if (button == previous) return Action.NONE
        previous = button
        return when {
            button != null -> Action.REQUEST
            !hadFocus -> Action.NONE
            controlsVisible -> Action.RETURN_TO_CONTROLS
            else -> Action.RETURN_TO_VIDEO
        }
    }
}

/** How many times the skip button's focus is retried, and how long to wait between attempts. */
internal const val SKIP_FOCUS_ATTEMPTS = 10
internal const val WAIT_BETWEEN_FOCUS_ATTEMPTS_MS = 32L

/**
 * Requests focus until getting it, actually waiting between attempts. `true` if it succeeded.
 *
 * Retrying is needed because the node may not be placed yet in the frame it appears in, and
 * because focus is held by an Android `View` (the `videoView`, with the "any key = show the
 * controls" behavior) that Compose has to take it away from through the interop.
 *
 * **Why the signal is [isAlreadyFocused] and not what requesting focus returns:** it returns
 * nothing. In Compose UI 1.7.6 —verified with `javap` on the AAR— `FocusRequester.requestFocus()`
 * is `void`: it calls `focus()` and discards its boolean. The only thing that throws is the case
 * where the `FocusRequester` isn't attached to any node. So `runCatching { … }.isSuccess`, which
 * is how this used to be written, came back true almost always —including when focus was NOT
 * obtained— and the retry never retried anything. The only real signal that focus arrived is the
 * button's own `onFocusChanged`, which is what's checked here.
 *
 * The `runCatching` stays, but only for what actually throws, and **without using it as the
 * success criterion**: an exception doesn't cut the loop, it keeps retrying.
 */
internal suspend fun retryFocus(
    attempts: Int = SKIP_FOCUS_ATTEMPTS,
    isAlreadyFocused: () -> Boolean,
    wait: suspend () -> Unit,
    request: () -> Unit,
): Boolean {
    repeat(attempts) {
        // `return` from the whole function, not `return@repeat`: the latter returns from that
        // round's lambda, i.e. it's a `continue` and the loop never cuts. That was the other half
        // of the bug.
        if (isAlreadyFocused()) return true
        runCatching { request() }
        wait()
    }
    return isAlreadyFocused()
}
