package com.arkiv.player.ui.live

import android.view.KeyEvent
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys

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

/** Where the TV guide puts focus: when it opens, and when the row holding it went away. */
enum class TvGuideFocus {
    PROVIDERS,
    CATEGORIES,

    /** Only while a search is on screen: the provider and category rows are hidden then. */
    KEYBOARD,
}

/**
 * The provider row exists only with more than one provider (spec §4). When it does, it is the
 * first thing the remote lands on, so choosing a source is one press away. Otherwise focus goes
 * to "Favoritos", as before providers existed.
 */
fun tvGuideFirstFocus(showProviders: Boolean): TvGuideFocus =
    if (showProviders) TvGuideFocus.PROVIDERS else TvGuideFocus.CATEGORIES

/**
 * Where the guide lands right now: a non-blank search replaces the provider and category rows with
 * the merged results (Amendment A1), so the keyboard is the only row left to land on.
 */
fun tvGuideFocusTarget(showProviders: Boolean, searching: Boolean): TvGuideFocus =
    if (searching) TvGuideFocus.KEYBOARD else tvGuideFirstFocus(showProviders)

/**
 * Whether the guide moves focus now: always on opening; afterwards only when nothing on the screen
 * holds it any more (a provider chip or category that held it vanished with its provider, or "Borrar
 * búsqueda" removed itself). Compose clears focus instead of moving it then, which strands the D-pad.
 * A row appearing later (a second provider arriving) never yanks focus from where the person is.
 */
fun tvGuideShouldLand(landedOnce: Boolean, screenHasFocus: Boolean): Boolean = !landedOnce || !screenHasFocus

/**
 * The provider the TV channel drawer is locked to (R6): the on-screen channel's, so its categories,
 * favourites and search never cross into another provider. With no channel known yet, Xuper's, as
 * before providers existed.
 */
fun drawerProviderFor(currentChannel: LiveChannel?): String = currentChannel?.provider ?: LiveChannelKeys.XUPER
