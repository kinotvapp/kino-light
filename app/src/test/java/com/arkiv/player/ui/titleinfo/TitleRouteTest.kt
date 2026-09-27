package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginRef
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleRouteTest {

    /**
     * Android's `Uri.decode`, which is what Navigation applies to an argument: percent escapes only,
     * a `+` stays a `+`. (`URLDecoder` would turn it into a space and hide a space encoded as `+`.)
     */
    private fun uriDecode(value: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                bytes.write(value.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(value[i].code)
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    /** What Navigation does with a route: split path and query, decode each value like Android does. */
    private fun argsOf(route: String): Map<String, String> {
        val (path, query) = route.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val out = mutableMapOf("id" to uriDecode(path.removePrefix("title/")))
        query.split("&").filter { it.isNotEmpty() }.forEach { pair ->
            val (k, v) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            out[k] = uriDecode(v)
        }
        return out
    }

    /** A plugin's route, rebuilt the way the roots do it. */
    private fun roundTrip(result: GatewayResult): CatalogItem? = titleRoute(result)?.let { r -> argsOf(r).let { m -> titleItemFrom { m[it] } } }

    private fun pluginResult(
        kind: String = "series",
        itemId: String = "i1",
        ref: String = PluginRef("demo", itemId, if (kind == "series") PluginRef.SERIES else PluginRef.MOVIE, "own").encode(),
        title: String = "Título ñ & Co",
        extra: Map<String, String> = emptyMap(),
    ) = GatewayResult(
        source = "plugin:demo", title = title, ref = ref, kind = kind, year = "2020",
        extra = mapOf(
            "pluginItemId" to itemId, "pluginName" to "Demo", "color" to "#FF8800",
            "poster" to "https://img/p.jpg?w=300&h=450", "backdrop" to "https://img/b.jpg",
            "overview" to "Sinopsis del plugin.", "genres" to "Drama, Comedia", "rating" to "7.5",
            "runtimeMinutes" to "45", "tmdbId" to "123", "imdbId" to "tt1234567",
        ) + extra,
    )

    private fun pluginArgs(result: GatewayResult): Map<String, String> = argsOf(titleRoute(result)!!)

    @Test
    fun `characters that break URLs come back intact`() {
        val nasty = "Ñandú & Co? #1 100% \"A+B\" it's 🎬 ¡Hola!"
        val out = roundTrip(pluginResult(title = nasty, extra = mapOf("overview" to nasty, "genres" to "Sci-Fi, Drama")))!!
        assertEquals(nasty, out.title)
        assertEquals(nasty, out.description)
        assertEquals(listOf("Sci-Fi", "Drama"), out.genres)
    }

    @Test
    fun `a space travels as a percent escape, never as a plus`() {
        // Android leaves a '+' in an argument as a '+': a space encoded that way would come back as one.
        val result = pluginResult(title = "Dos palabras", extra = mapOf("overview" to "a b c"))
        val route = titleRoute(result)!!
        assertFalse(route, route.contains('+'))
        val out = roundTrip(result)!!
        assertEquals("Dos palabras", out.title)
        assertEquals("a b c", out.description)
    }

    @Test
    fun `a long synopsis is cut at 600 characters with an ellipsis`() {
        val out = roundTrip(pluginResult(extra = mapOf("overview" to "a".repeat(5000))))!!
        assertEquals(TITLE_DESC_MAX, out.description.length)
        assertTrue(out.description.endsWith("…"))
    }

    @Test
    fun `a synopsis of exactly 600 characters is left alone`() {
        val text = "b".repeat(TITLE_DESC_MAX)
        assertEquals(text, clipSynopsis(text))
        assertEquals(text, roundTrip(pluginResult(extra = mapOf("overview" to text)))!!.description)
    }

    @Test
    fun `the worst-case synopsis keeps the route well under a few kilobytes`() {
        val accents = "é".repeat(TITLE_DESC_MAX)
        assertTrue(titleRoute(pluginResult(extra = mapOf("overview" to accents)))!!.length < 6000)
    }

    @Test
    fun `a route with no id cannot be rebuilt`() {
        assertNull(titleItemFrom { null })
        assertNull(titleItemFrom { if (it == "id") "  " else if (it == "src") "plugin" else null })
    }

    @Test
    fun `a route from before Xuper became a plugin cannot be rebuilt and has no origin`() {
        // Such a route (no `src=plugin`, a Magis ref rebuilt from id and type) can come back from a
        // saved back stack; no source serves it any more, so the roots pop it instead of drawing it.
        val legacy = mapOf("id" to "c5", "type" to "teleplay", "title" to "Show T1")
        assertNull(titleItemFrom { legacy[it] })
        assertNull(titleOriginFrom { legacy[it] })
        assertNull(titleOriginFrom { null })
    }

    @Test
    fun `a plugin series result survives the route round trip`() {
        val result = pluginResult()
        val args = pluginArgs(result)
        val item = titleItemFrom { args[it] }!!
        assertEquals("i1", item.id)
        assertEquals("Título ñ & Co", item.title)
        assertEquals(result.ref, item.ref)
        assertEquals("series", item.type)
        assertEquals("https://img/p.jpg?w=300&h=450", item.poster)
        assertEquals("https://img/b.jpg", item.backdrop)
        assertEquals("Sinopsis del plugin.", item.description)
        assertEquals(listOf("Drama", "Comedia"), item.genres)
        assertEquals(7.5, item.score!!, 0.001)
        assertEquals(2700, item.durationS)
        assertEquals(
            TitleOrigin.Plugin(PluginTitleExtras("Demo", "#FF8800", "2020", 123, "tt1234567")),
            titleOriginFrom { args[it] },
        )
    }

    @Test
    fun `a plugin movie keeps its kind`() {
        val args = pluginArgs(pluginResult(kind = "movie"))
        assertEquals("movie", titleItemFrom { args[it] }!!.type)
    }

    @Test
    fun `hostile characters in a plugin title come back intact`() {
        val nasty = "Ñandú & Co? #1 100% \"A+B\" it's 🎬 ¡Hola!"
        val args = pluginArgs(pluginResult(title = nasty, extra = mapOf("overview" to nasty)))
        val item = titleItemFrom { args[it] }!!
        assertEquals(nasty, item.title)
        assertEquals(nasty, item.description)
    }

    @Test
    fun `a huge ref and huge image URLs stay under the cap and the URLs are left out`() {
        val hugeRef = PluginRef("demo", "i1", PluginRef.SERIES, "r".repeat(3000)).encode()
        val huge = "https://img/" + "x".repeat(3000)
        val route = titleRoute(
            pluginResult(ref = hugeRef, extra = mapOf("poster" to huge, "backdrop" to huge, "overview" to "é".repeat(5000))),
        )!!
        assertTrue("route was ${route.length} chars", route.length < 9000)
        val args = argsOf(route)
        val item = titleItemFrom { args[it] }!!
        assertEquals(hugeRef, item.ref)
        assertNull(item.poster)
        assertNull(item.backdrop)
        assertEquals(TITLE_DESC_MAX, item.description.length)
    }

    @Test
    fun `an image URL as long as a plugin may send is kept so playing still saves its art`() {
        // PluginOutput allows 2048 characters; signed CDN URLs reach that. playPlugin reads the art
        // from the same result, so dropping such a URL left the library row without a poster.
        val longest = "https://cdn/" + "x".repeat(2048 - "https://cdn/".length)
        val args = pluginArgs(pluginResult(extra = mapOf("poster" to longest, "backdrop" to longest)))
        val item = titleItemFrom { args[it] }!!
        assertEquals(longest, item.poster)
        assertEquals(longest, item.backdrop)
        assertTrue(titleRoute(pluginResult(extra = mapOf("poster" to longest, "backdrop" to longest)))!!.length < 15_000)
    }

    @Test
    fun `an image URL at the limit is kept`() {
        val atLimit = "https://img/" + "x".repeat(TITLE_URL_MAX - "https://img/".length)
        val args = pluginArgs(pluginResult(extra = mapOf("poster" to atLimit)))
        assertEquals(atLimit, titleItemFrom { args[it] }!!.poster)
    }

    @Test
    fun `a plugin result that cannot be opened has no route`() {
        assertNull(titleRoute(pluginResult(itemId = " ")))
        assertNull(titleRoute(pluginResult(ref = "garbage")))
        // Another plugin's ref under this plugin's source.
        assertNull(titleRoute(pluginResult(ref = PluginRef("other", "i1", PluginRef.SERIES, "own").encode())))
        // Not a plugin: Caracol keeps its own flow, and nothing emits the retired native "magis".
        assertNull(titleRoute(GatewayResult(source = "ditu", title = "x", ref = "ditu1:a:b")))
        assertNull(titleRoute(GatewayResult(source = "magis", title = "x", ref = "r", extra = mapOf("content_id" to "c9"))))
    }

    @Test
    fun `a plugin route with no ref cannot rebuild an item`() {
        assertNull(titleItemFrom { if (it == "id") "i1" else if (it == "src") "plugin" else null })
    }

    @Test
    fun `a plugin route with an unknown type rebuilds a movie`() {
        val item = titleItemFrom {
            mapOf("id" to "i1", "src" to "plugin", "ref" to "plg1:demo:abc", "type" to "teleplay")[it]
        }!!
        assertEquals("movie", item.type)
    }

    @Test
    fun `a bad TMDB id or a missing plugin name is tolerated`() {
        val origin = titleOriginFrom { mapOf("src" to "plugin", "tmdb" to "abc")[it] }
        assertEquals(TitleOrigin.Plugin(PluginTitleExtras()), origin)
    }
}
