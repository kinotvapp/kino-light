package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOutputTest {
    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs += it }
    private val hosts = EffectiveHosts(listOf("archive.org", "*.archive.org"))

    private fun items(json: String, allowSeries: Boolean = true, max: Int = PluginOutput.MAX_SEARCH_ITEMS) =
        PluginOutput.page(json, max, allowSeries, allowNext = false, log = log).items

    @Test fun `valid items keep their fields, invalid ones are dropped with a log line`() {
        val json = """[
          {"id":"a1","ref":"r1","title":" Metrópolis ","kind":"movie","year":1927,"poster":"https://x/p.jpg","backdrop":"http://x/b.jpg"},
          {"id":"bad id","ref":"r","title":"t","kind":"movie"},
          {"id":"a2","ref":"","title":"t","kind":"movie"},
          {"id":"a3","ref":"r","title":"   ","kind":"movie"},
          {"id":"a4","ref":"r","title":"t","kind":"episode"},
          {"id":"a1","ref":"dup","title":"dup","kind":"movie"},
          42, null
        ]"""
        val items = items(json)
        assertEquals(1, items.size)
        with(items.single()) {
            assertEquals("Metrópolis", title)
            assertEquals("1927", year)
            assertEquals("https://x/p.jpg", poster)
            assertEquals("", backdrop) // http images are dropped
        }
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `caps and garbage`() {
        val many = (1..150).joinToString(",", "[", "]") { """{"id":"i$it","ref":"r","title":"t","kind":"movie"}""" }
        assertEquals(PluginOutput.MAX_SEARCH_ITEMS, items(many).size)
        assertEquals(100, PluginOutput.MAX_SEARCH_ITEMS)
        assertEquals(emptyList<PluginItem>(), items("not json"))
        assertEquals(emptyList<PluginItem>(), items("{}"))
        assertEquals(emptyList<PluginItem>(), items("42"))
        val hugeRef = """[{"id":"x","ref":"${"r".repeat(5000)}","title":"t","kind":"movie"}]"""
        assertEquals(0, items(hugeRef).size)
    }

    @Test fun `series items are dropped when the plugin cannot list episodes`() {
        val json = """[{"id":"s","ref":"r","title":"t","kind":"series"},{"id":"m","ref":"r","title":"t","kind":"movie"}]"""
        assertEquals(listOf("m"), items(json, allowSeries = false).map { it.id })
    }

    @Test fun `a page carries its cursor only when the plugin declares browse`() {
        val json = """{"items":[{"id":"a","ref":"r","title":"A","kind":"movie"}],"next":"page=2"}"""
        with(PluginOutput.page(json, 100, allowSeries = true, allowNext = true, log = log)) {
            assertEquals(listOf("a"), items.map { it.id })
            assertEquals("page=2", next)
        }
        assertNull(PluginOutput.page(json, 100, allowSeries = true, allowNext = false, log = log).next)
        assertTrue(logs.any { "browse" in it })
        val long = """{"items":[],"next":"${"c".repeat(2049)}"}"""
        assertNull(PluginOutput.page(long, 100, true, true, log = log).next)
        assertNull(PluginOutput.page("""{"items":[],"next":42}""", 100, true, true, log = log).next)
        assertNull(PluginOutput.page("""{"items":[],"next":""}""", 100, true, true, log = log).next)
        assertEquals(emptyList<PluginItem>(), PluginOutput.page("""{"next":"x"}""", 100, true, true, log = log).items)
    }

    @Test fun `SDK v1 item fields are read and bounded`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","originalTitle":" Metropolis ",
          "genres":["Drama"," ","Ciencia ficción","${"g".repeat(40)}","Drama","Cine mudo","Clásico","Extra"],
          "rating":8.3,"runtimeMinutes":153,"ids":{"tmdb":19,"imdb":"tt0017136"},
          "badges":["HD","Latino","Subtitulada","4K"],"lang":"es","quality":"1080p"}]"""
        with(items(json).single()) {
            assertEquals("Metropolis", originalTitle)
            assertEquals(listOf("Drama", "Ciencia ficción", "g".repeat(30), "Cine mudo", "Clásico"), genres)
            assertEquals(8.3, rating!!, 0.0)
            assertEquals(153, runtimeMinutes)
            assertEquals(19, tmdbId)
            assertEquals("tt0017136", imdbId)
            assertEquals(listOf("HD", "Latino", "Subtitulada"), badges)
        }
    }

    @Test fun `out-of-range optional fields are dropped, the item stays`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","rating":11,"runtimeMinutes":0,
          "ids":{"tmdb":-4,"imdb":"nm0000001"},"genres":"Drama","badges":[1,2]},
          {"id":"b","ref":"r","title":"B","kind":"movie","rating":"9","runtimeMinutes":1001,"ids":{"tmdb":"19","imdb":"tt123"}}]"""
        items(json).forEach {
            assertNull(it.rating)
            assertEquals(0, it.runtimeMinutes)
            assertEquals(0, it.tmdbId)
            assertEquals("", it.imdbId)
            assertEquals(emptyList<String>(), it.genres)
            assertEquals(emptyList<String>(), it.badges)
        }
    }

    @Test fun `adult items never reach a screen`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","adult":true},{"id":"b","ref":"r","title":"B","kind":"movie","adult":false}]"""
        assertEquals(listOf("b"), items(json).map { it.id })
        assertTrue(logs.any { "adult" in it })
    }

    @Test fun `rows are capped at 20 and 60 items, empty rows vanish`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val rows = (1..25).joinToString(",", "[", "]") { """{"id":"row$it","title":"Fila $it","items":[$item]}""" }
        assertEquals(PluginOutput.MAX_ROWS, PluginOutput.rows(rows, true, allowBrowse = false, log = log).size)
        val wide = (1..70).joinToString(",", "[", "]") { """{"id":"i$it","ref":"r","title":"t","kind":"movie"}""" }
        assertEquals(PluginOutput.MAX_ROW_ITEMS, PluginOutput.rows("""[{"id":"w","title":"W","items":$wide}]""", true, false, log = log).single().items.size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"x","items":[]}]""", true, false, log = log).size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"","items":[$item]}]""", true, false, log = log).size)
    }

    @Test fun `a row keeps its ref only when the plugin declares browse`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val json = """[{"id":"a","title":"A","items":[$item],"ref":"films"},{"id":"b","title":"B","items":[$item],"ref":"${"x".repeat(4097)}"}]"""
        assertEquals(listOf("films", null), PluginOutput.rows(json, true, allowBrowse = true, log = log).map { it.ref })
        assertEquals(listOf(null, null), PluginOutput.rows(json, true, allowBrowse = false, log = log).map { it.ref })
    }

    @Test fun `duplicate row ids are dropped, first wins, with a log line`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val json = """[
          {"id":"top","title":"Primera","items":[$item]},
          {"id":"mid","title":"Otra","items":[$item]},
          {"id":"top","title":"Duplicada","items":[$item]}
        ]"""
        val rows = PluginOutput.rows(json, true, false, log = log)
        // Home keys each Lazy row by the plugin's row id: a repeat id would crash the whole screen.
        assertEquals(listOf("top", "mid"), rows.map { it.id })
        assertEquals("Primera", rows[0].title)
        assertTrue(logs.any { "duplicate" in it && "top" in it })
    }

    @Test fun `episodes default season 1, drop bad ones and duplicates, read SDK v1 fields`() {
        val json = """{"series":{"title":"Dragnet","tmdbId":123,"poster":"https://x/p.jpg","genres":["Policial"],"year":1951},
          "episodes":[{"number":1,"ref":"a","airDate":"1951-12-16","runtimeMinutes":30},{"season":2,"number":1,"ref":"b","title":"B","airDate":"16/12/1951"},
                      {"number":0,"ref":"c"},{"number":2,"ref":""},{"season":2,"number":1,"ref":"dup"}]}"""
        val out = PluginOutput.episodes(json, log)
        assertEquals(listOf(1 to 1, 2 to 1), out.episodes.map { it.season to it.number })
        assertEquals(listOf("1951-12-16", ""), out.episodes.map { it.airDate })
        assertEquals(30, out.episodes[0].runtimeMinutes)
        assertEquals(123, out.series!!.tmdbId)
        assertEquals(listOf("Policial"), out.series!!.genres)
        assertEquals("1951", out.series!!.year)
        assertThrows(PluginContractException::class.java) { PluginOutput.episodes("[]", log) }
    }

    @Test fun `series ids use the SDK v1 shape and fall back to the flat fields`() {
        val v1 = PluginOutput.episodes("""{"series":{"ids":{"tmdb":7,"imdb":"tt0043208"}},"episodes":[]}""", log).series!!
        assertEquals(7, v1.tmdbId)
        assertEquals("tt0043208", v1.imdbId)
        val flat = PluginOutput.episodes("""{"series":{"tmdbId":9,"imdbId":"tt0000009"},"episodes":[]}""", log).series!!
        assertEquals(9, flat.tmdbId)
        assertEquals("tt0000009", flat.imdbId)
    }

    @Test fun `episodes are capped at 5000`() {
        val many = (1..5200).joinToString(",", "{\"episodes\":[", "]}") { """{"number":$it,"ref":"r$it"}""" }
        assertEquals(PluginOutput.MAX_EPISODES, PluginOutput.episodes(many, log).episodes.size)
    }

    @Test fun `stream must be https on a declared host`() {
        val ok = PluginOutput.stream(
            """{"url":"https://archive.org/download/x/y.mp4","mime":"video/mp4","durationMs":5000,"expiresInSeconds":600,
               "headers":{"Referer":"https://archive.org/","Host":"evil","X-Bad":"a\nb","N":5},
               "subtitles":[{"lang":"en","url":"https://ia8.us.archive.org/s.vtt","format":"vtt"},
                            {"lang":"es","url":"https://evil.example/s.vtt"}]}""",
            hosts,
        )
        assertEquals("https://archive.org/download/x/y.mp4", ok.url)
        assertEquals(mapOf("Referer" to "https://archive.org/"), ok.headers)
        assertEquals(listOf("en"), ok.subtitles.map { it.lang })
        assertEquals(5000L, ok.durationMs)
        assertEquals(600, ok.expiresInSeconds)

        listOf(
            """{"url":"http://archive.org/x.mp4"}""",
            """{"url":"https://evil.example/x.mp4"}""",
            """{"url":"not a url"}""",
            """{"url":"https://archive.org/x.mpd","drmLicenseUrl":"https://archive.org/lic"}""",
            """{"url":"https://archive.org/x.mp4","mime":"video mp4"}""",
            "[]",
        ).forEach { json -> assertThrows(json, PluginContractException::class.java) { PluginOutput.stream(json, hosts) } }
    }

    @Test fun `expiresInSeconds outside 30 to 86400 is ignored`() {
        listOf(29, 86_401, -1).forEach { v ->
            assertEquals(0, PluginOutput.stream("""{"url":"https://archive.org/x.mp4","expiresInSeconds":$v}""", hosts).expiresInSeconds)
        }
        assertEquals(30, PluginOutput.stream("""{"url":"https://archive.org/x.mp4","expiresInSeconds":30}""", hosts).expiresInSeconds)
    }

    @Test fun `a stream and its subtitles may be on a server the person typed, exactly`() {
        val lan = EffectiveHosts(listOf("api.example.com"), listOf(UserHost("http", "192.168.1.10", 8096)))
        val s = PluginOutput.stream(
            """{"url":"http://192.168.1.10:8096/Videos/1/stream.mp4","subtitles":[{"lang":"es","url":"http://192.168.1.10:8096/s.vtt"},{"lang":"en","url":"http://192.168.1.10:8097/s.vtt"}]}""",
            lan,
        )
        assertEquals("http://192.168.1.10:8096/Videos/1/stream.mp4", s.url)
        assertEquals(listOf("es"), s.subtitles.map { it.lang })
        listOf("http://192.168.1.10:9999/x.mp4", "https://192.168.1.10:8096/x.mp4", "http://192.168.1.11:8096/x.mp4", "http://api.example.com/x.mp4").forEach { u ->
            assertThrows(u, PluginContractException::class.java) { PluginOutput.stream("""{"url":"$u"}""", lan) }
        }
    }

    @Test fun `images on IP literals or local names are dropped, so a poster is never a LAN probe`() {
        val items = items(
            """[{"id":"a","ref":"r","title":"A","kind":"movie","poster":"https://192.168.1.1/cgi-bin/reboot","backdrop":"https://cdn.example.com/b.jpg"},
                {"id":"b","ref":"r","title":"B","kind":"movie","poster":"https://localhost:8080/p.jpg","backdrop":"https://[fd00::1]/b.jpg"},
                {"id":"c","ref":"r","title":"C","kind":"movie","poster":"https://nas.local/p.jpg","backdrop":"https://2130706433/b.jpg"}]""",
            allowSeries = false,
        )
        assertEquals(listOf("", "", ""), items.map { it.poster })
        assertEquals(listOf("https://cdn.example.com/b.jpg", "", ""), items.map { it.backdrop })
    }

    @Test fun `an image on a server the person typed is kept, exactly that server only`() {
        val lan = EffectiveHosts(emptyList(), listOf(UserHost("http", "192.168.1.10", 8096)))
        val page = PluginOutput.page(
            """[{"id":"a","ref":"r","title":"A","kind":"movie","poster":"http://192.168.1.10:8096/Items/1/Images/Primary","backdrop":"http://192.168.1.10:80/b.jpg"}]""",
            100, allowSeries = false, allowNext = false, hosts = lan,
        )
        assertEquals("http://192.168.1.10:8096/Items/1/Images/Primary", page.items.single().poster)
        assertEquals("", page.items.single().backdrop)
    }
}
