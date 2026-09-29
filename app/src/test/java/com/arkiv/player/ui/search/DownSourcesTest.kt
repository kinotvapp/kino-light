package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** What the search results say when a source, or all of them, didn't respond. */
class DownSourcesTest {

    private val demo = "plugin:demo"
    private val noErrors = SourcesState(responded = setOf(demo, "ditu")).withLabel(demo, "Demo")
    private val caracolDown = SourcesState().withLabel(demo, "Demo").withResponse(demo).withFailure("ditu", "sin red")
    private val everythingDown = SourcesState().withLabel(demo, "Demo").withFailure(demo, "timeout").withFailure("ditu", "sin red")
    private val demoTab = tabForSource(demo, mapOf(demo to "Demo"))!!

    /** With no errors, a plugin looks exactly as before: no notice and the usual texts. */
    @Test fun `with no errors the screen says the usual thing`() {
        for (state in listOf(SourcesState(), noErrors)) {
            assertTrue(downSourceNotices(state, SourceTab.ALL).isEmpty())
            assertEquals(NO_SOURCES_TEXT, noSourcesText(state))
            assertEquals("Sin resultados en Caracol.", emptyTabText(SourceTab.CARACOL, false, state))
            assertEquals("Buscando en Demo…", emptyTabText(demoTab, true, state))
            assertEquals("Sin resultados", emptySectionText(demoTab, state))
        }
    }

    @Test fun `caracol down leaves its line without covering a plugin`() {
        // "sin red" says nothing understandable: the line is the generic one, without the raw text.
        assertEquals(listOf("Caracol no respondió"), downSourceNotices(caracolDown, SourceTab.ALL))
        assertEquals(listOf("Caracol no respondió"), downSourceNotices(caracolDown, SourceTab.CARACOL))
        assertTrue(downSourceNotices(caracolDown, demoTab).isEmpty())
        // Caracol's tab doesn't say "Buscando…" or "Sin resultados": its line already explains it.
        assertNull(emptyTabText(SourceTab.CARACOL, true, caracolDown))
        assertEquals("Sin resultados en Demo.", emptyTabText(demoTab, false, caracolDown))
        assertEquals("No respondió", emptySectionText(SourceTab.CARACOL, caracolDown))
        assertEquals("Sin resultados", emptySectionText(demoTab, caracolDown))
        // The plugin did respond, with nothing: the usual advice is still the right one.
        assertEquals(NO_SOURCES_TEXT, noSourcesText(caracolDown))
    }

    /** With everything down the problem isn't the season: the text can't advise changing it. */
    @Test fun `everything down doesn't advise another season`() {
        assertEquals(NO_RESPONSE_TEXT, noSourcesText(everythingDown))
        assertFalse(noSourcesText(everythingDown).contains("temporada"))
        assertEquals(
            listOf("Demo no respondió: timeout", "Caracol no respondió"),
            downSourceNotices(everythingDown, SourceTab.ALL),
        )
    }

    /** Caracol's line is written by `CaracolFailure`, with the exception the source sent. */
    @Test fun `caracol's line is in plain human words`() {
        val state = SourcesState().withResponse(demo).withFailure(
            "ditu",
            "Caracol no responde: Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""),
        )
        assertEquals(
            listOf("Caracol no respondió: sin conexión a internet"),
            downSourceNotices(state, SourceTab.CARACOL),
        )
    }

    /** A plugin's untyped failure: its line is still its name and its error text, as before. */
    @Test fun `a plugin's untyped failure keeps its name and error text`() {
        val state = SourcesState().withLabel(demo, "Demo").withFailure(demo, "Unable to resolve host \"x\"", UnknownHostException("x"))
        assertEquals(listOf("Demo no respondió: Unable to resolve host \"x\""), downSourceNotices(state, SourceTab.ALL))
    }

    /** Nothing emits `"magis"` since Xuper moved to its plugin: an old name like it is an unknown source. */
    @Test fun `magis is no longer a known source name`() {
        assertNull(tabForSource("magis"))
        val state = SourcesState().withFailure("magis", "boom")
        assertEquals(listOf("Una fuente no respondió: boom"), downSourceNotices(state, SourceTab.ALL))
    }

    /** `CompositeSource` names "desconocida" a source that goes down before announcing itself. */
    @Test fun `an unnamed source still gets a notice`() {
        val state = SourcesState().withFailure("desconocida", "boom")
        assertEquals(listOf("Una fuente no respondió: boom"), downSourceNotices(state, SourceTab.ALL))
        assertTrue(downSourceNotices(state, SourceTab.CARACOL).isEmpty())
    }

    @Test fun `a plugin that did not respond is named by its label`() {
        val state = SourcesState().withLabel("plugin:demo", "Demo").withResponse("ditu").withFailure("plugin:demo", "no respondió a tiempo")
        assertEquals(listOf("Demo no respondió: no respondió a tiempo"), downSourceNotices(state, SourceTab.ALL))
        val tab = tabForSource("plugin:demo", state.labels)!!
        assertEquals(listOf("Demo no respondió: no respondió a tiempo"), downSourceNotices(state, tab))
        assertEquals(emptyList<String>(), downSourceNotices(state, SourceTab.CARACOL))
        assertNull(emptyTabText(tab, false, state))
        assertEquals("No respondió", emptySectionText(tab, state))
    }

    @Test fun `a plugin's typed error is its own sentence, not "no respondió"`() {
        val state = SourcesState().withLabel("plugin:jf", "Jellyfin")
            .withFailure("plugin:jf", "Configura Jellyfin en Ajustes ▸ Plugins", com.arkiv.player.data.plugin.PluginErrorException("auth_required", ""))
        assertEquals(listOf("Configura Jellyfin en Ajustes ▸ Plugins"), downSourceNotices(state, SourceTab.ALL))
    }

    // A reason Kino itself worked out (PluginFailureText) is already a whole sentence naming the source.
    @Test fun `a failure Kino explained is its own sentence`() {
        // Untyped on purpose (a script's own throw): only Kino's explanation makes it a sentence.
        val e = com.arkiv.player.data.plugin.PluginScriptException("fetch failed").apply {
            trace = com.arkiv.player.data.plugin.PluginCallTrace().apply { refused("cdn.example", com.arkiv.player.data.plugin.PluginCallTrace.Refusal.NOT_ASKED) }
        }
        val state = SourcesState().withLabel("plugin:c", "Castle")
            .withFailure("plugin:c", "Castle necesita cdn.example, que no está aprobado", e)
        assertEquals(listOf("Castle necesita cdn.example, que no está aprobado"), downSourceNotices(state, SourceTab.ALL))
    }

    /**
     * Fix round 1, finding 1 (spec §3.6 "Any other error keeps today's generic handling"): an
     * UNTYPED plugin error code (outside `PluginErrors.CODES`) must NOT show the plugin's raw text
     * bare -- that would let a plugin's own text masquerade as the app's message. It falls through
     * to the same generic wording an untyped/non-plugin failure gets.
     */
    @Test fun `an untyped plugin error code still gets the generic "no respondió" wording`() {
        val state = SourcesState().withLabel("plugin:jf", "Jellyfin")
            .withFailure("plugin:jf", "algo raro pasó", com.arkiv.player.data.plugin.PluginErrorException("whatever", "algo raro pasó"))
        assertEquals(listOf("Jellyfin no respondió: algo raro pasó"), downSourceNotices(state, SourceTab.ALL))
    }
}
