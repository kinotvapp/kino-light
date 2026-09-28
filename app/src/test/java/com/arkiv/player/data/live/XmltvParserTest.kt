package com.arkiv.player.data.live

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvParserTest {
    private val dir = File("../docs/plugins/fixtures/live")
    private val from = Instant.parse("2026-09-27T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()

    private fun parseFile(name: String, wanted: Set<String>? = setOf("canal1.co", "dep"), names: Set<String> = emptySet()) =
        XmltvParser.parse(XmltvParser.open(File(dir, name).readBytes()), from, to, wanted, names)

    private fun expected(): XmltvGuide {
        val o = JSONObject(File(dir, "guide.expected.json").readText())
        val names = o.getJSONObject("displayNames").let { n -> n.keys().asSequence().associateWith { k -> n.getJSONArray(k).let { a -> (0 until a.length()).map(a::getString) } } }
        val progs = o.getJSONObject("programmes").let { p ->
            p.keys().asSequence().associateWith { k ->
                p.getJSONArray(k).let { a ->
                    (0 until a.length()).map { i ->
                        val e = a.getJSONObject(i)
                        XmltvProgramme(k, e.getString("title"), Instant.parse(e.getString("start")).toEpochMilli(), Instant.parse(e.getString("end")).toEpochMilli(), e.getString("description"))
                    }
                }
            }
        }
        return XmltvGuide(names, progs, o.getBoolean("truncated"))
    }

    @Test fun `plain and gzip guides give the expected programmes`() {
        assertEquals(expected(), parseFile("guide.xml"))
        assertEquals(expected(), parseFile("guide.xml.gz"))
    }

    @Test fun `a channel can be wanted by its display name`() {
        val g = parseFile("guide.xml", wanted = emptySet(), names = setOf(XmltvParser.normaliseName("CANAL UNO")))
        assertEquals(setOf("canal1.co"), g.programmes.keys)
    }

    @Test fun `a Latin-1 guide reads its accents`() {
        val g = parseFile("guide-latin1.xml", wanted = null)
        assertEquals("Niñez", g.programmes.getValue("n").single().title)
        assertEquals(listOf("Niños"), g.displayNames.getValue("n"))
    }

    @Test fun `a guide declaring entities is refused, never expanded`() {
        val g = parseFile("guide-xxe.xml", wanted = null)
        assertEquals(emptyMap<String, Any>(), g.programmes)
    }

    @Test fun `times with and without offsets`() {
        assertEquals(Instant.parse("2026-09-27T17:00:00Z").toEpochMilli(), XmltvParser.parseTime("20260927120000 -0500"))
        assertEquals(Instant.parse("2026-09-27T12:00:00Z").toEpochMilli(), XmltvParser.parseTime("20260927120000"))
        assertEquals(Instant.parse("2026-09-27T12:30:00Z").toEpochMilli(), XmltvParser.parseTime("202609271230 +0000"))
        assertEquals(null, XmltvParser.parseTime("basura"))
    }

    @Test fun `a gzip bomb stops at the cap and keeps what it read`() {
        val body = buildString {
            append("<tv>")
            repeat(200_000) { append("<programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"c$it\"><title>P</title></programme>") }
            append("</tv>")
        }.toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(body) } }.toByteArray()
        val g = XmltvParser.parse(XmltvParser.open(gz), from, to, wantedIds = null, maxBytes = 1_000_000)
        assertTrue(g.truncated)
        assertTrue(g.programmes.size in 1 until 200_000)
    }

    @Test fun `the per-channel cap and a broken document never crash`() {
        val many = "<tv>" + (0 until 150).joinToString("") { "<programme start=\"20260927${"%02d".format(it / 60)}${"%02d".format(it % 60)}00 +0000\" stop=\"20260927${"%02d".format(it / 60)}${"%02d".format(it % 60)}59 +0000\" channel=\"c\"><title>P$it</title></programme>" } + "</tv>"
        assertEquals(100, XmltvParser.parse(ByteArrayInputStream(many.toByteArray()), from, to, maxPerChannel = 100).programmes.getValue("c").size)
        val broken = XmltvParser.parse(ByteArrayInputStream("<tv><programme start=\"20260927120000\" stop=\"20260927130000\" channel=\"c\"><title>Uno</title></programme><programme".toByteArray()), from, to)
        assertEquals(listOf("Uno"), broken.programmes.getValue("c").map { it.title })
    }
}
