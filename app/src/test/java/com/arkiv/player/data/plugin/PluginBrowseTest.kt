package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK v1 on the content-source side: pages, cursors, browse, typed errors, typed servers. */
class PluginBrowseTest {
    private fun plugin(caps: Set<String> = setOf("search", "browse", "episodes", "resolve")) =
        InstalledPlugin(
            PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), caps, null, null),
            InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L),
            null,
        )

    private class Caller(val answer: (String, String) -> String) : PluginCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += function to argJson
            return answer(function, argJson)
        }
    }

    private fun source(caller: PluginCaller, p: InstalledPlugin = plugin(), hosts: EffectiveHosts = EffectiveHosts(p.record.hosts)) =
        PluginContentSource(p, caller, hosts, log = {})
    private val item = """{"id":"m1","ref":"R1","title":"Uno","kind":"movie"}"""

    @Test fun `search sends the SDK v1 query and passes the page cursor on SourceDone`() = runTest {
        val caller = Caller { _, _ -> """{"items":[$item],"next":"p2"}""" }
        val ctx = GatewaySearchQuery(q = "uno", type = "movie", originalTitle = "One", altTitles = listOf("Un", "", "x".repeat(300), "Un", "a", "b", "c", "d"))
        val events = source(caller).search(ctx).toList()
        assertEquals("p2", (events.last() as SearchEvent.SourceDone).more)
        val arg = JSONObject(caller.calls.single().second)
        assertEquals("One", arg.getString("originalTitle"))
        assertEquals(listOf("Un", "x".repeat(200), "a", "b", "c"), arg.getJSONArray("altTitles").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertTrue(arg.isNull("cursor"))
    }

    @Test fun `without browse a search page's cursor is dropped`() = runTest {
        val caller = Caller { _, _ -> """{"items":[$item],"next":"p2"}""" }
        val events = source(caller, plugin(caps = setOf("search", "resolve"))).search(GatewaySearchQuery("uno")).toList()
        assertNull((events.last() as SearchEvent.SourceDone).more)
    }

    @Test fun `searchPage continues the same query from the cursor`() = runTest {
        val caller = Caller { _, _ -> """{"items":[$item]}""" }
        val page = source(caller).searchPage(PluginContentSource.queryJson(GatewaySearchQuery("uno")), "p2")
        assertEquals(1, page.items.size)
        assertNull(page.next)
        assertEquals("p2", JSONObject(caller.calls.single().second).getString("cursor"))
        assertEquals("uno", JSONObject(caller.calls.single().second).getString("q"))
    }

    @Test fun `browse sends ref and cursor, and wraps what comes back`() = runTest {
        val caller = Caller { _, _ -> """{"items":[$item],"next":"c2"}""" }
        val first = source(caller).browse("films", null)
        assertEquals("c2", first.next)
        assertEquals(PluginRef("demo", "m1", PluginRef.MOVIE, "R1"), PluginRef.decode(first.items.single().ref))
        source(caller).browse("films", "c2")
        assertEquals(
            listOf("films" to null, "films" to "c2"),
            caller.calls.map { JSONObject(it.second).let { o -> o.getString("ref") to (if (o.isNull("cursor")) null else o.getString("cursor")) } },
        )
    }

    @Test fun `browse on a plugin without the capability never calls it`() = runTest {
        val caller = Caller { _, _ -> "[]" }
        assertThrows(GatewayException::class.java) { kotlinx.coroutines.runBlocking { source(caller, plugin(caps = setOf("search", "resolve"))).browse("x", null) } }
        assertEquals(emptyList<Pair<String, String>>(), caller.calls)
    }

    @Test fun `typed errors become what the screens expect`() = runTest {
        fun failing(code: String) = source(PluginCaller { _, _, _, _ -> throw PluginErrorException(code, "detalle") })
        val ref = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()
        val auth = assertThrows(PluginSetupRequiredException::class.java) { kotlinx.coroutines.runBlocking { failing("auth_required").resolve(ref) } }
        assertEquals("demo", auth.pluginId)
        assertEquals("Configura Demo en Ajustes ▸ Plugins", auth.message)
        assertEquals("Este contenido no está disponible en tu región", assertThrows(GatewayBlockedException::class.java) { kotlinx.coroutines.runBlocking { failing("geo_blocked").resolve(ref) } }.message)
        assertEquals("No se encontró en Demo", assertThrows(GatewayException::class.java) { kotlinx.coroutines.runBlocking { failing("not_found").browse("x", null) } }.message)
        assertEquals("Demo: detalle", assertThrows(GatewayException::class.java) { kotlinx.coroutines.runBlocking { failing("network").resolve(ref) } }.message)
    }

    @Test fun `a typed error in search is the source's error line`() = runTest {
        val events = source(PluginCaller { _, _, _, _ -> throw PluginErrorException("rate_limited", "x") }).search(GatewaySearchQuery("uno")).toList()
        val err = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("Demo está limitando las peticiones; intenta en unos minutos", err.error)
        assertTrue(err.cause is PluginErrorException)
    }

    @Test fun `a stream on the typed server resolves, and its expiry travels`() = runTest {
        val lan = EffectiveHosts(listOf("example.com"), listOf(UserHost("http", "10.0.2.2", 8096)))
        val caller = Caller { _, _ -> """{"url":"http://10.0.2.2:8096/v.mp4","expiresInSeconds":600}""" }
        val play = source(caller, hosts = lan).resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode())
        assertEquals("http://10.0.2.2:8096/v.mp4", play.url)
        assertEquals(600, play.expiresInSeconds)
        assertThrows(GatewayException::class.java) {
            kotlinx.coroutines.runBlocking { source(caller).resolve(PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()) }
        }
    }

    @Test fun `SDK v1 item fields ride in the result's extra`() {
        val r = PluginContentSource.resultFrom(
            plugin(),
            PluginItem("m1", "R1", "Uno", "movie", originalTitle = "One", genres = listOf("Drama", "Cine"), rating = 7.25, runtimeMinutes = 90, tmdbId = 19, imdbId = "tt0017136", badges = listOf("HD", "Latino")),
        )
        assertEquals("One", r.extra["originalTitle"])
        assertEquals("Drama, Cine", r.extra["genres"])
        assertEquals("7.3", r.extra["rating"])
        assertEquals("90", r.extra["runtimeMinutes"])
        assertEquals("19", r.extra["tmdbId"])
        assertEquals("tt0017136", r.extra["imdbId"])
        assertEquals("HD|Latino", r.extra["badges"])
        assertNull(PluginContentSource.resultFrom(plugin(), PluginItem("m2", "R", "Dos", "movie")).extra["tmdbId"])
    }
}
