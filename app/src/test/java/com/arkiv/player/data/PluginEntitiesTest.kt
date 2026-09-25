package com.arkiv.player.data

import com.arkiv.player.data.plugin.PluginRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a plugin title is saved to the library. The contract that matters is with the player:
 * `PlayerViewModel.loadPlugin` enters only for ids starting `plugin:` and reads the wrapped ref
 * from the episode's `torrentData`.
 */
class PluginEntitiesTest {
    private val movieRef = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()
    private val seriesRef = PluginRef("demo", "s1", PluginRef.SERIES, "S1").encode()

    private fun chapter(season: Int, n: Int, plugin: String = "demo", item: String = "s1") =
        PluginChapter(n, "Cap $n", PluginRef(plugin, item, PluginRef.EPISODE, "E$season-$n", season, n).encode(), season)

    @Test fun `a movie gets plugin-scoped ids and keeps its ref in torrentData`() {
        val (item, ep) = PluginEntities.buildMovie(movieRef, "Metrópolis", "p.jpg", now = 5L, existing = null)!!
        assertEquals("plugin:demo:m1", item.identifier)
        assertEquals("plugin:demo", item.source)
        assertEquals(movieRef, item.torrentData)
        assertEquals("movie", item.tipo)
        assertEquals("plugin:demo:m1::0", ep.id)
        assertEquals(movieRef, ep.torrentData)
    }

    @Test fun `a series ref or a foreign ref is not a movie`() {
        assertNull(PluginEntities.buildMovie(seriesRef, "x", "", 1L, null))
        assertNull(PluginEntities.buildMovie("ditu1:VOD:1", "x", "", 1L, null))
    }

    @Test fun `chapter ids follow the Caracol scheme`() {
        assertEquals("x::e3", PluginEntities.chapterId("x", 1, 3))
        assertEquals("x::t2e1", PluginEntities.chapterId("x", 2, 1))
    }

    @Test fun `a series saves its chapters and finds the chosen one by season and number`() {
        val chapters = listOf(chapter(1, 1), chapter(1, 2), chapter(2, 1))
        val s = PluginEntities.buildSeries(seriesRef, "Serie", chapters, chapters[2], "", 1L, null, null, tmdbId = 7, tituloCanonico = null)!!
        assertEquals("plugin:demo:s1", s.item.identifier)
        assertEquals(seriesRef, s.item.torrentData)
        assertEquals("series", s.item.categoryOverride)
        assertEquals(7, s.item.tmdbId)
        assertEquals(listOf("plugin:demo:s1::e1", "plugin:demo:s1::e2", "plugin:demo:s1::t2e1"), s.episodes.map { it.id })
        assertEquals("plugin:demo:s1::t2e1", s.chosenId)
        assertEquals(10_001, s.episodes[2].orderIndex)
    }

    @Test fun `chapters from another series or plugin are not saved`() {
        val chapters = listOf(chapter(1, 1), chapter(1, 2, plugin = "other"), chapter(1, 3, item = "s2"))
        val s = PluginEntities.buildSeries(seriesRef, "Serie", chapters, chapters[0], "", 1L, null, null, null, null)!!
        assertEquals(listOf("plugin:demo:s1::e1"), s.episodes.map { it.id })
    }

    @Test fun `an existing row keeps its added date and poster`() {
        val existing = PluginEntities.buildMovie(movieRef, "M", "old.jpg", 1L, null)!!.first
        val (item, _) = PluginEntities.buildMovie(movieRef, "M", "", now = 9L, existing = existing)!!
        assertEquals(1L, item.addedAt)
        assertEquals("old.jpg", item.thumbnailUrl)
    }

    @Test fun `a movie keeps the plugin's TMDB id, and an existing one survives a save without it`() {
        val ref = com.arkiv.player.data.plugin.PluginRef("demo", "m1", com.arkiv.player.data.plugin.PluginRef.MOVIE, "R1").encode()
        val (item, _) = PluginEntities.buildMovie(ref, "Matrix", "", 1L, null, tmdbId = 603)!!
        assertEquals(603, item.tmdbId)
        val (again, _) = PluginEntities.buildMovie(ref, "Matrix", "", 2L, item, tmdbId = null)!!
        assertEquals(603, again.tmdbId)
    }
}
