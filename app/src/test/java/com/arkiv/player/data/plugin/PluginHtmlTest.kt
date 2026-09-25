package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginHtmlTest {
    @Test fun `select returns text, inner html and attributes`() {
        val out = JSONArray(PluginHtml.selectJson("<ul><li><a href='/a' class='x'>Uno</a></li><li><a href='/b'>Dos <b>!</b></a></li></ul>", "li a"))
        assertEquals(2, out.length())
        assertEquals("Uno", out.getJSONObject(0).getString("text"))
        assertEquals("/a", out.getJSONObject(0).getJSONObject("attrs").getString("href"))
        assertEquals("Dos <b>!</b>", out.getJSONObject(1).getString("html"))
    }

    @Test fun `matches are capped`() =
        assertEquals(PluginHtml.MAX_MATCHES, JSONArray(PluginHtml.selectJson("<p>x</p>".repeat(600), "p")).length())

    @Test fun `an invalid selector throws so the plugin can catch it`() {
        assertThrows(Exception::class.java) { PluginHtml.selectJson("<p>", "a[") }
    }

    @Test fun `output size is capped even when nested elements repeat their descendants' html`() {
        // Each of the outer divs' html() re-serializes nearly the whole remaining nested tree, so
        // MAX_MATCHES alone doesn't bound total output: this is a few thousand nested divs, well
        // under MAX_HTML_CHARS as input, but their combined html()+text() blows well past a 5 MB cap.
        val nested = "<div>".repeat(2000) + "x" + "</div>".repeat(2000)
        assertThrows(Exception::class.java) { PluginHtml.selectJson(nested, "div") }
    }
}
