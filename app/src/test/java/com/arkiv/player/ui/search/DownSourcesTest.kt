package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** What the search results say when a source, or all of them, didn't respond. */
class DownSourcesTest {

    private val noErrors = SourcesState(responded = setOf("magis", "ditu"))
    private val caracolDown = SourcesState().withResponse("magis").withFailure("ditu", "sin red")
    private val everythingDown = SourcesState().withFailure("magis", "timeout").withFailure("ditu", "sin red")

    /** With no errors, Magis looks exactly as before: no notice and the usual texts. */
    @Test fun `with no errors the screen says the usual thing`() {
        for (state in listOf(SourcesState(), noErrors)) {
            assertTrue(downSourceNotices(state, SourceTab.ALL).isEmpty())
            assertEquals(NO_SOURCES_TEXT, noSourcesText(state))
            assertEquals("Sin resultados en Caracol.", emptyTabText(SourceTab.CARACOL, false, state))
            assertEquals("Buscando en Xuper…", emptyTabText(SourceTab.MAGIS, true, state))
            assertEquals("Sin resultados", emptySectionText(SourceTab.MAGIS, state))
        }
    }

    @Test fun `caracol down leaves its line without covering magis`() {
        // "sin red" says nothing understandable: the line is the generic one, without the raw text.
        assertEquals(listOf("Caracol no respondió"), downSourceNotices(caracolDown, SourceTab.ALL))
        assertEquals(listOf("Caracol no respondió"), downSourceNotices(caracolDown, SourceTab.CARACOL))
        assertTrue(downSourceNotices(caracolDown, SourceTab.MAGIS).isEmpty())
        // Caracol's tab doesn't say "Buscando…" or "Sin resultados": its line already explains it.
        assertNull(emptyTabText(SourceTab.CARACOL, true, caracolDown))
        assertEquals("Sin resultados en Xuper.", emptyTabText(SourceTab.MAGIS, false, caracolDown))
        assertEquals("No respondió", emptySectionText(SourceTab.CARACOL, caracolDown))
        assertEquals("Sin resultados", emptySectionText(SourceTab.MAGIS, caracolDown))
        // Magis did respond, with nothing: the usual advice is still the right one.
        assertEquals(NO_SOURCES_TEXT, noSourcesText(caracolDown))
    }

    /** With everything down the problem isn't the season: the text can't advise changing it. */
    @Test fun `everything down doesn't advise another season`() {
        assertEquals(NO_RESPONSE_TEXT, noSourcesText(everythingDown))
        assertFalse(noSourcesText(everythingDown).contains("temporada"))
        assertEquals(
            listOf("Xuper no respondió: timeout", "Caracol no respondió"),
            downSourceNotices(everythingDown, SourceTab.ALL),
        )
    }

    /** Caracol's line is written by `CaracolFailure`, with the exception the source sent. */
    @Test fun `caracol's line is in plain human words`() {
        val state = SourcesState().withResponse("magis").withFailure(
            "ditu",
            "Caracol no responde: Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""),
        )
        assertEquals(
            listOf("Caracol no respondió: sin conexión a internet"),
            downSourceNotices(state, SourceTab.CARACOL),
        )
    }

    /** Magis untouched: its line is still its name and its error text, as before. */
    @Test fun `magis's line stays the same`() {
        val state = SourcesState().withFailure("magis", "Unable to resolve host \"x\"", UnknownHostException("x"))
        assertEquals(listOf("Xuper no respondió: Unable to resolve host \"x\""), downSourceNotices(state, SourceTab.ALL))
    }

    /** `CompositeSource` names "desconocida" a source that goes down before announcing itself. */
    @Test fun `an unnamed source still gets a notice`() {
        val state = SourcesState().withFailure("desconocida", "boom")
        assertEquals(listOf("Una fuente no respondió: boom"), downSourceNotices(state, SourceTab.ALL))
        assertTrue(downSourceNotices(state, SourceTab.CARACOL).isEmpty())
    }
}
