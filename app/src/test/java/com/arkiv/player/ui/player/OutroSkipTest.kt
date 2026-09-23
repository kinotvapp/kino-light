package com.arkiv.player.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OutroSkipTest {

    @Test fun `with another item in the playlist it advances within it`() {
        // archive.org (source removed in this branch's pruning) was the only multi-item source: it
        // loaded the whole section as a playlist, and there `seekToNextMediaItem()` did lead
        // somewhere, without re-resolving the source.
        assertEquals(
            OutroSkip.Action.PLAYLIST_ADVANCE,
            OutroSkip.decide(currentIndex = 0, playlistItems = 12, nextChapter = "e2"),
        )
    }

    @Test fun `on the playlist's last item it navigates to the next chapter`() {
        assertEquals(
            OutroSkip.Action.NEXT_CHAPTER,
            OutroSkip.decide(currentIndex = 11, playlistItems = 12, nextChapter = "e13"),
        )
    }

    @Test fun `with a single item it navigates to the next chapter`() {
        // The bug this fixes: magis, Ditu, local, and the legacy sources (web, torrent, NUC)
        // publish ONE item, so the `seekToNextMediaItem()` the button made had nowhere to
        // go and pressing it did NOTHING. It's the same path the auto-advance already uses when a
        // chapter ends.
        assertEquals(
            OutroSkip.Action.NEXT_CHAPTER,
            OutroSkip.decide(currentIndex = 0, playlistItems = 1, nextChapter = "e2"),
        )
    }

    @Test fun `with no next chapter there's no skip`() {
        // A movie, or the series' last chapter: the button isn't drawn. A button that shows and
        // does nothing is worse than not having it.
        assertEquals(
            OutroSkip.Action.NONE,
            OutroSkip.decide(currentIndex = 0, playlistItems = 1, nextChapter = null),
        )
    }

    @Test fun `a blank next chapter is the same as not having one`() {
        assertEquals(
            OutroSkip.Action.NONE,
            OutroSkip.decide(currentIndex = 0, playlistItems = 1, nextChapter = "  "),
        )
    }

    @Test fun `with no playlist yet, all that's left is the next chapter`() {
        assertEquals(
            OutroSkip.Action.NEXT_CHAPTER,
            OutroSkip.decide(currentIndex = 0, playlistItems = 0, nextChapter = "e2"),
        )
    }

    @Test fun `an out-of-range index doesn't invent a next item`() {
        // `currentIndex` is moved by the player's listener and the playlist gets replaced whole on
        // changing chapter: they can cross paths for a frame.
        assertEquals(
            OutroSkip.Action.NEXT_CHAPTER,
            OutroSkip.decide(currentIndex = 40, playlistItems = 12, nextChapter = "e13"),
        )
    }
}

class SkipButtonKindVisibleTest {

    private fun which(
        inOpening: Boolean = false,
        inEnding: Boolean = false,
        action: OutroSkip.Action = OutroSkip.Action.NEXT_CHAPTER,
        casting: Boolean = false,
    ) = SkipButtonKind.which(inOpening, inEnding, action, casting)

    @Test fun `on the opening the intro one shows up`() {
        assertEquals(SkipButtonKind.INTRO, which(inOpening = true))
    }

    @Test fun `on the ending the outro one shows up`() {
        assertEquals(SkipButtonKind.OUTRO, which(inEnding = true))
    }

    @Test fun `casting, the outro one doesn't show up`() {
        // "Saltar intro" is a seekTo the CastPlayer does just as well; the outro one changes
        // chapter and the receiver only has one loaded.
        assertNull(which(inEnding = true, casting = true))
    }

    @Test fun `casting, the intro one still shows up`() {
        assertEquals(SkipButtonKind.INTRO, which(inOpening = true, casting = true))
    }

    @Test fun `with nowhere to skip to the outro one doesn't show up`() {
        assertNull(which(inEnding = true, action = OutroSkip.Action.NONE))
    }

    @Test fun `outside both intervals none shows up`() {
        assertNull(which())
    }

    @Test fun `if it fell into both it sends the intro one`() {
        // Shouldn't happen with healthy data; if it does, only ONE comes out: two buttons
        // grabbing focus at once is worse than a weird datum.
        assertEquals(SkipButtonKind.INTRO, which(inOpening = true, inEnding = true))
    }
}

class SkipButtonFocusTest {

    private val focus = SkipButtonFocus()

    @Test fun `on the button appearing it grabs focus`() {
        // Without this, the button shows and OK falls on the playback control: it PAUSES the
        // video instead of skipping the opening, the opposite of what's expected.
        assertEquals(
            SkipButtonFocus.Action.REQUEST,
            focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = false),
        )
    }

    @Test fun `while it stays on screen it doesn't request it again`() {
        // The position advances and this gets re-evaluated on every bar tick: stealing focus once
        // is helpful, stealing it every half second is unusable. If the person went to the
        // controls, it stays there.
        focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = false)
        assertEquals(
            SkipButtonFocus.Action.NONE,
            focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = true),
        )
    }

    @Test fun `the outro one is a different button and does request focus`() {
        focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = false)
        assertEquals(
            SkipButtonFocus.Action.REQUEST,
            focus.onChange(SkipButtonKind.OUTRO, hadFocus = false, controlsVisible = false),
        )
    }

    @Test fun `on disappearing with focus set and no controls it returns to the video`() {
        // If focus ends up orphaned the remote stops responding, much worse than the original
        // bug. The video is the one with the "any key = show the controls" behavior.
        focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = false)
        assertEquals(
            SkipButtonFocus.Action.RETURN_TO_VIDEO,
            focus.onChange(null, hadFocus = true, controlsVisible = false),
        )
    }

    @Test fun `on disappearing with the controls open it returns to the bar`() {
        focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = false)
        assertEquals(
            SkipButtonFocus.Action.RETURN_TO_CONTROLS,
            focus.onChange(null, hadFocus = true, controlsVisible = true),
        )
    }

    @Test fun `on disappearing with no focus nothing gets touched`() {
        // The person had already gone to the controls: moving focus from here would mean taking
        // it away from wherever they put it.
        focus.onChange(SkipButtonKind.INTRO, hadFocus = false, controlsVisible = true)
        assertEquals(
            SkipButtonFocus.Action.NONE,
            focus.onChange(null, hadFocus = false, controlsVisible = true),
        )
    }

    @Test fun `with never a button it does nothing`() {
        assertEquals(
            SkipButtonFocus.Action.NONE,
            focus.onChange(null, hadFocus = false, controlsVisible = false),
        )
    }
}

class RetryFocusTest {

    private var requests = 0
    private var waits = 0
    private var focused = false

    private fun retry(attempts: Int = 10, request: () -> Unit) = runBlocking {
        retryFocus(
            attempts = attempts,
            isAlreadyFocused = { focused },
            wait = { waits++ },
            request = { requests++; request() },
        )
    }

    @Test fun `it really cuts as soon as it gets focus`() {
        // The bug: `return@repeat` returns from the lambda, i.e. it's a `continue`. All ten
        // rounds happened anyway (and skipping the wait on top of it).
        retry { if (requests >= 2) focused = true }
        assertEquals(2, requests)
    }

    @Test fun `if it already has it, it doesn't request it again`() {
        focused = true
        assertTrue(retry { })
        assertEquals(0, requests)
        assertEquals(0, waits)
    }

    @Test fun `it waits between attempts`() {
        // The only thing that gives retrying any point. Before, the wait was AFTER the `return`
        // that ran almost every time, so all ten attempts happened in microseconds.
        retry(attempts = 3) { }
        assertEquals(3, requests)
        assertEquals(3, waits)
    }

    @Test fun `if it never gets it, it says so`() {
        assertFalse(retry(attempts = 3) { })
    }

    @Test fun `an exception doesn't count as having gotten focus`() {
        // The heart of the bug: it checked `runCatching { requestFocus() }.isSuccess`, and in
        // Compose 1.7.6 `requestFocus()` returns void (calls `focus()` and discards its boolean —
        // verified with javap on the AAR), so it's only a "failure" if it THROWS, which only
        // happens when the FocusRequester isn't attached to any node. The real signal is the
        // button's `onFocusChanged`, not the exception.
        assertFalse(retry(attempts = 3) { throw IllegalStateException("not attached to a node") })
        assertEquals("an exception doesn't cut the retry", 3, requests)
    }
}
