package com.arkiv.player.ui.tv

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import com.arkiv.player.ui.plugin.CatalogAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvAddPluginScreenTest {
    @Test fun `each catalog action reads as a sentence with the plugin name`() {
        assertEquals("Instalar Internet Archive", catalogRowLabel(CatalogAction.INSTALL, "Internet Archive"))
        assertEquals("Configurar Mi servidor", catalogRowLabel(CatalogAction.CONFIGURE, "Mi servidor"))
        assertEquals("Activar Xuper", catalogRowLabel(CatalogAction.ENABLE, "Xuper"))
    }

    @Test fun `an installed plugin reads as a status, not as an action`() {
        assertEquals("Xuper: instalado", catalogRowLabel(CatalogAction.INSTALLED, "Xuper"))
    }

    // Found on the KALLEY TV: Up inside the `usuario/repositorio` field only moved the caret, so the
    // recommended rows above it could not be reached with the D-pad once the person was below the field.
    @Test fun `Up and Down both leave a text field, in their own direction`() {
        assertEquals(FocusDirection.Up, fieldExitDirection(Key.DirectionUp))
        assertEquals(FocusDirection.Down, fieldExitDirection(Key.DirectionDown))
    }

    @Test fun `Left, Right and typing keys stay with the text field`() {
        assertNull(fieldExitDirection(Key.DirectionLeft))
        assertNull(fieldExitDirection(Key.DirectionRight))
        assertNull(fieldExitDirection(Key.A))
    }
}
