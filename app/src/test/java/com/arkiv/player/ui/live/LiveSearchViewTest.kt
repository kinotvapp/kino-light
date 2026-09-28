package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone En vivo screen draws for its search field (Amendment A1): the merged results, or the normal view. */
class LiveSearchViewTest {
    private val cnn = LiveChannel("c1", "CNN", 1, null)
    private val cnnPlugin = LiveChannel("c1", "CNN", 1, null, provider = "plugin:tv")

    @Test fun `a blank query is the normal per-provider view`() {
        assertEquals(LiveSearchView.Off, liveSearchView("", null))
        assertEquals(LiveSearchView.Off, liveSearchView("   ", CrossSearch("cnn", listOf(cnn), emptyList())))
    }

    @Test fun `a query with no answer yet is searching`() {
        assertEquals(LiveSearchView.Searching, liveSearchView("cnn", null))
        assertEquals(LiveSearchView.Searching, liveSearchView("cnn", CrossSearch("cn", emptyList(), emptyList())))
    }

    @Test fun `the previous answer stays on screen while the next one is computed`() {
        assertEquals(
            LiveSearchView.Results(listOf(cnn), note = null),
            liveSearchView("cnn ", CrossSearch("cn", listOf(cnn), emptyList())),
        )
    }

    @Test fun `results from every provider, with the note for providers not fully loaded`() {
        assertEquals(
            LiveSearchView.Results(listOf(cnn, cnnPlugin), note = "Algunos canales de Tu servidor aún no se han cargado"),
            liveSearchView("cnn", CrossSearch("cnn", listOf(cnn, cnnPlugin), listOf("Tu servidor"))),
        )
    }

    @Test fun `no results still says which providers were not fully searched`() {
        assertEquals(LiveSearchView.NoResults(note = null), liveSearchView("zzz", CrossSearch("zzz", emptyList(), emptyList())))
        assertEquals(
            LiveSearchView.NoResults(note = "Algunos canales de A aún no se han cargado\nAlgunos canales de B aún no se han cargado"),
            liveSearchView("zzz", CrossSearch("zzz", emptyList(), listOf("A", "B"))),
        )
    }

    @Test fun `the provider chip is lit only on a provider's own categories`() {
        val s = LiveUiState(activeProvider = "plugin:tv", activeCategory = "news")
        assertEquals("plugin:tv", selectedProviderChip(s, recentView = false))
        assertEquals(null, selectedProviderChip(s, recentView = true))
        assertEquals(null, selectedProviderChip(s.copy(activeCategory = CATEGORY_FAVORITES), recentView = false))
    }

    /** What a click zaps through: the merged results while they are on screen, else the per-provider list; nothing while waiting. */
    @Test fun `the list on screen follows the search view`() {
        val base = listOf(cnn)
        assertEquals(base, listOnScreen(LiveSearchView.Off, base))
        assertEquals(listOf(cnnPlugin), listOnScreen(LiveSearchView.Results(listOf(cnnPlugin), note = null), base))
        assertEquals(emptyList<LiveChannel>(), listOnScreen(LiveSearchView.Searching, base))
        assertEquals(emptyList<LiveChannel>(), listOnScreen(LiveSearchView.NoResults(note = null), base))
    }
}
