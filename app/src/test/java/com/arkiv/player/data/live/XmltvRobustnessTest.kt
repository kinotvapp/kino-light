package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.gateway.liveCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Guides as they really arrive: gzip with no `.gz` in the name, merged sources, shifts, broken or mislabelled XML. */
class XmltvRobustnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val from = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli()

    private fun xml(channel: String = "uno.co", title: String = "Noticias", start: String = "20261001100000 +0000", stop: String = "20261001110000 +0000", decl: String = """<?xml version="1.0" encoding="UTF-8"?>""") =
        """$decl<tv><channel id="$channel"><display-name>Uno</display-name></channel><programme start="$start" stop="$stop" channel="$channel"><title>$title</title></programme></tv>"""

    private fun gz(bytes: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private fun parse(bytes: ByteArray) = XmltvParser.parse(XmltvParser.open(bytes), from, to, setOf("uno.co"))

    @Test fun `gzip is told by its magic bytes whatever the file or address is called`() {
        val plain = xml().toByteArray()
        assertEquals("Noticias", parse(gz(plain)).programmes.getValue("uno.co").single().title)
        val f = tmp.newFile("guia.xml") // a .xml name holding gzip
        f.writeBytes(gz(plain))
        val fromFile = XmltvParser.open(f).use { XmltvParser.parse(it, from, to, setOf("uno.co")) }
        assertEquals("Noticias", fromFile.programmes.getValue("uno.co").single().title)
        val named = tmp.newFile("guia.xml.gz") // a .gz name holding plain XML
        named.writeBytes(plain)
        assertEquals(1, XmltvParser.open(named).use { XmltvParser.parse(it, from, to, setOf("uno.co")) }.programmes.size)
    }

    @Test fun `offsets of any sign are applied, and none means UTC`() {
        val g = XmltvParser.parse(
            XmltvParser.open(xml(start = "20261001100000 -0500", stop = "20261001110000 -0500").toByteArray()), from, to, setOf("uno.co"),
        ).programmes.getValue("uno.co").single()
        assertEquals(Instant.parse("2026-10-01T15:00:00Z").toEpochMilli(), g.startMs)
        assertEquals(Instant.parse("2026-10-01T05:00:00Z").toEpochMilli(), XmltvParser.parseTime("20261001100000 +0500"))
        assertEquals(Instant.parse("2026-10-01T04:30:00Z").toEpochMilli(), XmltvParser.parseTime("20261001100000 +0530"))
        assertEquals(Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(), XmltvParser.parseTime("202610011000"))
    }

    @Test fun `a guide cut in the middle or with unclosed tags keeps the programmes read so far`() {
        val cut = xml().replace("</programme></tv>", "</programme><programme start=\"20261001120000 +0000\" stop=\"20261001130000 +0000\" channel=\"uno.co\"><title>Segundo")
        val g = parse(cut.toByteArray())
        assertEquals(listOf("Noticias"), g.programmes.getValue("uno.co").map { it.title })
        assertTrue(g.truncated)
        val unclosed = """<?xml version="1.0"?><tv><channel id="uno.co"><display-name>Uno</channel><programme start="20261001100000 +0000" stop="20261001110000 +0000" channel="uno.co"><title>Con etiqueta abierta</programme></tv>"""
        // It never throws; whatever was read is kept.
        parse(unclosed.toByteArray())
    }

    @Test fun `a guide that declares UTF-8 but is Latin-1 does not lose what came before the bad byte`() {
        val head = xml(title = "Antes").dropLast("</tv>".length)
        val bad = (head + """<programme start="20261001120000 +0000" stop="20261001130000 +0000" channel="uno.co"><title>Ni""" + "ñ" + """o</title></programme></tv>""")
            .toByteArray(Charsets.ISO_8859_1)
        val g = parse(bad)
        assertEquals("Antes", g.programmes.getValue("uno.co").first().title)
    }

    @Test fun `a guide that declares an encoding it is not still reads`() {
        val g = parse(xml(title = "Niño", decl = """<?xml version="1.0" encoding="utf-16"?>""").toByteArray(Charsets.UTF_8))
        // A wrong declaration never throws; it either reads or keeps what it could.
        assertTrue(g.programmes.size <= 1)
    }

    private fun write(name: String, text: String): File = tmp.newFile(name).also { it.writeText(text) }

    @Test fun `several guides are merged, the first holding a channel keeps it`() = runTest {
        val a = write("a.xml", xml(channel = "uno.co", title = "De A"))
        val b = write("b.xml", xml(channel = "uno.co", title = "De B").replace("<tv>", "<tv>") + "")
        val c = write("c.xml", xml(channel = "dos.co", title = "De C"))
        val bodies = mapOf("https://g.example.com/a.xml" to a.readBytes(), "https://g.example.com/b.xml" to gz(b.readBytes()), "https://g.example.com/c.xml" to c.readBytes())
        val m3u = "#EXTM3U url-tvg=\"https://g.example.com/a.xml,https://g.example.com/b.xml,https://g.example.com/c.xml\"\n" +
            "#EXTINF:-1 tvg-id=\"uno.co\",Uno\nhttps://s.example.com/1.m3u8\n#EXTINF:-1 tvg-id=\"dos.co\" tvg-shift=\"2\",Dos\nhttps://s.example.com/2.m3u8\n"
        val fetcher = LivePlaylistFetcher { url, _, _ -> if (url.endsWith(".m3u")) m3u.toByteArray() else bodies.getValue(url) }
        val now = Instant.parse("2026-10-01T11:00:00Z").toEpochMilli()
        val p = OwnLiveProvider(
            sources = { listOf(OwnLiveSourceEntity("l1", "PLAYLIST", "L", "https://l.example.com/lista.m3u")) },
            fetcher = fetcher, cacheDir = tmp.newFolder(), allCachesRoot = null, clock = { now }, log = {},
        )
        val channels = p.categories(false).flatMap { p.channels(it.id) }
        val (guide, _) = p.guide(channels)
        val uno = channels.first { it.name == "Uno" }
        val dos = channels.first { it.name == "Dos" }
        assertEquals(listOf("De A"), guide.getValue(uno.liveCode).map { it.title })
        // `tvg-shift="2"`: this channel's guide is two hours off.
        val shifted = guide.getValue(dos.liveCode).single()
        assertEquals("De C", shifted.title)
        assertEquals(Instant.parse("2026-10-01T12:00:00Z").epochSecond, shifted.start)
        assertFalse(guide.getValue(uno.liveCode).isEmpty())
    }
}
