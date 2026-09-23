package com.arkiv.player.ui.live

import android.view.KeyEvent

/** Where the focus is inside the drawer. */
enum class DrawerFocus {
    CATEGORIES,
    CHANNELS,

    /**
     * The search box's keyboard. Its own state, not a variant of [CATEGORIES], because it
     * changes what the arrows mean: an on-screen keyboard is navigated with all four, so while
     * it's open none of them can still mean "change column" or "close" -- typing "RCN" would
     * close the drawer on the first letter.
     */
    KEYBOARD,
}

/** What to do with a key, decided before touching anything in the UI. */
enum class DrawerAction {
    /** Open the drawer (and don't let the key reach the player). */
    OPEN,

    /** Close it and return focus to the video. */
    CLOSE,

    /** Move focus to the categories column. */
    TO_CATEGORIES,

    /** Move focus to the channel list. */
    TO_CHANNELS,

    /**
     * The key belongs to whichever list has focus: Compose resolves it (navigate with up/down,
     * pick with OK). Still CONSUMED, so it doesn't keep falling through to the player.
     */
    FROM_LIST,

    /** Not the drawer's: let whoever's underneath handle it (live, the player's zapping). */
    PASS,
}

/**
 * What each arrow does when the channel drawer is in play, while live is playing.
 *
 * Lives here and not inside [com.arkiv.player.ui.player.PlayerScreen]'s `setOnKeyListener`
 * because the project has no UI tests (no `androidTest`): a navigation rule written in there
 * can't be tested any way at all, and it's exactly the kind of rule that breaks quietly when the
 * screen gets touched months later.
 *
 * What makes this non-trivial is that up and down are already taken in live: they zap
 * ([PlayerScreen] calls `zapPrevious`/`zapNext`). With the drawer open they have to
 * navigate the list and NOT zap -- if they were let through, the list wouldn't move and the
 * channel would change on its own. That's why [DrawerAction.FROM_LIST] consumes the key instead
 * of returning it.
 */
object DrawerDpad {

    fun action(key: Int, open: Boolean, focus: DrawerFocus): DrawerAction {
        if (!open) {
            // Closed, the ONLY key that belongs to it is left. Anything else keeps doing what it
            // always does -- especially up and down, which zap (and Back, which exits the
            // player: keeping it would leave the person locked into the channel).
            return if (key == KeyEvent.KEYCODE_DPAD_LEFT) DrawerAction.OPEN else DrawerAction.PASS
        }
        // With the keyboard open all four arrows are its; only Back closes it (not the whole
        // drawer, which would lose the search just typed for the sake of correcting it).
        if (focus == DrawerFocus.KEYBOARD) {
            return if (key == KeyEvent.KEYCODE_BACK) DrawerAction.TO_CATEGORIES
            else DrawerAction.FROM_LIST
        }
        return when (key) {
            KeyEvent.KEYCODE_BACK -> DrawerAction.CLOSE
            // Right closes when there's nothing left to the right anymore. From categories
            // there's still the channel list, and skipping it would force crossing the whole
            // drawer back.
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (focus == DrawerFocus.CHANNELS) DrawerAction.CLOSE else DrawerAction.TO_CHANNELS
            // And mirrored: from the leftmost column there's nowhere to go, so left is also an
            // exit.
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (focus == DrawerFocus.CATEGORIES) DrawerAction.CLOSE else DrawerAction.TO_CATEGORIES
            else -> DrawerAction.FROM_LIST
        }
    }
}
