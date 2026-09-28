package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAcrossProvidersTest {
    private val tv = "plugin:tv"
    private val other = "plugin:other"

    private fun ch(code: String, name: String, provider: String = "xuper", number: Int = 0) =
        LiveChannel(code, name, number, null, provider = provider)

    @Test
    fun `a query finds channels in every provider, not only the active one`() {
        val out = searchAcrossProviders(
            "cnn",
            listOf(
                listOf(ch("c1", "Caracol"), ch("c2", "CNN en Español")),
                listOf(ch("n1", "CNN", tv), ch("n2", "Noticias", tv)),
            ),
        )
        assertEquals(setOf("c2", "plugin:tv:n1"), out.map { it.liveCode }.toSet())
    }

    @Test
    fun `the same name in two providers shows twice, the same live code once`() {
        val out = searchAcrossProviders(
            "cnn",
            listOf(
                listOf(ch("c1", "CNN"), ch("c1", "CNN")),
                listOf(ch("c1", "CNN", tv)),
            ),
        )
        assertEquals(listOf("c1", "plugin:tv:c1"), out.map { it.liveCode })
    }

    @Test
    fun `better matches come first, then provider order, then each provider's own order`() {
        val out = searchAcrossProviders(
            "canal",
            listOf(
                listOf(ch("x1", "Mi Canal Uno"), ch("x2", "Elcanal"), ch("x3", "Canal Dos")),
                listOf(ch("t1", "Canal", tv), ch("t2", "Canal Tres", tv)),
                listOf(ch("o1", "canal", other)),
            ),
        )
        assertEquals(
            listOf(
                "plugin:tv:t1", "plugin:other:o1", // exact name
                "x3", "plugin:tv:t2", // name starts with it
                "x1", // a word starts with it
                "x2", // anywhere in the name
            ),
            out.map { it.liveCode },
        )
    }

    @Test
    fun `accents, case and the exact channel number match like the per-provider filter`() {
        val out = searchAcrossProviders(
            "502",
            listOf(listOf(ch("a", "TNT", number = 502), ch("b", "Canal 5020", number = 7)), listOf(ch("c", "Señal", tv, 5))),
        )
        assertEquals(listOf("a", "b"), out.map { it.code })
        assertEquals(listOf("plugin:tv:c"), searchAcrossProviders("SENAL", listOf(emptyList(), listOf(ch("c", "Señal", tv)))).map { it.liveCode })
    }

    @Test
    fun `the merged list is capped`() {
        val many = (1..400).map { ch("c$it", "Canal $it") }
        val more = (1..50).map { ch("c$it", "Canal $it", tv) }
        val out = searchAcrossProviders("canal", listOf(many, more))
        assertEquals(SEARCH_ACROSS_LIMIT, out.size)
        assertEquals(300, SEARCH_ACROSS_LIMIT)
    }

    @Test
    fun `a blank query is no search at all`() {
        assertTrue(searchAcrossProviders("  ", listOf(listOf(ch("a", "A")))).isEmpty())
    }

    @Test
    fun `the not-yet-loaded note names the plugin`() {
        assertEquals("Algunos canales de Tu servidor aún no se han cargado", notLoadedYetNote("Tu servidor"))
    }
}
