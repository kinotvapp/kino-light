package com.arkiv.player.ui.live

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each arrow does when the channel drawer is in play, while live is playing.
 *
 * Lives outside the Composable on purpose: the project has no UI test infrastructure (no
 * `androidTest`), so a navigation rule written inside `setOnKeyListener` can't be tested any way
 * at all -- and it's exactly the kind of rule that breaks quietly when the screen gets touched
 * months later. Here it's a pure function.
 *
 * The contract with the player is the delicate part: in live, up and down ALREADY zap
 * ([PlayerScreen] uses them for `zapPrevious`/`zapNext`). If the drawer kept all of them, it
 * would stop being possible to zap; if it kept none, the list couldn't be navigated. That's why
 * [DrawerAction.PASS] exists: it explicitly says "this isn't mine, let whoever's underneath
 * handle it."
 */
class DrawerDpadTest {

    private val channels = DrawerFocus.CHANNELS
    private val categories = DrawerFocus.CATEGORIES

    // ---- Open ----

    @Test fun `with the drawer closed, left opens it`() {
        assertEquals(
            DrawerAction.OPEN,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_LEFT, open = false, focus = channels),
        )
    }

    /**
     * And NO other key opens it. It matters that up/down keep zapping as before: opening the
     * drawer by accident, every time the channel changes, would be worse than not having it.
     */
    @Test fun `with the drawer closed, the rest of the keys don't open it`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
        ).forEach {
            assertEquals(
                "key=$it",
                DrawerAction.PASS,
                DrawerDpad.action(it, open = false, focus = channels),
            )
        }
    }

    // ---- Close ----

    @Test fun `with the drawer open and focus on channels, right closes it`() {
        assertEquals(
            DrawerAction.CLOSE,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_RIGHT, open = true, focus = channels),
        )
    }

    /**
     * But NOT from the categories column: there, to the right there's still something -the
     * channel list- and skipping it would force crossing the whole drawer back to pick one.
     * Right closes when there's nothing left to the right, which is where the gesture feels
     * natural.
     */
    @Test fun `with focus on categories, right moves to channels instead of closing`() {
        assertEquals(
            DrawerAction.TO_CHANNELS,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_RIGHT, open = true, focus = categories),
        )
    }

    @Test fun `with focus on channels, left goes to categories`() {
        assertEquals(
            DrawerAction.TO_CATEGORIES,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_LEFT, open = true, focus = channels),
        )
    }

    /** From the leftmost column there's nowhere to go: it closes, which is the exit. */
    @Test fun `with focus on categories, left closes`() {
        assertEquals(
            DrawerAction.CLOSE,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_LEFT, open = true, focus = categories),
        )
    }

    // ---- What the drawer does NOT keep ----

    /**
     * Up and down with the drawer open belong to the LIST (navigating channels or categories),
     * and Compose's focus handles them -- not this object and not the player's zapping.
     * Returning PASS here would hand them back to zapping: the list wouldn't move and the
     * channel would change on its own.
     */
    @Test fun `with the drawer open, up and down belong to the list, not to zapping`() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).forEach {
            assertEquals(
                "key=$it",
                DrawerAction.FROM_LIST,
                DrawerDpad.action(it, open = true, focus = channels),
            )
        }
    }

    /** OK too: picking a channel is resolved by the row that has focus, not this rule. */
    @Test fun `with the drawer open, OK belongs to the list`() {
        assertEquals(
            DrawerAction.FROM_LIST,
            DrawerDpad.action(KeyEvent.KEYCODE_DPAD_CENTER, open = true, focus = channels),
        )
    }

    // ---- The search box's keyboard ----
    //
    // An on-screen keyboard is navigated with all FOUR arrows: it's a grid of letters. So while
    // it's open, left and right can't still mean "change column" or "close the drawer" --
    // typing "RCN" would close the drawer on the first letter.

    @Test fun `with the keyboard open, all four arrows are its`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        ).forEach {
            assertEquals(
                "key=$it",
                DrawerAction.FROM_LIST,
                DrawerDpad.action(it, open = true, focus = DrawerFocus.KEYBOARD),
            )
        }
    }

    // ---- Back ----

    /** With the keyboard open, Back exits the keyboard -- not the whole drawer. */
    @Test fun `back with the keyboard open returns to categories`() {
        assertEquals(
            DrawerAction.TO_CATEGORIES,
            DrawerDpad.action(KeyEvent.KEYCODE_BACK, open = true, focus = DrawerFocus.KEYBOARD),
        )
    }

    @Test fun `back with the drawer open closes it`() {
        listOf(channels, categories).forEach {
            assertEquals(
                "focus=$it",
                DrawerAction.CLOSE,
                DrawerDpad.action(KeyEvent.KEYCODE_BACK, open = true, focus = it),
            )
        }
    }

    /**
     * And with the drawer closed, Back isn't its: it has to keep exiting the player as always.
     * Keeping it would leave the person with no way out of the channel.
     */
    @Test fun `back with the drawer closed isn't the drawer's`() {
        assertEquals(
            DrawerAction.PASS,
            DrawerDpad.action(KeyEvent.KEYCODE_BACK, open = false, focus = channels),
        )
    }
}
