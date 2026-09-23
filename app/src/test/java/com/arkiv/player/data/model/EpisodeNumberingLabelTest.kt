package com.arkiv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cases taken from the Fire Stick's REAL database (431 episodes): the displayNames come in wildly
 * different formats and some carry the whole synopsis stuck onto them. That's why the label is
 * built by parsing, not by showing the raw text.
 */
class EpisodeNumberingLabelTest {

    @Test fun `sXXeYY format (Dragon Ball GT)`() {
        assertEquals("T1 · E1", EpisodeNumbering.displayLabel(null, "s01e01"))
        assertEquals("T1 · E16", EpisodeNumbering.displayLabel(null, "s01e16"))
    }

    @Test fun `clean T and E format`() {
        assertEquals("T1 · E3", EpisodeNumbering.displayLabel(null, "T1 · E3"))
        assertEquals("T1 · E100", EpisodeNumbering.displayLabel(null, "T1 · E100  El brujo de las ranas"))
    }

    @Test fun `with the synopsis stuck on, only the number is kept`() {
        // The "E181" that follows is the absolute numbering; the first match wins (E23).
        val messy = "T4 · E23  E181 La verdad de por qué el Hoshikage fue silenciado Tras salvarle " +
            "la vida, Natsuhi le explica a Naruto cómo ella y su marido pusieron fin a los horrores " +
            "del entrenamiento de la estrella. 19/04/2006"
        assertEquals("T4 · E23", EpisodeNumbering.displayLabel(null, messy))
    }

    @Test fun `with no season, only the episode shows`() {
        assertEquals("E7", EpisodeNumbering.displayLabel(null, "Ep 7"))
        assertEquals("E5", EpisodeNumbering.displayLabel(null, "Episodio 5"))
    }

    @Test fun `the season can come from the section`() {
        assertEquals("T2 · E7", EpisodeNumbering.displayLabel("Temporada 2", "Ep 7"))
    }

    @Test fun `text with no episode marker doesn't make up a label`() {
        assertNull(EpisodeNumbering.displayLabel(null, "TPO Neon Genesis Evangelion 04  · Trapo2019 Universo Anime"))
        assertNull(EpisodeNumbering.displayLabel(null, ""))
    }

    @Test fun `doesn't confuse a title starting with T or E for the numbering`() {
        assertNull(EpisodeNumbering.displayLabel(null, "Terminator"))
        assertNull(EpisodeNumbering.displayLabel(null, "Everest"))
    }
}
