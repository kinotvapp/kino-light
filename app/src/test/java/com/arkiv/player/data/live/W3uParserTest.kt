package com.arkiv.player.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiseplay W3U lists, shaped like the ones that circulate (docs.dimplay.app/playlists/w3u). */
class W3uParserTest {
    private val bundle = """
        {
          "name": "Mis listas",
          "author": "yo",
          "epg": "https://example.com/guide.xml.gz",
          "groups": [
            { "name": "Deportes", "url": "https://example.com/deportes.w3u" },
            { "name": "Noticias", "image": "https://x.example.com/n.png",
              "stations": [
                { "name": "Canal X", "url": "https://x.example.com/live.m3u8", "image": "https://x.example.com/logo.png",
                  "referer": "https://x.example.com/", "userAgent": "Mozilla/5.0", "epgId": "CanalX.co" },
                { "name": "Con headers", "url": "https://y.example.com/a.m3u8",
                  "headers": { "user-agent": "Kodi/20", "Origin": "https://y.example.com", "X-Forwarded-For": "1.1.1.1" } },
              ],
              "groups": [ { "name": "Regionales", "stations": [ { "name": "Canal R", "url": "http://r.example.com/r.m3u8" } ] } ]
            }
          ],
          "stations": [ { "name": "Suelto", "url": "https://s.example.com/s.m3u8" } ]
        }
    """.trimIndent()

    @Test fun `a bundle gives its stations with their group path, logo, headers and epg id`() {
        val l = W3uParser.parse(bundle)!!
        assertEquals("Mis listas", l.name)
        assertEquals(listOf("https://example.com/guide.xml.gz"), l.epgUrls)
        val byName = l.entries.associateBy { it.name }
        assertEquals(setOf("Canal X", "Con headers", "Canal R", "Suelto"), byName.keys)
        val x = byName.getValue("Canal X")
        assertEquals("Noticias", x.group)
        assertEquals("https://x.example.com/logo.png", x.logo)
        assertEquals("CanalX.co", x.tvgId)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://x.example.com/"), x.headers)
        assertEquals(mapOf("User-Agent" to "Kodi/20", "Origin" to "https://y.example.com"), byName.getValue("Con headers").headers)
        assertEquals("Noticias · Regionales", byName.getValue("Canal R").group)
        assertEquals("", byName.getValue("Suelto").group)
    }

    @Test fun `a group with a url is a link to another list, named by its group path`() {
        val l = W3uParser.parse(bundle)!!
        assertEquals(listOf(W3uLink("Deportes", "https://example.com/deportes.w3u")), l.links)
    }

    @Test fun `stations that are web pages, host-resolved, other schemes or nameless are skipped and counted`() {
        val text = """{"name":"L","stations":[
            {"name":"Embebido","url":"https://web.example.com/player.html","embed":true},
            {"name":"YouTube","url":"https://youtube.com/watch?v=x","isHost":true},
            {"name":"Ace","url":"acestream://0123456789012345678901234567890123456789"},
            {"name":"Archivo","url":"file:///sdcard/x.m3u8"},
            {"name":"JS","url":"javascript:alert(1)"},
            {"name":"Datos","url":"data:text/plain,hola"},
            {"name":"Intent","url":"intent://x#Intent;end"},
            {"name":"","url":"https://ok.example.com/a.m3u8"},
            {"name":"Sin url"},
            {"name":"Bueno","url":"https://ok.example.com/b.m3u8"}
        ]}"""
        val l = W3uParser.parse(text)!!
        assertEquals(listOf("Bueno"), l.entries.map { it.name })
        assertEquals(9, l.skipped)
    }

    @Test fun `a link with a non-http scheme is dropped`() {
        val l = W3uParser.parse("""{"name":"L","groups":[{"name":"A","url":"file:///etc/x.w3u"},{"name":"B","url":"content://x/y"}]}""")!!
        assertEquals(emptyList<W3uLink>(), l.links)
    }

    @Test fun `header values with control characters are dropped`() {
        val l = W3uParser.parse("""{"name":"L","stations":[{"name":"A","url":"https://a.example.com/a","userAgent":"x\r\nX-Evil: 1"}]}""")!!
        assertEquals(emptyMap<String, String>(), l.entries.single().headers)
    }

    @Test fun `the station cap keeps the first ones and counts the rest as skipped`() {
        val stations = (1..30).joinToString(",") { """{"name":"C$it","url":"https://a.example.com/$it"}""" }
        val l = W3uParser.parse("""{"name":"L","stations":[$stations]}""", maxEntries = 10)!!
        assertEquals(10, l.entries.size)
        assertEquals(20, l.skipped)
    }

    @Test fun `groups nested past the limit are not walked`() {
        var g = """{"name":"Fondo","stations":[{"name":"Hondo","url":"https://a.example.com/h"}]}"""
        repeat(W3uParser.MAX_GROUP_DEPTH + 2) { g = """{"name":"G$it","groups":[$g]}""" }
        val l = W3uParser.parse("""{"name":"L","groups":[$g]}""")!!
        assertEquals(emptyList<String>(), l.entries.map { it.name })
    }

    @Test fun `JSON that is not a W3U list is null, and so is broken JSON`() {
        assertNull(W3uParser.parse("""{"foo":1}"""))
        assertNull(W3uParser.parse("""[1,2,3]"""))
        assertNull(W3uParser.parse("""{"groups":"""))
        assertNull(W3uParser.parse("#EXTM3U\n#EXTINF:-1,A\nhttps://a.example.com/a\n"))
    }

    @Test fun `epg may be a comma list or an array, http(s) only, at most three`() {
        val a = W3uParser.parse("""{"name":"L","epg":"https://e.example.com/1.xml, file:///x.xml ,http://e.example.com/2.xml","stations":[]}""")!!
        assertEquals(listOf("https://e.example.com/1.xml", "http://e.example.com/2.xml"), a.epgUrls)
        val b = W3uParser.parse("""{"name":"L","epg":["https://e.example.com/1.xml","https://e.example.com/2.xml","https://e.example.com/3.xml","https://e.example.com/4.xml"],"groups":[]}""")!!
        assertEquals(3, b.epgUrls.size)
    }

    @Test fun `names are trimmed and long ones cut, never with control characters`() {
        val l = W3uParser.parse("""{"name":"L","stations":[{"name":"  A\nB  ","url":"https://a.example.com/a"}]}""")!!
        assertEquals("A B", l.entries.single().name)
        assertTrue(W3uParser.parse("""{"name":"L","stations":[{"name":"${"x".repeat(500)}","url":"https://a.example.com/a"}]}""")!!.entries.single().name.length <= 200)
    }
}
