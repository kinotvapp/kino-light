package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginHomeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero fallback both homes use when "Continuar viendo" is empty: generic over plugin rows. */
class PluginHeroTest {

    private fun item(title: String, kind: String = "movie", extra: Map<String, String> = emptyMap()) =
        GatewayResult(source = "plugin:p", title = title, ref = "plg1:p:$title", kind = kind, extra = extra)

    private fun row(pluginId: String, id: String, vararg items: GatewayResult, name: String = "Plugin $pluginId") =
        PluginHomeRow(pluginId = pluginId, pluginName = name, color = 0L, id = id, title = "Fila $id", items = items.toList())

    @Test fun `picks the first item of the first row, whatever plugin it is`() {
        val rows = listOf(row("xuper", "r1", item("A"), item("B")), row("otro", "r2", item("C")))
        val pick = pluginHeroPick(rows)!!
        assertEquals("A", pick.item.title)
        assertEquals("xuper", pick.row.pluginId)

        // Not a Xuper carve-out: any plugin's first row wins when it comes first.
        assertEquals("C", pluginHeroPick(listOf(row("otro", "r2", item("C")), row("xuper", "r1", item("A"))))!!.item.title)
    }

    @Test fun `an empty first row is skipped instead of leaving the hero empty`() {
        assertEquals("C", pluginHeroPick(listOf(row("a", "vacía"), row("b", "r2", item("C"))))!!.item.title)
    }

    @Test fun `no rows, or rows with no items, give no pick`() {
        assertNull(pluginHeroPick(emptyList()))
        assertNull(pluginHeroPick(listOf(row("a", "vacía"))))
    }

    @Test fun `the image is the backdrop first, then the poster, never a blank url`() {
        val both = pluginHeroPick(listOf(row("a", "r", item("A", extra = mapOf("backdrop" to "https://b", "poster" to "https://p")))))!!
        assertEquals("https://b", both.imageUrl)
        val posterOnly = pluginHeroPick(listOf(row("a", "r", item("A", extra = mapOf("backdrop" to "", "poster" to "https://p")))))!!
        assertEquals("https://p", posterOnly.imageUrl)
        val none = pluginHeroPick(listOf(row("a", "r", item("A", extra = mapOf("backdrop" to "", "poster" to "")))))!!
        assertNull(none.imageUrl)
    }

    @Test fun `the meta line is plugin, kind and rating, omitting what's missing`() {
        val movie = pluginHeroPick(listOf(row("x", "r", item("A", extra = mapOf("rating" to "7.7")), name = "Xuper")))!!
        assertEquals("Xuper  ·  Película  ·  ★ 7.7", movie.meta())
        val series = pluginHeroPick(listOf(row("x", "r", item("B", kind = "series"), name = "Xuper")))!!
        assertEquals("Xuper  ·  Serie", series.meta())
        val live = pluginHeroPick(listOf(row("x", "r", item("Canal", kind = "live"), name = "Xuper")))!!
        assertEquals("Xuper  ·  En vivo", live.meta())
    }
}
