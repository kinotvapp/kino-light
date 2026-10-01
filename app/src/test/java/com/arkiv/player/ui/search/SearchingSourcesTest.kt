package com.arkiv.player.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each source turns off its own "Buscando…" as soon as it finishes, without waiting for the others. */
/** Caracol pinned VISIBLE (as before it was hidden), so these keep guarding that state; the
 *  hidden state ([com.arkiv.player.data.ditu.CaracolVisibility]) is covered at the bottom. */
class SearchingSourcesTest {

    private fun SearchingSources.searching(vararg tabs: SourceTab) =
        SourceTab.fixed(caracolVisible = true).forEach { t -> assertTrue("$t", isSearching(t) == (t in tabs)) }

    /** Xuper searches as a plugin: it announces itself like any other (`PluginContentSource.search`). */
    private val xuper = SourceTab.plugin("plugin:xuper", "Xuper", androidx.compose.ui.graphics.Color.White)

    @Test fun `on starting, everything is searching`() {
        SearchingSources.starting(caracolVisible = true).searching(SourceTab.ALL, SourceTab.CARACOL)
    }

    /** The case that used to happen: Xuper had already brought everything back and its tab kept spinning for Caracol. */
    @Test fun `xuper responded, its tab turns off and caracol and todo keep going`() {
        val s = SearchingSources.starting(caracolVisible = true).sourceStarted("plugin:xuper").sourceFinished("plugin:xuper")
        s.searching(SourceTab.ALL, SourceTab.CARACOL)
        assertFalse(s.isSearching(xuper))
    }

    @Test fun `caracol went down, its tab turns off and todo keeps going for xuper`() {
        val s = SearchingSources.starting(caracolVisible = true).sourceStarted("plugin:xuper").sourceFinished("ditu")
        s.searching(SourceTab.ALL)
        assertTrue(s.isSearching(xuper))
    }

    @Test fun `with both finished everything turns off even without the end signal`() {
        SearchingSources.starting(caracolVisible = true).sourceStarted("plugin:xuper").sourceFinished("plugin:xuper").sourceFinished("ditu").searching()
    }

    /**
     * The retired native Magis source (`"magis"`, deleted in Task 13c) is no longer waited on: with
     * no plugin announced, Caracol finishing is enough for "Todo" to stop, without the end signal.
     */
    @Test fun `todo does not wait on the retired native magis source`() {
        SearchingSources.starting(caracolVisible = true).sourceFinished("ditu").searching()
    }

    /** A source that sent neither its SourceDone nor its SourceError leaves nothing spinning. */
    @Test fun `the end turns everything off even if a source never reported`() {
        SearchingSources.starting(caracolVisible = true).sourceStarted("plugin:xuper").allFinished().searching()
        SearchingSources.starting(caracolVisible = true).allFinished().searching()
    }

    /** `CompositeSource` names "desconocida" a source that goes down before announcing itself. */
    @Test fun `an unnamed source turns off none of them`() {
        SearchingSources.starting(caracolVisible = true).sourceFinished("desconocida")
            .searching(SourceTab.ALL, SourceTab.CARACOL)
    }

    @Test fun `with no search in progress nothing spins`() {
        SearchingSources().searching()
        assertFalse(SearchingSources().any)
    }

    /** A plugin that is still searching keeps "Todo" spinning after Xuper and Caracol are done. */
    @Test fun `a started plugin keeps todo spinning until it finishes`() {
        val s = SearchingSources.starting(caracolVisible = true).sourceStarted("plugin:xuper").sourceStarted("plugin:demo")
            .sourceFinished("plugin:xuper").sourceFinished("ditu")
        s.searching(SourceTab.ALL)
        assertTrue(s.isSearching(SourceTab.plugin("plugin:demo", "Demo", androidx.compose.ui.graphics.Color.White)))
        s.sourceFinished("plugin:demo").searching()
    }

    @Test fun `hidden caracol is never waited on`() {
        assertTrue(SearchingSources.expectedFor(caracolVisible = false).isEmpty())
        // "Todo" goes off as soon as the plugins finish, with no "ditu" event ever arriving.
        val s = SearchingSources.starting(caracolVisible = false).sourceStarted("plugin:xuper")
        assertTrue(s.isSearching(SourceTab.ALL))
        assertFalse(s.sourceFinished("plugin:xuper").any)
        assertFalse(SearchingSources.starting(caracolVisible = false).any)
    }

    @Test fun `the app runs with the switch`() {
        assertTrue(
            SearchingSources.ALWAYS_EXPECTED ==
                SearchingSources.expectedFor(com.arkiv.player.data.ditu.CaracolVisibility.visible),
        )
        if (com.arkiv.player.data.ditu.CaracolVisibility.HIDDEN) {
            assertFalse(SourceTab.CARACOL.key in SearchingSources.ALWAYS_EXPECTED)
            assertFalse(SearchingSources.starting().any)
        }
    }
}
