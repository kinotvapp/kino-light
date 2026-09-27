package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndeterminedTrackLabelTest {
    @Test fun `ISO codes that name no language are treated as no language`() {
        for (code in listOf("und", "UND", "mul", "zxx", "mis", " und ")) assertTrue(code, isUndeterminedLanguage(code))
    }

    @Test fun `a real language code is not undetermined`() {
        for (code in listOf("es", "spa", "en", "ja", "pt-BR")) assertFalse(code, isUndeterminedLanguage(code))
    }

    @Test fun `the first audio track without a language reads Original, the rest are numbered`() {
        assertEquals("Original", audioFallbackLabel(0))
        assertEquals("Audio 2", audioFallbackLabel(1))
        assertEquals("Audio 3", audioFallbackLabel(2))
    }
}
