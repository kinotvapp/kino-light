package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.PluginEntities
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.ui.search.PlaybackResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTitleSourceTest {

    private val extras = PluginTitleExtras(
        pluginName = "Demo", color = "#FF0000", year = "2021", tmdbId = 603, imdbId = "tt0133093",
    )

    private fun source(e: PluginTitleExtras = extras) = PluginTitleSource(
        extras = e,
        onPlayMovie = { PlaybackResult.Ready("movie:${it.ref}") },
        onPlaySeason = { _, chapters, chosen, _ -> PlaybackResult.Ready("season:${chapters.size}:${chosen.number}") },
    )

    private val movieRef = PluginRef("demo", "m1", PluginRef.MOVIE, "own-m1").encode()
    private val seriesRef = PluginRef("demo", "s1", PluginRef.SERIES, "own-s1").encode()

    private fun movie() = CatalogItem(
        id = "m1", title = "Film", poster = "https://img/p.jpg", durationS = 0, ref = movieRef, type = "movie",
        backdrop = "https://img/b.jpg",
    )

    private fun show() = CatalogItem(
        id = "s1", title = "Show", poster = null, durationS = 0, ref = seriesRef, type = "series",
    )

    private fun chapter(season: Int?, number: Int) =
        GatewayEpisode(number = number, title = "E", ref = "r", season = season)

    // ---- library ids ----

    @Test
    fun `a movie's ids are the plugin entity ones`() {
        val s = source()
        assertEquals("plugin:demo:m1", s.itemId(movie()))
        assertEquals("plugin:demo:m1::0", s.movieEpisodeId(movie()))
    }

    @Test
    fun `a chapter id carries its season and season 0 or none counts as 1`() {
        val s = source()
        assertEquals(PluginEntities.chapterId("plugin:demo:s1", 2, 3), s.chapterEpisodeId(show(), chapter(2, 3)))
        assertEquals("plugin:demo:s1::t2e3", s.chapterEpisodeId(show(), chapter(2, 3)))
        assertEquals("plugin:demo:s1::e3", s.chapterEpisodeId(show(), chapter(null, 3)))
        assertEquals("plugin:demo:s1::e3", s.chapterEpisodeId(show(), chapter(0, 3)))
    }

    @Test
    fun `an item whose ref is not a plugin ref of its kind gets an id no library row can have`() {
        val bad = movie().copy(ref = "garbage")
        val id = source().itemId(bad)
        assertTrue(id.startsWith("plugin-unknown"))
        // A movie ref where a series is expected is just as unusable.
        assertTrue(source().itemId(show().copy(ref = movieRef)).startsWith("plugin-unknown"))
    }

    // ---- the gateway result playback reads ----

    @Test
    fun `the gateway result carries what playPlugin and playPluginSeason read`() {
        val r = source().gatewayResult(movie())
        assertEquals("plugin:demo", r.source)
        assertEquals(movieRef, r.ref)
        assertEquals("Film", r.title)
        assertEquals("movie", r.kind)
        assertEquals("2021", r.year)
        assertEquals("https://img/p.jpg", r.extra["poster"])
        assertEquals("https://img/b.jpg", r.extra["backdrop"])
        assertEquals("Demo", r.extra["pluginName"])
        assertEquals("m1", r.extra["pluginItemId"])
        assertEquals("603", r.extra["tmdbId"])
        assertEquals("tt0133093", r.extra["imdbId"])
    }

    @Test
    fun `a series result says series and leaves out ids the plugin did not send`() {
        val r = source(PluginTitleExtras(pluginName = "Demo")).gatewayResult(show())
        assertEquals("series", r.kind)
        assertNull(r.extra["tmdbId"])
        assertNull(r.extra["imdbId"])
        assertNull(r.extra["poster"])
    }

    // ---- capabilities ----

    @Test
    fun `a plugin has no downloads and shows its badge`() {
        val s = source()
        assertNull(s.downloads)
        assertFalse(s.canDownload)
        assertEquals(TitleBadge("Demo", 0xFFFF0000), s.badge)
        assertEquals("2021", s.initialYear)
    }

    // ---- sibling seasons ----

    @Test
    fun `a sibling season becomes the same card under its own id, wrapped ref and numbered title`() {
        val siblingRef = PluginRef("demo", "s2", PluginRef.SERIES, "own-s2").encode()
        val next = source().siblingItem(show().copy(title = "Show T1", episodeCount = 8), SeasonRef("s2", 2, ref = siblingRef, title = "Temporada 2"))
        assertEquals("s2", next.id)
        assertEquals(siblingRef, next.ref)
        assertEquals("Show T2", next.title)
        assertEquals("the count is the new season's to load", 0, next.episodeCount)
        assertEquals("series", next.type)
        // Its library id follows: progress reads under the sibling's own rows.
        assertEquals("plugin:demo:s2", source().itemId(next))
    }

    @Test
    fun `an unnumbered sibling keeps the current title`() {
        val next = source().siblingItem(show().copy(title = "Show"), SeasonRef("sp", 0, ref = "r", title = "Especiales"))
        assertEquals("Show", next.title)
        assertEquals("sp", next.id)
    }

    @Test
    fun `a nameless plugin has no badge and a bad color falls back to the neutral one`() {
        assertNull(source(PluginTitleExtras()).badge)
        val neutral = source(PluginTitleExtras(pluginName = "X", color = "red")).badge!!
        assertEquals(0xFFB0BEC5, neutral.colorArgb)
    }

    @Test
    fun `a plugin's own failure message reaches the person`() {
        // "Esto venía del plugin X, que ya no está instalado", "Configura X en Ajustes ▸ Plugins"…
        assertTrue(source().showsFailureDetail)
    }

    @Test
    fun `the TMDB hint is the ids the plugin sent`() = runTest {
        assertEquals(TmdbHint(603, "tt0133093"), source().tmdbHint(movie()))
        assertEquals(TmdbHint(), source(PluginTitleExtras()).tmdbHint(show()))
    }

    // ---- playback delegates ----

    @Test
    fun `playback goes through the injected plugin paths`() = runTest {
        val s = source()
        assertEquals(PlaybackResult.Ready("movie:$movieRef"), s.playMovie(s.gatewayResult(movie())))
        val out = s.playSeason(s.gatewayResult(show()), listOf(chapter(1, 1), chapter(2, 1)), chapter(2, 1), null)
        assertEquals(PlaybackResult.Ready("season:2:1"), out)
    }
}
