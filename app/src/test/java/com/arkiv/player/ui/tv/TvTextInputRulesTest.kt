package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTextInputRulesTest {
    @Test fun `an empty field shows the label alone, with the hint under it`() {
        val t = tvFieldButtonText("Servidor", "", hint = "http://ejemplo")
        assertNull(t.label)
        assertEquals("Servidor", t.value)
        assertEquals("http://ejemplo", t.hint)
        assertFalse(t.alert)
    }

    @Test fun `an empty required field keeps the star and raises the alert`() {
        val t = tvFieldButtonText("Servidor", "  ", required = true)
        assertEquals("Servidor *", t.value)
        assertTrue(t.alert)
    }

    @Test fun `a set field shows the label on top and the value`() {
        val t = tvFieldButtonText("Servidor", " http://a.b \n", required = true)
        assertEquals("Servidor *", t.label)
        assertEquals("http://a.b", t.value)
        assertFalse(t.alert)
    }

    @Test fun `a secret never shows its real value`() {
        val t = tvFieldButtonText("Llave", "s3cr3t-value", secret = true)
        assertEquals(TV_SECRET_MASK, t.value)
        assertFalse("s3cr3t-value" in t.value)
        assertEquals(TV_SECRET_SAVED, tvFieldButtonText("Llave", "x", secret = true, secretMask = TV_SECRET_SAVED).value)
    }

    @Test fun `save needs every required field`() {
        val fields = listOf(TvTextInput("a", required = true), TvTextInput("b"))
        assertFalse(tvTextCanSave(fields, listOf(" ", "")))
        assertTrue(tvTextCanSave(fields, listOf("x", "")))
    }

    @Test fun `chip hides a secret until revealed`() {
        assertEquals("•••", tvTextChipValue("abc", secret = true, revealed = false))
        assertEquals("abc", tvTextChipValue("abc", secret = true, revealed = true))
        assertEquals("abc", tvTextChipValue("abc", secret = false, revealed = false))
    }

    @Test fun `keyboard shortcuts follow the kind`() {
        assertTrue('@' in tvTextKeyboardExtras(TvTextKind.EMAIL))
        assertTrue('/' in tvTextKeyboardExtras(TvTextKind.URL))
        assertTrue(tvTextKeyboardExtras(TvTextKind.NUMBER).isEmpty())
    }
}
