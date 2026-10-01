package com.arkiv.player.ui.search

import androidx.compose.ui.graphics.Color
import com.arkiv.player.data.gateway.CompositeSource
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.ui.catalog.PlaySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fire TV, 0.9.44: a typed "deadpool" search showed only Xuper, while Internet Archive and two
 * Nuvio scrapers had been searched too. They answered with nothing (archive.org has no such film;
 * a Nuvio scraper can only answer a TMDB id, which a typed search doesn't carry), and the results
 * built tabs/sections/rows ONLY from results, so a searched plugin with zero results vanished.
 * Every searched plugin now keeps its chip (and its phone section, and the TV's "Sin resultados en"
 * line), on phone and TV alike, since both read the same [SourcesState].
 */
class SearchedPluginsShowTest {

    private fun plugin(title: String, id: String, name: String) = PlaySource.Plugin(
        id, name, 0xFFE0A030, GatewayResult(source = "plugin:$id", title = title, ref = "plg1:$id:$title"),
    )

    private val state = SourcesState()
        .withStart("plugin:xuper", "Xuper").withStart("plugin:archive-org", "Internet Archive")
        .withStart("plugin:pelisplushd", "PelisPlusHD")
        .withResponse("plugin:xuper").withResponse("plugin:archive-org").withResponse("plugin:pelisplushd")
    private val xuperOnly = listOf(plugin("Deadpool", "xuper", "Xuper"))

    @Test fun `a searched plugin with no results still has its chip, at zero`() {
        val announced = announcedTabs(state)
        assertEquals(listOf("plugin:xuper", "plugin:archive-org", "plugin:pelisplushd"), announced.map { it.key })
        val tabs = tabsFor(xuperOnly, caracolVisible = false, announced = announced)
        assertEquals(listOf("all", "plugin:xuper", "plugin:archive-org", "plugin:pelisplushd"), tabs.map { it.key })
        assertEquals("Internet Archive", tabs[2].label)
        val counts = countsByTab(xuperOnly, caracolVisible = false, announced = announced)
        assertEquals(1, counts[tabs[1]])
        assertEquals(0, counts[tabs[2]])
        assertEquals(0, counts[tabs[3]])
    }

    @Test fun `a plugin with results keeps its own accent and its arrival order`() {
        val tabs = tabsFor(listOf(plugin("x", "pelisplushd", "PelisPlusHD")), caracolVisible = false, announced = announcedTabs(state))
        assertEquals(listOf("all", "plugin:pelisplushd", "plugin:xuper", "plugin:archive-org"), tabs.map { it.key })
        assertEquals(Color(0xFFE0A030), tabs[1].accent)
    }

    @Test fun `the TV's Todo names the plugins that brought nothing`() {
        val done = SearchingSources()
        assertEquals("Sin resultados en Internet Archive, PelisPlusHD.", quietSourcesText(xuperOnly, state, done))
        // All of them brought something: no line.
        val all = xuperOnly + plugin("a", "archive-org", "Internet Archive") + plugin("p", "pelisplushd", "PelisPlusHD")
        assertNull(quietSourcesText(all, state, done))
    }

    @Test fun `a plugin still searching or down isn't called empty`() {
        val searching = SearchingSources.starting(caracolVisible = false)
            .sourceStarted("plugin:xuper").sourceStarted("plugin:archive-org").sourceStarted("plugin:pelisplushd")
            .sourceFinished("plugin:xuper").sourceFinished("plugin:archive-org")
        assertEquals("Sin resultados en Internet Archive.", quietSourcesText(xuperOnly, state, searching))
        val down = state.withFailure("plugin:archive-org", "no respondió a tiempo")
        assertEquals("Sin resultados en PelisPlusHD.", quietSourcesText(xuperOnly, down, SearchingSources()))
    }

    @Test fun `announcing twice keeps one place, and caracol is never an announced plugin tab`() {
        val s = SourcesState().withStart("plugin:a", "A").withStart("ditu", "").withStart("plugin:a", "A")
        assertEquals(listOf("plugin:a", "ditu"), s.announced)
        assertEquals(listOf("plugin:a"), announcedTabs(s).map { it.key })
    }

    /** A plugin installed while the app runs is searched by the very next search, no restart. */
    @Test fun `the composite reads its sources on every search`() = runTest {
        val installed = mutableListOf<ContentSource>(Quiet("plugin:xuper", listOf("Deadpool")))
        val composite = CompositeSource { installed.toList() }
        fun announced(events: List<SearchEvent>) =
            events.filterIsInstance<SearchEvent.SourceStart>().map { it.source }

        assertEquals(listOf("plugin:xuper"), announced(composite.search(GatewaySearchQuery(q = "deadpool")).toList()))
        installed += Quiet("plugin:archive-org", emptyList())
        installed += Quiet("plugin:pelisplushd", emptyList())
        val events = composite.search(GatewaySearchQuery(q = "deadpool")).toList()
        assertEquals(setOf("plugin:xuper", "plugin:archive-org", "plugin:pelisplushd"), announced(events).toSet())

        // Fed into the state the screens read, the zero-result ones still get their tabs.
        var st = SourcesState()
        events.forEach { if (it is SearchEvent.SourceStart) st = st.withStart(it.source, it.label) }
        val tabs = tabsFor(emptyList(), caracolVisible = false, announced = announcedTabs(st))
        assertTrue(tabs.map { it.key }.containsAll(listOf("plugin:archive-org", "plugin:pelisplushd")))
        assertFalse(tabs.any { it.key == "ditu" })
    }

    private class Quiet(val name: String, val titles: List<String>) : ContentSource {
        override fun recognizes(ref: String) = false
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(name, label = name))
            titles.forEach { emit(SearchEvent.ResultEvent(name, GatewayResult(name, it, "plg1:$it"))) }
            emit(SearchEvent.SourceDone(name, titles.size, 1))
        }
        override suspend fun resolve(ref: String): GatewayPlayable = throw GatewayException("sin uso")
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> = emptyList<GatewayEpisode>() to null
    }
}
