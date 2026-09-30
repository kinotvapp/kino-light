package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenreTest {
    @Test fun `the vocabulary is closed and has a Spanish label for each id`() {
        assertEquals(
            listOf("peliculas", "series", "anime", "infantil", "documentales", "deportes", "noticias", "musica", "entretenimiento", "otros"),
            Genre.IDS,
        )
        assertEquals("Películas", Genre.label("peliculas"))
        assertEquals("Música", Genre.label("musica"))
        assertEquals("Otros", Genre.label("otros"))
        Genre.IDS.forEach { assertEquals(false, Genre.label(it).isBlank()) }
    }

    @Test fun `a declared genre is taken when it is in the vocabulary, whatever its case and spaces`() {
        assertEquals("deportes", Genre.parse("deportes"))
        assertEquals("deportes", Genre.parse("  Deportes "))
        assertEquals("peliculas", Genre.parse("PELICULAS"))
    }

    @Test fun `anything else is no genre, never an error`() {
        assertNull(Genre.parse("sports"))
        assertNull(Genre.parse(""))
        assertNull(Genre.parse(null))
        assertNull(Genre.parse(42))
        assertNull(Genre.parse(listOf("deportes")))
    }

    @Test fun `a title tells the genre when nothing was declared, accents and case ignored`() {
        assertEquals("deportes", Genre.infer("Deportes en vivo"))
        assertEquals("deportes", Genre.infer("ESPN Fútbol"))
        assertEquals("noticias", Genre.infer("Noticias Colombia"))
        assertEquals("noticias", Genre.infer("World News"))
        assertEquals("infantil", Genre.infer("Niños"))
        assertEquals("infantil", Genre.infer("Kids & Cartoon"))
        assertEquals("peliculas", Genre.infer("Películas de acción"))
        assertEquals("peliculas", Genre.infer("Cine"))
        assertEquals("series", Genre.infer("Series con capítulos nuevos"))
        assertEquals("anime", Genre.infer("Anime"))
        assertEquals("documentales", Genre.infer("Documentales"))
        assertEquals("musica", Genre.infer("Música y conciertos"))
        assertEquals("entretenimiento", Genre.infer("Entretenimiento"))
    }

    @Test fun `the more specific genre wins over the general one`() {
        assertEquals("anime", Genre.infer("Series de anime"))
        assertEquals("infantil", Genre.infer("Películas infantiles"))
        assertEquals("deportes", Genre.infer("Noticias deportivas"))
    }

    @Test fun `a title that says nothing gives no genre`() {
        assertNull(Genre.infer("Recién agregadas"))
        assertNull(Genre.infer("Argentina"))
        assertNull(Genre.infer(""))
    }

    @Test fun `declared beats inferred`() {
        assertEquals("infantil", Genre.of("infantil", "Noticias"))
        assertEquals("noticias", Genre.of(null, "Noticias"))
        assertEquals("noticias", Genre.of("no-existe", "Noticias"))
        assertNull(Genre.of(null, "Recién agregadas"))
    }
}
