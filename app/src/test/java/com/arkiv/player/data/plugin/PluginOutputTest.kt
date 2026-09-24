package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOutputTest {
    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs += it }
    private val hosts = listOf("archive.org", "*.archive.org")

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
        val items = PluginOutput.items(json, allowSeries = true, log = log)
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
        val many = (1..80).joinToString(",", "[", "]") { """{"id":"i$it","ref":"r","title":"t","kind":"movie"}""" }
        assertEquals(PluginOutput.MAX_ITEMS, PluginOutput.items(many, true, log).size)
        assertEquals(emptyList<PluginItem>(), PluginOutput.items("not json", true, log))
        assertEquals(emptyList<PluginItem>(), PluginOutput.items("{}", true, log))
        val hugeRef = """[{"id":"x","ref":"${"r".repeat(5000)}","title":"t","kind":"movie"}]"""
        assertEquals(0, PluginOutput.items(hugeRef, true, log).size)
    }

    @Test fun `series items are dropped when the plugin cannot list episodes`() {
        val json = """[{"id":"s","ref":"r","title":"t","kind":"series"},{"id":"m","ref":"r","title":"t","kind":"movie"}]"""
        assertEquals(listOf("m"), PluginOutput.items(json, allowSeries = false, log = log).map { it.id })
    }

    @Test fun `rows are capped and empty rows vanish`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val rows = (1..12).joinToString(",", "[", "]") { """{"id":"row$it","title":"Fila $it","items":[$item]}""" }
        assertEquals(PluginOutput.MAX_ROWS, PluginOutput.rows(rows, true, log).size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"x","items":[]}]""", true, log).size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"","items":[$item]}]""", true, log).size)
    }

    @Test fun `duplicate row ids are dropped, first wins, with a log line`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val json = """[
          {"id":"top","title":"Primera","items":[$item]},
          {"id":"mid","title":"Otra","items":[$item]},
          {"id":"top","title":"Duplicada","items":[$item]}
        ]"""
        val rows = PluginOutput.rows(json, true, log)
        // Home keys each Lazy row by the plugin's row id: a repeat id would crash the screen.
        assertEquals(listOf("top", "mid"), rows.map { it.id })
        assertEquals("Primera", rows[0].title)
        assertTrue(logs.any { "duplicate" in it && "top" in it })
    }

    @Test fun `episodes default season 1, drop bad ones and duplicates`() {
        val json = """{"series":{"title":"Dragnet","tmdbId":123,"poster":"https://x/p.jpg"},
          "episodes":[{"number":1,"ref":"a"},{"season":2,"number":1,"ref":"b","title":"B"},
                      {"number":0,"ref":"c"},{"number":2,"ref":""},{"season":2,"number":1,"ref":"dup"}]}"""
        val out = PluginOutput.episodes(json, log)
        assertEquals(listOf(1 to 1, 2 to 1), out.episodes.map { it.season to it.number })
        assertEquals(123, out.series!!.tmdbId)
        assertThrows(PluginContractException::class.java) { PluginOutput.episodes("[]", log) }
    }

    @Test fun `stream must be https on a declared host`() {
        val ok = PluginOutput.stream(
            """{"url":"https://archive.org/download/x/y.mp4","mime":"video/mp4","durationMs":5000,
               "headers":{"Referer":"https://archive.org/","Host":"evil","X-Bad":"a\nb","N":5},
               "subtitles":[{"lang":"en","url":"https://ia8.us.archive.org/s.vtt","format":"vtt"},
                            {"lang":"es","url":"https://evil.example/s.vtt"}]}""",
            hosts,
        )
        assertEquals("https://archive.org/download/x/y.mp4", ok.url)
        assertEquals(mapOf("Referer" to "https://archive.org/"), ok.headers)
        assertEquals(listOf("en"), ok.subtitles.map { it.lang })
        assertEquals(5000L, ok.durationMs)

        listOf(
            """{"url":"http://archive.org/x.mp4"}""",
            """{"url":"https://evil.example/x.mp4"}""",
            """{"url":"not a url"}""",
            """{"url":"https://archive.org/x.mpd","drmLicenseUrl":"https://archive.org/lic"}""",
            """{"url":"https://archive.org/x.mp4","mime":"video mp4"}""",
            "[]",
        ).forEach { json -> assertThrows(json, PluginContractException::class.java) { PluginOutput.stream(json, hosts) } }
    }

    @Test fun `images on IP literals or local names are dropped, so a poster is never a LAN probe`() {
        val items = PluginOutput.items(
            """[{"id":"a","ref":"r","title":"A","kind":"movie","poster":"https://192.168.1.1/cgi-bin/reboot","backdrop":"https://cdn.example.com/b.jpg"},
                {"id":"b","ref":"r","title":"B","kind":"movie","poster":"https://localhost:8080/p.jpg","backdrop":"https://[fd00::1]/b.jpg"},
                {"id":"c","ref":"r","title":"C","kind":"movie","poster":"https://nas.local/p.jpg","backdrop":"https://2130706433/b.jpg"}]""",
            allowSeries = false,
        )
        assertEquals(listOf("", "", ""), items.map { it.poster })
        assertEquals(listOf("https://cdn.example.com/b.jpg", "", ""), items.map { it.backdrop })
    }
}
