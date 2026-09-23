package com.arkiv.player.data.trivia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sample JSON written by hand with TMDB's real fields (`append_to_response=credits` for a movie
 * and a chapter, `aggregate_credits` for a series). Each one's `overview` carries a unique word
 * that must never show up in [WorkSheet.lines]: a trivia fact can't have spoilers.
 */
class WorkSheetTest {

    private val movieJson = """
        {
          "title": "Coco",
          "release_date": "2017-10-27",
          "runtime": 105,
          "overview": "PALABRAUNICATRAMAPELICULA",
          "production_companies": [{"name": "Pixar Animation Studios"}],
          "credits": {
            "cast": [
              {"name": "Anthony Gonzalez", "order": 0},
              {"name": "Gael García Bernal", "order": 1},
              {"name": "Benjamin Bratt", "order": 2},
              {"name": "Alanna Ubach", "order": 3},
              {"name": "Renée Victor", "order": 4},
              {"name": "Jaime Camil", "order": 5}
            ],
            "crew": [
              {"name": "Lee Unkrich", "job": "Director", "department": "Directing"},
              {"name": "Adrian Molina", "job": "Co-Director", "department": "Directing"},
              {"name": "Adrian Molina", "job": "Screenplay", "department": "Writing"},
              {"name": "Matthew Aldrich", "job": "Screenplay", "department": "Writing"}
            ]
          }
        }
    """.trimIndent()

    private val seriesJson = """
        {
          "name": "Naruto",
          "first_air_date": "2002-10-03",
          "overview": "PALABRAUNICATRAMASERIE",
          "created_by": [{"name": "Masashi Kishimoto"}],
          "networks": [{"name": "TV Tokyo"}],
          "aggregate_credits": {
            "cast": [
              {"name": "Junko Takeuchi", "order": 0},
              {"name": "Chie Nakamura", "order": 1},
              {"name": "Noriaki Sugiyama", "order": 2},
              {"name": "Kazuhiko Inoue", "order": 3},
              {"name": "Hidekatsu Shibata", "order": 4},
              {"name": "Alguien Más", "order": 5}
            ]
          }
        }
    """.trimIndent()

    private val chapterJson = """
        {
          "name": "¡Soy Konohamaru!",
          "air_date": "2002-10-10",
          "season_number": 1,
          "episode_number": 2,
          "overview": "PALABRAUNICATRAMACAPITULO",
          "crew": [
            {"name": "Hayato Date", "job": "Director", "department": "Directing"},
            {"name": "Junki Takegami", "job": "Writer", "department": "Writing"}
          ],
          "guest_stars": [
            {"name": "Invitado Uno"},
            {"name": "Invitado Dos"},
            {"name": "Invitado Tres"},
            {"name": "Invitado Cuatro"},
            {"name": "Invitado Cinco"},
            {"name": "Invitado Seis"}
          ]
        }
    """.trimIndent()

    @Test fun `a movie sheet pulls director, writers, cast, production companies, date and runtime`() {
        val f = movieSheet(movieJson)!!
        assertEquals("movie", f.kind)
        assertEquals("Coco", f.name)
        assertEquals("2017-10-27", f.releaseDate)
        assertEquals(105, f.runtimeMinutes)
        // Only exact "Director": "Co-Director" doesn't count as a director.
        assertEquals(listOf("Lee Unkrich"), f.directors)
        assertEquals(listOf("Adrian Molina", "Matthew Aldrich"), f.writers)
        assertEquals(listOf("Pixar Animation Studios"), f.productionCompanies)
        // The first 5 by `order`: Jaime Camil (order 5) is left out.
        assertEquals(
            listOf("Anthony Gonzalez", "Gael García Bernal", "Benjamin Bratt", "Alanna Ubach", "Renée Victor"),
            f.cast,
        )
    }

    @Test fun `a series sheet pulls creators, network, first air date and cast`() {
        val f = seriesSheet(seriesJson)!!
        assertEquals("tv", f.kind)
        assertEquals("Naruto", f.name)
        assertEquals("2002-10-03", f.releaseDate)
        assertEquals(listOf("Masashi Kishimoto"), f.creators)
        assertEquals(listOf("TV Tokyo"), f.networks)
        assertEquals(
            listOf("Junko Takeuchi", "Chie Nakamura", "Noriaki Sugiyama", "Kazuhiko Inoue", "Hidekatsu Shibata"),
            f.cast,
        )
    }

    @Test fun `the chapter pulls name, date, director, writer and guest stars`() {
        val c = chapterSheet(chapterJson)!!
        assertEquals(1, c.season)
        assertEquals(2, c.episode)
        assertEquals("¡Soy Konohamaru!", c.name)
        assertEquals("2002-10-10", c.date)
        assertEquals(listOf("Hayato Date"), c.directors)
        assertEquals(listOf("Junki Takegami"), c.writers)
        // The first 5: "Invitado Seis" is left out.
        assertEquals(listOf("Invitado Uno", "Invitado Dos", "Invitado Tres", "Invitado Cuatro", "Invitado Cinco"), c.guestStars)
    }

    @Test fun `a null or missing field does not show up in lines`() {
        val json = """{"title": "Sin datos", "release_date": null}"""
        val f = movieSheet(json)!!
        assertNull(f.releaseDate)
        assertTrue(f.directors.isEmpty())
        assertTrue(f.productionCompanies.isEmpty())
        // With no fact beyond the name, there's no block to show.
        assertEquals("", f.lines())
    }

    @Test fun `no overview field ever reaches lines`() {
        val movie = movieSheet(movieJson)!!.lines()
        val series = seriesSheet(seriesJson)!!.lines()
        val seriesWithChapter = seriesSheet(seriesJson)!!.copy(chapter = chapterSheet(chapterJson))
        assertFalse(movie.contains("PALABRAUNICATRAMAPELICULA"))
        assertFalse(series.contains("PALABRAUNICATRAMASERIE"))
        assertFalse(seriesWithChapter.lines().contains("PALABRAUNICATRAMACAPITULO"))
    }

    @Test fun `broken json gives null`() {
        assertNull(movieSheet("{esto no es json"))
        assertNull(seriesSheet("{esto no es json"))
        assertNull(chapterSheet("{esto no es json"))
    }

    @Test fun `an actor with no latin letters does not show up in the cast`() {
        val json = """
            {
              "title": "Naruto la película",
              "credits": {
                "cast": [
                  {"name": "竹内順子", "order": 0},
                  {"name": "Junko Takeuchi", "order": 1}
                ]
              }
            }
        """.trimIndent()
        val f = movieSheet(json)!!
        assertEquals(listOf("Junko Takeuchi"), f.cast)
    }

    @Test fun `a director only in kanji does not show up`() {
        val json = """
            {
              "title": "Naruto la película",
              "credits": {
                "crew": [
                  {"name": "小坂春女", "job": "Director", "department": "Directing"}
                ]
              }
            }
        """.trimIndent()
        val f = movieSheet(json)!!
        assertTrue(f.directors.isEmpty())
    }

    @Test fun `a mixed name with latin letters stays`() {
        val json = """
            {
              "title": "Naruto la película",
              "credits": {
                "crew": [
                  {"name": "Haruko 春子", "job": "Director", "department": "Directing"}
                ]
              }
            }
        """.trimIndent()
        val f = movieSheet(json)!!
        assertEquals(listOf("Haruko 春子"), f.directors)
    }

    @Test fun `a writer only in kanji does not show up`() {
        val json = """
            {
              "title": "Naruto la película",
              "credits": {
                "crew": [
                  {"name": "西園悟", "job": "Screenplay", "department": "Writing"}
                ]
              }
            }
        """.trimIndent()
        val f = movieSheet(json)!!
        assertTrue(f.writers.isEmpty())
    }

    @Test fun `a creator only in kanji does not show up in the series sheet`() {
        val json = """
            {
              "name": "Naruto",
              "created_by": [{"name": "岸本斉史"}, {"name": "Masashi Kishimoto"}]
            }
        """.trimIndent()
        val f = seriesSheet(json)!!
        assertEquals(listOf("Masashi Kishimoto"), f.creators)
    }

    @Test fun `a guest star only in kanji does not show up in the chapter`() {
        val json = """
            {
              "season_number": 1,
              "episode_number": 2,
              "guest_stars": [{"name": "竹内順子"}, {"name": "Invitado Uno"}]
            }
        """.trimIndent()
        val c = chapterSheet(json)!!
        assertEquals(listOf("Invitado Uno"), c.guestStars)
    }

    @Test fun `lines builds the series and chapter as in the brief's example`() {
        val sheet = seriesSheet(seriesJson)!!.copy(chapter = chapterSheet(chapterJson))
        val r = sheet.lines()
        assertTrue(r.contains("Serie: Naruto (primera emisión 2002-10-03; creada por Masashi Kishimoto; canal TV Tokyo; reparto:"))
        assertTrue(
            r.contains(
                "Capítulo: temporada 1, episodio 2, «¡Soy Konohamaru!» (emitido 2002-10-10; " +
                    "dirigido por Hayato Date; escrito por Junki Takegami; invitados: Invitado Uno, " +
                    "Invitado Dos, Invitado Tres, Invitado Cuatro, Invitado Cinco)",
            ),
        )
    }
}
