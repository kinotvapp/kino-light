package com.arkiv.player.ui.tv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Width split between the keyboard and the fields (Task 11, see the KDoc of [KEYBOARD_WEIGHT] in
 * `TvKeyboardAndFields.kt`): it's done by weight, and the keyboard keeps more of it, because
 * it's what gets used key by key with the remote while the fields only display text already typed.
 *
 * The only screen that calls `TvKeyboardAndFields` today is [TvMagisLinkOffer] (the Magis
 * linking offer), so this constant only needs to be right for that one screen.
 */
class TvKeyboardAndFieldsTest {

    @Test fun `the weight split keeps the keyboard getting more`() {
        assertTrue(KEYBOARD_WEIGHT > FIELDS_WEIGHT)
    }
}
