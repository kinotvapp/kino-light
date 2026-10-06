package app.kino.tv

import app.kino.tv.data.allFilms
import app.kino.tv.data.metaLine
import app.kino.tv.data.parseCatalog
import app.kino.tv.data.searchFilms
import app.kino.tv.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    private val json = """
        {"rows": [
          {"id": "a", "title": "Uno", "items": [
            {"id": "f1", "title": "El gabinete del doctor Caligari", "year": 1920, "durationMin": 51, "synopsis": "Expresionismo alemán", "poster": "p1", "video": "v1"},
            {"id": "f2", "title": "Sin video", "year": 1930},
            {"id": "f3", "title": "Ayuno de amor", "year": 1940, "durationMin": 92, "video": "v3"}
          ]},
          {"id": "b", "title": "Dos", "items": [
            {"id": "f1", "title": "El gabinete del doctor Caligari", "year": 1920, "video": "v1"}
          ]},
          {"id": "c", "title": "", "items": [{"id": "x", "title": "x", "video": "v"}]}
        ]}
    """.trimIndent()

    @Test
    fun parsesRowsAndSkipsIncompleteEntries() {
        val rows = parseCatalog(json)
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(listOf("f1", "f3"), rows[0].films.map { it.id })
    }

    @Test
    fun allFilmsListsEachFilmOnce() {
        assertEquals(listOf("f1", "f3"), allFilms(parseCatalog(json)).map { it.id })
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val rows = parseCatalog(json)
        assertEquals(listOf("f1"), searchFilms(rows, "ALEMAN caligari").map { it.id })
        assertEquals(2, searchFilms(rows, "  ").size)
        assertTrue(searchFilms(rows, "zzz").isEmpty())
    }

    @Test
    fun metaLineJoinsYearAndDuration() {
        val films = allFilms(parseCatalog(json))
        assertEquals("1920 · 51 min", films[0].metaLine())
        assertEquals("1940 · 1 h 32 min", films[1].metaLine())
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun bundledCatalogParsesWithEveryFieldFilled() {
        val rows = parseCatalog(File("src/main/assets/home.json").readText())
        val films = allFilms(rows)
        assertTrue(films.size in 8..15)
        films.forEach {
            assertTrue(it.videoUrl.startsWith("https://archive.org/download/"))
            assertTrue(it.posterUrl.startsWith("https://archive.org/services/img/"))
            assertTrue(it.synopsis.isNotBlank() && it.year > 0)
        }
    }
}
