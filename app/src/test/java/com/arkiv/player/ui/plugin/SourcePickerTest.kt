package com.arkiv.player.ui.plugin

import com.arkiv.player.data.onboarding.OnboardingKind
import com.arkiv.player.data.onboarding.OnboardingPrefs
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePickerTest {
    private fun plugin(enabled: Boolean) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled),
        iconFile = null,
    )

    private class FakePrefs : OnboardingPrefs {
        private var pickerDone = false
        override val onboardingKind: OnboardingKind? = OnboardingKind.NEW
        override val sourcePickerDone: Boolean get() = pickerDone
        override fun setOnboardingKind(kind: OnboardingKind) = Unit
        override fun setSourcePickerDone(done: Boolean) { pickerDone = done }
    }

    @Test fun `the copy is the spec's`() {
        assertEquals("sources", SOURCE_PICKER_ROUTE)
        assertEquals("Elige tus fuentes", SOURCE_PICKER_TITLE)
        assertEquals("Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Ajustes ▸ Plugins.", SOURCE_PICKER_LINE)
        assertEquals("Listo", SOURCE_PICKER_DONE)
        assertEquals("Ahora no", SOURCE_PICKER_SKIP)
        assertEquals("Recomendados", RECOMMENDED_TITLE)
    }

    @Test fun `Listo needs at least one installed plugin, in any state`() {
        assertFalse(pickerCanFinish(emptyList()))
        assertTrue(pickerCanFinish(listOf(plugin(enabled = false))))
        assertTrue(pickerCanFinish(listOf(plugin(enabled = true))))
    }

    @Test fun `the TV picker takes focus back only once placed, when nothing of it holds focus and no dialog is up`() {
        assertTrue(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = false, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = false, screenHasFocus = false, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = true, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = false, dialogOpen = true))
    }

    @Test fun `leaving the picker marks it done and pops back to what was under it`() {
        val prefs = FakePrefs()
        var wentHome = false
        leaveSourcePicker(prefs, popBack = { true }, goHome = { wentHome = true })
        assertTrue(prefs.sourcePickerDone)
        assertFalse(wentHome)
    }

    @Test fun `leaving the picker with nothing under it marks it done and goes Home`() {
        val prefs = FakePrefs()
        var wentHome = false
        leaveSourcePicker(prefs, popBack = { false }, goHome = { wentHome = true })
        assertTrue(prefs.sourcePickerDone)
        assertTrue(wentHome)
    }

    @Test fun `the done flag is on disk before navigation runs, so a crash there cannot reopen it`() {
        val prefs = FakePrefs()
        var doneWhenPopped: Boolean? = null
        leaveSourcePicker(prefs, popBack = { doneWhenPopped = prefs.sourcePickerDone; true }, goHome = {})
        assertEquals(true, doneWhenPopped)
    }
}
