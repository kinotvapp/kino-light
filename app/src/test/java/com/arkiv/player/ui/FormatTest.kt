package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainSynopsisTest {
    @Test fun `strips anilist's html tags`() {
        assertEquals(
            "Un profesor de química. Nota: spoiler",
            plainSynopsis("Un profesor de química.<br><br><i>Nota:</i> spoiler"),
        )
    }

    @Test fun `decodes basic entities`() {
        assertEquals(
            "Tom & Jerry \"el mejor\" y algo más",
            plainSynopsis("Tom &amp; Jerry &quot;el mejor&quot;&nbsp;y algo más"),
        )
    }

    @Test fun `doesn't decode an escaped entity twice`() {
        // "&amp;lt;" is a literal "&lt;" written on purpose: it must stay "&lt;", not become "<".
        assertEquals("&lt;b&gt;", plainSynopsis("&amp;lt;b&amp;gt;"))
    }

    @Test fun `collapses line breaks and multiple spaces`() {
        assertEquals("Una línea y otra", plainSynopsis("Una línea\n\n   y    otra"))
    }

    @Test fun `markup-only ends up empty`() {
        assertEquals("", plainSynopsis("<p></p><br>"))
    }

    @Test fun `null and blank end up empty`() {
        assertEquals("", plainSynopsis(null))
        assertEquals("", plainSynopsis("   "))
    }

    @Test fun `already-clean text is idempotent`() {
        val clean = "Un profesor de química con cáncer terminal."
        assertEquals(clean, plainSynopsis(clean))
        assertEquals(clean, plainSynopsis(plainSynopsis(clean)))
    }
}

class HeroFallbackTest {
    @Test fun `a series with a chapter name drops the title`() {
        assertEquals(
            "S01E03 · Glorious Purpose",
            heroFallback("Loki", "Loki · S01E03 · Glorious Purpose"),
        )
    }

    @Test fun `a series with no chapter name`() {
        assertEquals("S01E03", heroFallback("Loki", "Loki · S01E03"))
    }

    @Test fun `a movie with the title repeated ends up empty`() {
        assertEquals("", heroFallback("Dune", "Dune"))
    }

    @Test fun `the comparison ignores case`() {
        assertEquals("", heroFallback("Dune", "dune"))
        assertEquals("S01E03", heroFallback("Loki", "loki · S01E03"))
    }

    @Test fun `if the title changed afterward the displayName stays intact`() {
        // The label was formatted with a different showTitle: there's no prefix to strip.
        assertEquals("Loki · S01E03", heroFallback("Loki 2021", "Loki · S01E03"))
    }

    @Test fun `tolerates edge spaces in the title`() {
        assertEquals("S01E03", heroFallback("  Loki  ", "Loki · S01E03"))
    }
}

class HeroSubtitleTest {
    @Test fun `a real synopsis is used as-is`() {
        assertEquals(
            "Un profesor de química con cáncer terminal.",
            heroSubtitle("Breaking Bad", "Un profesor de química con cáncer terminal.", "5 episodios"),
        )
    }

    @Test fun `a description that's the title repeated falls back`() {
        // Real archive.org case: whoever uploads the file puts the title as the description.
        assertEquals(
            "1 h 36 min",
            heroSubtitle("Night Of The Living Dead 1990", "Night of the living dead 1990", "1 h 36 min"),
        )
    }

    @Test fun `with no description it falls back`() {
        assertEquals("12 episodios", heroSubtitle("Loki", null, "12 episodios"))
        assertEquals("12 episodios", heroSubtitle("Loki", "   ", "12 episodios"))
    }

    @Test fun `a description that was only markup falls back`() {
        assertEquals("12 episodios", heroSubtitle("Loki", "<p></p>", "12 episodios"))
    }

    @Test fun `a synopsis that starts with the title isn't discarded`() {
        // Starts with the name but keeps going: it's a legitimate synopsis, not a duplication.
        val synopsis = "Avatar Aang, el último Maestro Aire del mundo, se entera de un antiguo poder."
        assertEquals(synopsis, heroSubtitle("Avatar: Aang", synopsis, "20 episodios"))
    }

    @Test fun `cleans the html before comparing against the title`() {
        assertEquals("1 h 36 min", heroSubtitle("Dune", "<p>Dune</p>", "1 h 36 min"))
    }
}
