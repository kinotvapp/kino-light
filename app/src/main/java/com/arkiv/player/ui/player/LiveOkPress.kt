package com.arkiv.player.ui.player

import android.view.KeyEvent

/** The remote's OK/center keys: the ones [LiveOkPress] reads on an En vivo channel. */
internal val LIVE_OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/**
 * OK/center on the TV while an En vivo channel plays: a short press is play/pause, as always; a
 * long one (held [LONG_PRESS_MS]) stars or unstars the channel.
 *
 * That split means the short press can no longer act on the way DOWN -- it can't know yet whether
 * it'll be long -- so it acts on release. The ~100 ms that costs is not noticeable, and it's the
 * only way a long press never also pauses.
 *
 * Time-based on the key event's own clock (`eventTime - downTime`), not on counting repeats:
 * repeats fire it as soon as the hold passes the threshold, and a remote that sends no repeats at
 * all still gets it on release. A release with no press seen here is ignored: the OK that opened
 * the channel from the guide or the drawer can end on the video view.
 */
internal class LiveOkPress {
    enum class Action { NONE, SHORT, LONG }

    private var pressed = false
    private var fired = false

    fun onDown(repeatCount: Int, heldMs: Long): Action {
        if (repeatCount == 0) {
            pressed = true
            fired = false
            return Action.NONE
        }
        if (!pressed || fired || heldMs < LONG_PRESS_MS) return Action.NONE
        fired = true
        return Action.LONG
    }

    fun onUp(heldMs: Long): Action {
        val action = when {
            !pressed || fired -> Action.NONE
            heldMs >= LONG_PRESS_MS -> Action.LONG
            else -> Action.SHORT
        }
        pressed = false
        fired = false
        return action
    }

    companion object {
        const val LONG_PRESS_MS = 600L
    }
}
