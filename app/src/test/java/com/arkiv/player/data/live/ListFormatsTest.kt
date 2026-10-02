package com.arkiv.player.data.live

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Auto-detection by content, XSPF and plain lists, gzip by magic bytes, and the iptv-org catalog. */
class ListFormatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val xspf = """<?xml version="1.0" encoding="UTF-8"?>
        <playlist version="1" xmlns="http://xspf.org/ns/0/" xmlns:vlc="http://www.videolan.org/vlc/playlist/ns/0/">
          <title>Mi lista</title>
          <trackList>
            <track>
              <location>http://tv.example.com/uno.m3u8?a=1&amp;b=2</location>
              <title>Canal &quot;Uno&quot; &amp; Co</title>
              <image>https://img.example.com/uno.png</image>
              <album>Noticias</album>
              <extension application="http://www.videolan.org/vlc/playlist/0">
                <vlc:option>http-user-agent=VLC/3.0</vlc:option>
                <vlc:option>http-referrer=https://tv.example.com/</vlc:option>
              </extension>
            </track>
            <track><location>https://tv.example.com/dos.m3u8</location><creator><![CDATA[Dos <HD>]]></creator></track>
            <track><location>file:///home/x/video.mp4</location><title>Local</title></track>
            <track><title>Sin dirección</title></track>
            <track><location>rtmp://tv.example.com/x</location><title>Rtmp</title></track>
          </trackList>
        </playlist>"""

    @Test fun `every format is told by its content, never by a name`() {
        assertEquals(ListFormat.M3U, ListFormats.detect("#EXTM3U\n#EXTINF:-1,Uno\nhttp://a.example.com/1.m3u8"))
        assertEquals(ListFormat.M3U, ListFormats.detect("﻿  #EXTM3U\n"))
        assertEquals(ListFormat.M3U, ListFormats.detect("#EXTINF:-1,Sin cabecera\nhttp://a.example.com/1.m3u8"))
        assertEquals(ListFormat.W3U, ListFormats.detect("""{"name":"L","groups":[]}"""))
        assertEquals(ListFormat.W3U, ListFormats.detect("""[{"name":"x"}]"""))
        assertEquals(ListFormat.XTREAM_JSON, ListFormats.detect("""{"user_info":{"auth":1},"server_info":{}}"""))
        assertEquals(ListFormat.XSPF, ListFormats.detect(xspf))
        assertEquals(ListFormat.XMLTV, ListFormats.detect("""<?xml version="1.0"?><tv><channel id="a"></channel></tv>"""))
        assertEquals(ListFormat.WEB_PAGE, ListFormats.detect("<!DOCTYPE html><html><body>hola</body></html>"))
        assertEquals(ListFormat.WEB_PAGE, ListFormats.detect("""<?xml version="1.0"?><rss></rss>"""))
        assertEquals(ListFormat.PLAIN, ListFormats.detect("http://a.example.com/1.m3u8\nhttps://b.example.com/2.m3u8\n"))
        assertEquals(ListFormat.PLAIN, ListFormats.detect("# mis canales\nUno, http://a.example.com/1.m3u8\n\nDos;https://b.example.com/2.ts"))
        assertEquals(ListFormat.UNKNOWN, ListFormats.detect(""))
        assertEquals(ListFormat.UNKNOWN, ListFormats.detect("hola mundo\nesto no es una lista"))
        // One line that is not an address spoils a plain list: it is not guessed at.
        assertEquals(ListFormat.UNKNOWN, ListFormats.detect("http://a.example.com/1.m3u8\nnada que ver"))
    }

    @Test fun `an XSPF playlist gives its playable tracks with names, logos, groups and VLC headers`() {
        val e = ListFormats.xspf(xspf)
        assertEquals(listOf("Canal \"Uno\" & Co", "Dos <HD>"), e.map { it.name })
        assertEquals("http://tv.example.com/uno.m3u8?a=1&b=2", e[0].url)
        assertEquals("https://img.example.com/uno.png", e[0].logo)
        assertEquals("Noticias", e[0].group)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://tv.example.com/"), e[0].headers)
        assertTrue(e[1].headers.isEmpty())
    }

    @Test fun `XSPF is read without expanding anything a document declares`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "AAAA"><!ENTITY b "&a;&a;&a;&a;">]>
            <playlist><trackList><track><location>http://x.example.com/&b;.m3u8</location><title>&b;&#x41;&#0;&#1114112;</title></track></trackList></playlist>"""
        val e = ListFormats.xspf(hostile)
        assertEquals(1, e.size)
        assertFalse(e[0].name.contains("AAAA"))
        assertEquals("&b;A", e[0].name)
        // Broken XML (a track never closed) loses only that track.
        assertEquals(1, ListFormats.xspf("<playlist><trackList><track><location>http://a.example.com/1</location><title>Uno</title></track><track><location>http://b.example.com/2").size)
    }

    @Test fun `a plain list gives one entry per address, named after the line or the server`() {
        val e = ListFormats.plain("# comentario\nhttp://a.example.com/canales/uno.m3u8\nDos, https://b.example.com/2.ts\n\nhttp://c.example.com|User-Agent=X\nbasura")
        assertEquals(listOf("a.example.com · uno", "Dos", "c.example.com"), e.map { it.name })
        assertEquals("http://c.example.com|User-Agent=X", e[2].url)
    }

    @Test fun `converted lists are plain M3U that the usual parser reads to the same entries`() {
        val m3u = ListFormats.toM3u(xspf)!!
        val back = M3uParser.parse(m3u).entries
        assertEquals(ListFormats.xspf(xspf), back.map { it.copy(tvgName = "") })
        assertEquals("Uno", M3uParser.parse(ListFormats.toM3u("Uno,http://a.example.com/1.m3u8")!!).entries.single().name)
        assertNull(ListFormats.toM3u("#EXTM3U\n"))
        assertNull(ListFormats.toM3u("""{"groups":[]}"""))
    }

    private fun gz(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun fetcherOf(bytes: ByteArray) = ListFormatFetcher(LivePlaylistFetcher { _, _, _ -> bytes })

    @Test fun `the download path converts XSPF and plain lists, inflates gzip by its magic bytes, and leaves M3U and JSON alone`() = runTest {
        val m3u = "#EXTM3U\n#EXTINF:-1,Uno\nhttp://a.example.com/1.m3u8\n"
        val cases = listOf(
            xspf.toByteArray() to { f: File -> assertEquals(2, M3uParser.parse(f).entries.size) },
            "http://a.example.com/1.m3u8\nhttp://b.example.com/2.m3u8".toByteArray() to { f: File -> assertEquals(2, M3uParser.parse(f).entries.size) },
            gz(m3u) to { f: File -> assertEquals(m3u, f.readText()) },
            gz(xspf) to { f: File -> assertEquals(2, M3uParser.parse(f).entries.size) },
            m3u.toByteArray() to { f: File -> assertEquals(m3u, f.readText()) },
            """{"name":"x","groups":[]}""".toByteArray() to { f: File -> assertEquals("""{"name":"x","groups":[]}""", f.readText()) },
        )
        // The name says nothing: these all go through a file with no extension.
        cases.forEachIndexed { i, (bytes, check) ->
            val f = File(tmp.root, "d$i")
            fetcherOf(bytes).fetchTo("https://x.example.com/guia.xml", emptyMap(), 1L shl 20, f)
            check(f)
        }
    }

    @Test fun `gzip over the cap, or corrupt, fails the download and keeps no file`() = runTest {
        val big = gz("#EXTM3U\n" + "#EXTINF:-1,A\nhttp://a.example.com/1\n".repeat(1000))
        val f = File(tmp.root, "big")
        var failed = false
        try { fetcherOf(big).fetchTo("https://x.example.com/l", emptyMap(), 1024, f) } catch (e: PlaylistTooLargeException) { failed = true }
        assertTrue(failed && !f.exists())
        val bad = byteArrayOf(0x1F, 0x8B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val g = File(tmp.root, "bad")
        failed = false
        try { fetcherOf(bad).fetchTo("https://x.example.com/l", emptyMap(), 1 shl 20, g) } catch (e: java.io.IOException) { failed = true }
        assertTrue(failed && !g.exists())
    }

    @Test fun `a pasted XSPF or plain list is accepted and counted, one that names nothing playable is refused`() {
        val x = OwnPastedList.check(xspf) as OwnPastedCheck.Ok
        assertEquals(OwnPastedFormat.XSPF, x.format)
        assertEquals("Lista XSPF: encontré 2 canales", x.summary)
        val p = OwnPastedList.check("Uno,http://a.example.com/1.m3u8\nhttp://192.168.1.2/x.m3u8") as OwnPastedCheck.Ok
        assertEquals(OwnPastedFormat.PLAIN, p.format)
        assertEquals("Lista de direcciones: encontré 1 canal", p.summary)
        assertTrue(OwnPastedList.check("http://192.168.1.2/x.m3u8\nhttp://10.0.0.1/y") is OwnPastedCheck.Refused)
        assertTrue((OwnPastedList.check("""<?xml version="1.0"?><tv><programme channel="a"></programme></tv>""") as OwnPastedCheck.Refused).message.contains("XMLTV"))
        assertTrue((OwnPastedList.check("<html><body>x</body></html>") as OwnPastedCheck.Refused).message.contains("página web"))
        assertTrue((OwnPastedList.check("hola mundo") as OwnPastedCheck.Refused).message.contains("No parece una lista"))
    }

    @Test fun `an opened file may be gzip, XSPF or text, whatever it is called`() {
        assertTrue(OwnPastedList.checkFile("lista.xspf", xspf.toByteArray(), false) is OwnPastedCheck.Ok)
        assertTrue(OwnPastedList.checkFile("lista.txt", "http://a.example.com/1.m3u8".toByteArray(), false) is OwnPastedCheck.Ok)
        assertTrue(OwnPastedList.checkFile("lista.m3u.gz", gz("#EXTM3U\n#EXTINF:-1,Uno\nhttp://a.example.com/1.m3u8\n"), false) is OwnPastedCheck.Ok)
        assertTrue(OwnPastedList.checkFile("lista.gz", "no es gzip".toByteArray(), false) is OwnPastedCheck.Refused)
        assertTrue(OwnPastedList.checkFile("foto.png", ByteArray(4), false) is OwnPastedCheck.Refused)
    }

    @Test fun `Probar names the kind of list it found`() {
        assertEquals("Lista XSPF: encontré 2 canales", (OwnSourceProbe.classify(OwnKind.PLAYLIST, xspf.toByteArray()) as OwnProbe.Ok).message)
        assertEquals("Lista de direcciones: encontré 2 canales", (OwnSourceProbe.classify(OwnKind.PLAYLIST, "http://a.example.com/1\nhttp://b.example.com/2".toByteArray()) as OwnProbe.Ok).message)
        assertTrue(OwnSourceProbe.classify(OwnKind.PLAYLIST, """<tv><channel id="a"/></tv>""".toByteArray()) is OwnProbe.Failed)
    }

    @Test fun `the iptv-org picks are ordinary lists on a public host that pass the same form rules`() {
        val all = IptvOrgKind.entries.flatMap { IptvOrgCatalog.of(it) }
        assertTrue(all.size > 40)
        assertEquals(all.size, all.map { it.url }.toSet().size)
        assertEquals("https://iptv-org.github.io/iptv/countries/co.m3u", IptvOrgCatalog.countries.first { it.code == "co" }.url)
        assertEquals("https://iptv-org.github.io/iptv/languages/spa.m3u", IptvOrgCatalog.languages.first { it.code == "spa" }.url)
        assertEquals("https://iptv-org.github.io/iptv/categories/news.m3u", IptvOrgCatalog.categories.first { it.code == "news" }.url)
        assertFalse(IptvOrgCatalog.categories.any { it.code == "xxx" })
        all.forEach { item ->
            val r = IptvOrgCatalog.form(item).validate("id", emptyList())
            assertTrue(item.url, r is OwnFormResult.Valid)
            assertFalse((r as OwnFormResult.Valid).cleartext)
            assertEquals("iptv-org · ${item.title}", r.source.name)
        }
        // Each pick is its own list (its own cache key), so two picks never overwrite each other.
        assertEquals(all.size, all.map { PlaylistSource.cacheKey(it.url) }.toSet().size)
    }
}
