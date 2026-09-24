package com.arkiv.player.ui.kinobot

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown -> AnnotatedString parser: markers are stripped from the visible text, styles land on
 * the right ranges, and malformed/streaming input never throws. Only the plain `.text` and the bold
 * spans are asserted (enough to prove markers are consumed, not shown).
 */
class KinobotMarkdownTest {

    private fun text(src: String) = markdownToAnnotated(src).text

    @Test fun `bold markers are removed from the visible text`() {
        assertEquals("Mira Akira ya", text("Mira **Akira** ya"))
        assertEquals("Mira Akira ya", text("Mira __Akira__ ya"))
    }

    @Test fun `bold actually applies a bold span`() {
        val s = markdownToAnnotated("Mira **Akira** ya")
        val bold = s.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Akira", s.text.substring(bold.start, bold.end))
    }

    @Test fun `italic, code and strikethrough markers are stripped`() {
        assertEquals("un clasico", text("un *clasico*"))
        assertEquals("un clasico", text("un _clasico_"))
        assertEquals("el codigo aqui", text("el `codigo` aqui"))
        assertEquals("ya no", text("~~ya no~~"))
    }

    @Test fun `headers render their text without the hashes`() {
        assertEquals("Recomendaciones", text("## Recomendaciones"))
        assertEquals("Titulo", text("### Titulo"))
    }

    @Test fun `bullets become a dot and numbered lists are left as-is`() {
        assertEquals("•  Akira", text("- Akira"))
        assertEquals("•  Akira", text("* Akira"))
        assertEquals("1. Akira", text("1. Akira"))
    }

    @Test fun `a link shows only its label, not the url`() {
        assertEquals("Ver Akira", text("Ver [Akira](https://x.com/a)"))
    }

    @Test fun `bold with code inside is stripped (recursion), and separate bold + italic both work`() {
        assertEquals("con codigo dentro", text("**con `codigo` dentro**"))
        assertEquals("Akira es genial", text("**Akira** es *genial*"))
    }

    @Test fun `an unterminated marker is shown as plain text (streaming)`() {
        assertEquals("Te recomiendo **Aki", text("Te recomiendo **Aki"))
        assertEquals("empezando `co", text("empezando `co"))
    }

    @Test fun `plain text and blank input pass through unchanged`() {
        assertEquals("hola como estas", text("hola como estas"))
        assertEquals("", text(""))
        assertEquals("linea1\nlinea2", text("linea1\nlinea2"))
    }

    @Test fun `does not throw on odd marker soup`() {
        // Just needs to not crash.
        listOf("***", "__", "``", "[](", "~~~", "**a*_b`c", "###").forEach {
            assertTrue(markdownToAnnotated(it).text.length >= 0)
        }
    }
}
