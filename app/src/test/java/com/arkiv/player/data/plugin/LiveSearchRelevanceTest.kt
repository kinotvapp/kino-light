package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSearchRelevanceTest {
    private fun live(title: String) = PluginItem(id = title, ref = title, title = title, kind = PluginOutput.KIND_LIVE)
    private fun movie(title: String) = PluginItem(id = title, ref = title, title = title, kind = "movie")
    private fun titles(items: List<PluginItem>) = items.map { it.title }

    @Test fun `a channel list answering an unrelated query is dropped entirely`() {
        val items = listOf(live("Pluto TV Cine Acción"), live("Pluto TV Cine Comedia"), live("Pluto TV Cine Terror"))
        assertEquals(emptyList<String>(), titles(LiveSearchRelevance.filter(items, listOf("Breaking Bad"))))
    }

    @Test fun `channels whose name matches the query stay, accents and case aside`() {
        val items = listOf(live("Pluto TV Cine Acción"), live("Pluto TV Cine Comedia"), live("CNN"))
        assertEquals(listOf("Pluto TV Cine Acción"), titles(LiveSearchRelevance.filter(items, listOf("cine accion"))))
    }

    @Test fun `movies and series are never judged, even with another title`() {
        val items = listOf(movie("Totalmente distinto"), live("Pluto TV Cine Terror"))
        assertEquals(listOf("Totalmente distinto"), titles(LiveSearchRelevance.filter(items, listOf("Breaking Bad"))))
    }

    @Test fun `any known form of the title can make a channel relevant`() {
        val items = listOf(live("Breaking Bad 24/7"))
        assertEquals(1, LiveSearchRelevance.filter(items, listOf("Hacerse malo", "", "Breaking Bad")).size)
    }

    @Test fun `a half-typed name and letters glued to digits still match`() {
        val items = listOf(live("Discovery Channel"), live("ESPN 2"), live("ESPN2 HD"), live("Fox Sports"))
        assertEquals(listOf("Discovery Channel"), titles(LiveSearchRelevance.filter(items, listOf("discov"))))
        assertEquals(listOf("ESPN 2", "ESPN2 HD"), titles(LiveSearchRelevance.filter(items, listOf("ESPN2"))))
    }

    @Test fun `a query with no word of three letters filters nothing`() {
        val items = listOf(live("Pluto TV Cine Acción"))
        assertEquals(1, LiveSearchRelevance.filter(items, listOf("tv")).size)
    }

    @Test fun `query forms are read back from the search argument`() {
        val json = """{"q":"Casa de papel","originalTitle":"La casa de papel","altTitles":["Money Heist"]}"""
        assertEquals(listOf("Casa de papel", "La casa de papel", "Money Heist"), LiveSearchRelevance.queryForms(json))
        assertEquals(emptyList<String>(), LiveSearchRelevance.queryForms("not json"))
    }
}
