package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.catalog.TmdbInfo
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.magis.MagisRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleInfoTest {

    private fun item(
        type: String = "movie",
        title: String = "Nos vemos en la oficina",
        description: String = "",
        poster: String? = "https://img/p.jpg",
        backdrop: String? = "https://img/b.jpg",
        genres: List<String> = listOf("Drama", "Romance"),
        score: Double? = 7.9,
        durationS: Int = 6224,
        episodeCount: Int = 0,
    ) = CatalogItem(
        id = "c1", title = title, poster = poster, durationS = durationS,
        ref = MagisRef("c1", type, 0).encode(), type = type, genres = genres, score = score,
        backdrop = backdrop, description = description, episodeCount = episodeCount,
    )

    // ---- CatalogItem -> TitleInfo ----

    @Test
    fun `a catalog item keeps everything the page draws`() {
        val info = item(type = "teleplay", description = "Una oficinista harta.", episodeCount = 12).toTitleInfo()
        assertEquals("Nos vemos en la oficina", info.title)
        assertEquals(TitleKind.SERIES, info.kind)
        assertEquals("https://img/p.jpg", info.poster)
        assertEquals("https://img/b.jpg", info.backdrop)
        assertEquals("Una oficinista harta.", info.synopsis)
        assertEquals(listOf("Drama", "Romance"), info.genres)
        assertEquals(7.9, info.score!!, 0.0)
        assertEquals("a series' card duration is per episode, not the title's", 0, info.runtimeMinutes)
        assertEquals(12, info.episodeCount)
    }

    @Test
    fun `a series card's duration is not the title's runtime, a movie's is`() {
        // A series' duration is per episode (TMDB's series runtime is left out for the same reason).
        assertEquals(0, item(type = "teleplay", durationS = 2700).toTitleInfo().runtimeMinutes)
        assertEquals(103, item(type = "movie", durationS = 6224).toTitleInfo().runtimeMinutes)
    }

    @Test
    fun `every portal series type is a series and everything else is a movie`() {
        for (type in listOf("teleplay", "series", "variety")) {
            assertEquals(type, TitleKind.SERIES, item(type = type).toTitleInfo().kind)
        }
        assertEquals(TitleKind.MOVIE, item(type = "movie").toTitleInfo().kind)
        assertEquals(TitleKind.MOVIE, item(type = "").toTitleInfo().kind)
    }

    @Test
    fun `blank images become null and blank genres are dropped`() {
        val info = item(poster = " ", backdrop = "", genres = listOf("Drama", " ", "")).toTitleInfo()
        assertNull(info.poster)
        assertNull(info.backdrop)
        assertEquals(listOf("Drama"), info.genres)
    }

    @Test
    fun `html in a synopsis is stripped`() {
        assertEquals("Hola mundo", item(description = "Hola<br>mundo").toTitleInfo().synopsis)
    }

    @Test
    fun `a blank title falls back to the id`() {
        assertEquals("c1", item(title = "").toTitleInfo().title)
    }

    // ---- chapter identity ----

    private fun chapterIn(season: Int?, number: Int = 3) =
        GatewayEpisode(number = number, title = "t", ref = "r", season = season)

    @Test
    fun `a chapter with no season is in season 1 and season 0 counts as 1`() {
        assertEquals(1, chapterIn(null).seasonOrOne)
        assertEquals(1, chapterIn(0).seasonOrOne)
        assertEquals(1, chapterIn(1).seasonOrOne)
        assertEquals(4, chapterIn(4).seasonOrOne)
    }

    @Test
    fun `list keys differ across seasons that repeat a number`() {
        assertEquals("1-1", chapterIn(1, 1).listKey)
        assertEquals("2-1", chapterIn(2, 1).listKey)
        assertEquals("1-5", chapterIn(null, 5).listKey)
    }

    @Test
    fun `a chapter is labelled with its own season, else the page's`() {
        assertEquals(2, chapterIn(2).labelSeason(fallback = 9))
        assertEquals(9, chapterIn(null).labelSeason(fallback = 9))
        assertEquals(null, chapterIn(null).labelSeason(fallback = null))
        assertEquals(1, chapterIn(0).labelSeason(fallback = 9))
    }

    // ---- text helpers ----

    private fun info(
        kind: TitleKind = TitleKind.MOVIE,
        score: Double? = null,
        year: String = "",
        runtimeMinutes: Int = 0,
        episodeCount: Int = 0,
    ) = TitleInfo(title = "t", kind = kind, score = score, year = year, runtimeMinutes = runtimeMinutes, episodeCount = episodeCount)

    @Test
    fun `the meta line joins what is known and skips the rest`() {
        assertEquals("★ 7.9  ·  2026  ·  1 h 43 min", info(score = 7.9, year = "2026", runtimeMinutes = 103).metaLine())
        assertEquals("★ 8.0", info(score = 8.0).metaLine())
        assertEquals("2026  ·  45 min", info(year = "2026", runtimeMinutes = 45).metaLine())
        assertEquals("", info().metaLine())
    }

    @Test
    fun `the meta line ends with the age rating`() {
        assertEquals(
            "★ 7.9  ·  2026  ·  1 h 43 min  ·  12+",
            info(score = 7.9, year = "2026", runtimeMinutes = 103).copy(certification = "12+").metaLine(),
        )
        assertEquals("R", info().copy(certification = "R").metaLine())
    }

    // ---- TMDB merge ----

    private fun tmdbInfo(
        overview: String = "Sinopsis TMDB",
        tagline: String = "Nada es lo que parece",
        year: String = "2025",
        runtimeMinutes: Int = 144,
        genres: List<String> = listOf("Comedia", "Crimen"),
        voteAverage: Double? = 7.6,
        directors: List<String> = listOf("Rian Johnson"),
        cast: List<String> = listOf("Daniel Craig", "Josh O'Connor"),
        certification: String = "12+",
    ) = TmdbInfo(1, overview, tagline, year, runtimeMinutes, genres, voteAverage, directors, cast, certification)

    @Test
    fun `TMDB adds what the card did not carry`() {
        val out = info(kind = TitleKind.MOVIE).withTmdb(tmdbInfo())
        assertEquals("2025", out.year)
        assertEquals(144, out.runtimeMinutes)
        assertEquals("Nada es lo que parece", out.tagline)
        assertEquals(listOf("Rian Johnson"), out.directors)
        assertEquals(listOf("Daniel Craig", "Josh O'Connor"), out.cast)
        assertEquals("12+", out.certification)
        assertEquals(7.6, out.score!!, 0.001)
    }

    @Test
    fun `the portal's synopsis, score and runtime win over TMDB's`() {
        val out = info(kind = TitleKind.MOVIE, score = 5.8, runtimeMinutes = 103)
            .copy(synopsis = "Sinopsis del portal")
            .withTmdb(tmdbInfo())
        assertEquals("Sinopsis del portal", out.synopsis)
        assertEquals(5.8, out.score!!, 0.001)
        assertEquals(103, out.runtimeMinutes)
    }

    @Test
    fun `TMDB fills a blank synopsis, stripped of html`() {
        assertEquals("Hola", info().withTmdb(tmdbInfo(overview = "<p>Hola</p>")).synopsis)
    }

    @Test
    fun `TMDB's Spanish genres replace the portal's English tags, but only when it has some`() {
        val portal = info().copy(genres = listOf("Action", "Drama"))
        assertEquals(listOf("Comedia", "Crimen"), portal.withTmdb(tmdbInfo()).genres)
        assertEquals(listOf("Action", "Drama"), portal.withTmdb(tmdbInfo(genres = emptyList())).genres)
    }

    @Test
    fun `a series keeps no runtime from TMDB because that one is per episode`() {
        assertEquals(0, info(kind = TitleKind.SERIES).withTmdb(tmdbInfo(runtimeMinutes = 30)).runtimeMinutes)
    }

    @Test
    fun `an empty TMDB answer leaves the page as it was`() {
        val before = info(score = 5.8, year = "2024").copy(synopsis = "S", genres = listOf("Drama"))
        val empty = tmdbInfo(
            overview = "", tagline = "", year = "", runtimeMinutes = 0, genres = emptyList(),
            voteAverage = null, directors = emptyList(), cast = emptyList(), certification = "",
        )
        assertEquals(before, before.withTmdb(empty))
    }

    @Test
    fun `credit lines name the director or the creator and the first cast`() {
        val movie = info(kind = TitleKind.MOVIE).copy(directors = listOf("Rian Johnson"), cast = listOf("A", "B", "C", "D", "E", "F"))
        assertEquals(listOf("Dirección: Rian Johnson", "Reparto: A, B, C, D, E"), movie.creditLines())
        assertEquals(listOf("Dirección: Rian Johnson", "Reparto: A, B"), movie.creditLines(maxCast = 2))
        val show = info(kind = TitleKind.SERIES).copy(directors = listOf("Darren Star", "Otro"))
        assertEquals(listOf("Creada por: Darren Star, Otro"), show.creditLines())
        assertEquals(emptyList<String>(), info().creditLines())
    }

    @Test
    fun `the kind line says movie or series with its chapter count`() {
        assertEquals("Película", info().kindLine())
        assertEquals("Serie", info(kind = TitleKind.SERIES).kindLine())
        assertEquals("Serie  ·  1 episodio", info(kind = TitleKind.SERIES, episodeCount = 1).kindLine())
        assertEquals("Serie  ·  12 episodios", info(kind = TitleKind.SERIES, episodeCount = 12).kindLine())
    }

    @Test
    fun `the season header names the season and counts its episodes`() {
        assertEquals("Temporada 1  ·  12 episodios", seasonHeader(1, 12))
        assertEquals("Temporada 1  ·  1 episodio", seasonHeader(1, 1))
        assertEquals("12 episodios", seasonHeader(null, 12))
        assertEquals("Temporada 2", seasonHeader(2, 0))
        assertEquals("", seasonHeader(null, 0))
    }

    @Test
    fun `a chapter label carries the season only when it is known`() {
        assertEquals("T1 · E3", chapterNumberLabel(1, 3))
        assertEquals("E3", chapterNumberLabel(null, 3))
        assertEquals("E3", chapterNumberLabel(0, 3))
    }

    @Test
    fun `a chapter prefers the TMDB name, then the portal's, then a plain number`() {
        fun ch(title: String, tmdb: String?) = GatewayEpisode(number = 4, title = title, ref = "r", tmdbTitle = tmdb)
        assertEquals("El ataque", chapterName(ch("Daima T1_4", "El ataque")))
        assertEquals("Daima T1_4", chapterName(ch("Daima T1_4", " ")))
        assertNull(chapterName(ch(" ", null)))
        assertEquals("4. El ataque", chapterLine(ch("Daima T1_4", "El ataque")))
        assertEquals("Episodio 4", chapterLine(ch(" ", null)))
    }

    @Test
    fun `the download label follows the download state`() {
        assertEquals("Descargar", downloadLabel(DownloadDisplayState.NotDownloaded))
        assertEquals("En cola", downloadLabel(DownloadDisplayState.Queued))
        assertEquals("Descargando 45 %", downloadLabel(DownloadDisplayState.Downloading(0.456f)))
        assertEquals("Descargando", downloadLabel(DownloadDisplayState.Downloading(null)))
        assertEquals("Descargada", downloadLabel(DownloadDisplayState.Done))
        assertEquals("Falló la descarga", downloadLabel(DownloadDisplayState.Failed(null)))
        assertEquals("En espera", downloadLabel(DownloadDisplayState.NeedsConfirmation))
    }
}
