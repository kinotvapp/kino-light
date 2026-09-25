package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

/** `kino.html.select(html, css)`: Jsoup's CSS selectors, returned as JSON for the JS side. */
object PluginHtml {
    const val MAX_HTML_CHARS = 2_000_000
    const val MAX_MATCHES = 500

    /**
     * Matches `PluginHttp`'s fetch body cap. `MAX_MATCHES` bounds how many elements are returned,
     * not how big the output is: a match's `html()`/`text()` re-serializes its whole descendant
     * subtree, so a few hundred nested elements (e.g. `div > div > div …`) make every ancestor
     * match repeat nearly the same huge string, which is quadratic in nesting depth even with
     * `MAX_HTML_CHARS` bounding the input. Track the running output size and stop before it grows
     * unbounded, rather than after building it.
     */
    const val MAX_OUTPUT_CHARS = 5 * 1024 * 1024

    fun selectJson(html: String, css: String): String {
        val doc = Jsoup.parse(html.take(MAX_HTML_CHARS))
        doc.outputSettings().prettyPrint(false)
        val out = JSONArray()
        var outputChars = 0
        for (el in doc.select(css).take(MAX_MATCHES)) {
            val text = el.text()
            val innerHtml = el.html()
            outputChars += text.length + innerHtml.length
            if (outputChars > MAX_OUTPUT_CHARS) {
                throw IOException("html.select output exceeds the $MAX_OUTPUT_CHARS char cap (nested elements repeat their descendants)")
            }
            val attrs = JSONObject()
            el.attributes().forEach { attrs.put(it.key, it.value) }
            out.put(JSONObject().put("text", text).put("html", innerHtml).put("attrs", attrs))
        }
        return out.toString()
    }
}
