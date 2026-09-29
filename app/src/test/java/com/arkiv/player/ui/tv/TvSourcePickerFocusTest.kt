package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSourcePickerFocusTest {
    @Test fun `the first community card takes the initial focus over the recommended ones`() {
        assertEquals(
            PickerFocus.Card("community-kinotvapp/kino-plugin-xuper"),
            pickerInitialFocus("community-kinotvapp/kino-plugin-xuper", "card-archive", userHasMoved = false),
        )
    }

    @Test fun `while the community list is loading or empty the first recommended card does`() {
        assertEquals(PickerFocus.Card("card-archive"), pickerInitialFocus(null, "card-archive", userHasMoved = false))
    }

    @Test fun `with no card at all Listo does`() {
        assertEquals(PickerFocus.Done, pickerInitialFocus(null, null, userHasMoved = false))
    }

    @Test fun `once a key was pressed a late community list never moves focus`() {
        assertEquals(PickerFocus.Stay, pickerInitialFocus("community-kinotvapp/kino-plugin-xuper", "card-archive", userHasMoved = true))
        assertEquals(PickerFocus.Stay, pickerInitialFocus(null, "card-archive", userHasMoved = true))
    }
}
