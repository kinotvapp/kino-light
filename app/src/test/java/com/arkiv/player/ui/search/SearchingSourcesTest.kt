package com.arkiv.player.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each source turns off its own "Buscando…" as soon as it finishes, without waiting for the others. */
class SearchingSourcesTest {

    private fun SearchingSources.searching(vararg tabs: SourceTab) =
        SourceTab.entries.forEach { t -> assertTrue("$t", isSearching(t) == (t in tabs)) }

    @Test fun `on starting, everything is searching`() {
        SearchingSources.starting().searching(SourceTab.ALL, SourceTab.MAGIS, SourceTab.CARACOL)
    }

    /** The case that used to happen: Magis had already brought everything back and its tab kept spinning for Caracol. */
    @Test fun `magis responded, its tab turns off and caracol and todo keep going`() {
        SearchingSources.starting().sourceFinished("magis").searching(SourceTab.ALL, SourceTab.CARACOL)
    }

    @Test fun `caracol went down, its tab turns off just the same`() {
        SearchingSources.starting().sourceFinished("ditu").searching(SourceTab.ALL, SourceTab.MAGIS)
    }

    @Test fun `with both finished everything turns off even without the end signal`() {
        SearchingSources.starting().sourceFinished("magis").sourceFinished("ditu").searching()
    }

    /** A source that sent neither its SourceDone nor its SourceError leaves nothing spinning. */
    @Test fun `the end turns everything off even if a source never reported`() {
        SearchingSources.starting().sourceFinished("magis").allFinished().searching()
        SearchingSources.starting().allFinished().searching()
    }

    /** `CompositeSource` names "desconocida" a source that goes down before announcing itself. */
    @Test fun `an unnamed source turns off none of them`() {
        SearchingSources.starting().sourceFinished("desconocida")
            .searching(SourceTab.ALL, SourceTab.MAGIS, SourceTab.CARACOL)
    }

    @Test fun `with no search in progress nothing spins`() {
        SearchingSources().searching()
        assertFalse(SearchingSources().any)
    }
}
