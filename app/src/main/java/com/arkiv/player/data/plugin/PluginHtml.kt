package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** `kino.html.select(html, css)`: Jsoup's CSS selectors, returned as JSON for the JS side. */
object PluginHtml {
    const val MAX_HTML_CHARS = 2_000_000
    const val MAX_MATCHES = 500

    fun selectJson(html: String, css: String): String {
        val doc = Jsoup.parse(html.take(MAX_HTML_CHARS))
        doc.outputSettings().prettyPrint(false)
        val out = JSONArray()
        for (el in doc.select(css).take(MAX_MATCHES)) {
            val attrs = JSONObject()
            el.attributes().forEach { attrs.put(it.key, it.value) }
            out.put(JSONObject().put("text", el.text()).put("html", el.html()).put("attrs", attrs))
        }
        return out.toString()
    }
}
