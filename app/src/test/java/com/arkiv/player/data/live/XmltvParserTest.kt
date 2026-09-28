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

    // The window and wantedIds live in guide.expected.json's "query", not as a second, hand-kept
    // copy here: the fixture is the one source of truth the kit (Task 14) reads too.
    private val guideQuery = JSONObject(File(dir, "guide.expected.json").readText()).getJSONObject("query")
    private val from = Instant.parse(guideQuery.getString("from")).toEpochMilli()
    private val to = Instant.parse(guideQuery.getString("to")).toEpochMilli()
    private val defaultWanted = guideQuery.getJSONArray("wantedIds").let { a -> (0 until a.length()).map(a::getString).toSet() }

    private fun parseFile(name: String, wanted: Set<String>? = defaultWanted, names: Set<String> = emptySet()) =
        XmltvParser.parse(XmltvParser.open(File(dir, name).readBytes()), from, to, wanted, names)

    private fun expectedFrom(jsonName: String): XmltvGuide {
        val o = JSONObject(File(dir, jsonName).readText())
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
        assertEquals(expectedFrom("guide.expected.json"), parseFile("guide.xml"))
        assertEquals(expectedFrom("guide.expected.json"), parseFile("guide.xml.gz"))
    }

    @Test fun `a channel can be wanted by its display name`() {
        val g = parseFile("guide.xml", wanted = emptySet(), names = setOf(XmltvParser.normaliseName("CANAL UNO")))
        assertEquals(setOf("canal1.co"), g.programmes.keys)
    }

    @Test fun `a Latin-1 guide reads its accents`() {
        assertEquals(expectedFrom("guide-latin1.expected.json"), parseFile("guide-latin1.xml", wanted = null))
    }

    @Test fun `a guide declaring entities is refused, never expanded`() {
        assertEquals(expectedFrom("guide-xxe.expected.json"), parseFile("guide-xxe.xml", wanted = null))
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

    // --- Fix round 1 ---------------------------------------------------------------------

    @Test fun `an entity declared past the 8 KB head scan is still refused`() {
        // An internal entity (no SYSTEM id): the pre-existing external-entity feature flags don't
        // touch this, so only the doctype-wide refusal stops it from expanding into the title.
        val padding = "<!-- ${"x".repeat(8300)} -->"
        val xml = "<?xml version=\"1.0\"?>$padding<!DOCTYPE tv [<!ENTITY secret \"LEAKED\">]><tv><programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"x\"><title>&secret;</title></programme></tv>"
        val g = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), from, to, wantedIds = null)
        assertEquals(emptyMap<String, Any>(), g.programmes)
    }

    @Test fun `a UTF-16 document with a BOM is refused, never expanded`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE tv [<!ENTITY secret \"LEAKED\">]><tv><programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"x\"><title>&secret;</title></programme></tv>"
        val bytes = xml.toByteArray(Charsets.UTF_16)
        val g = XmltvParser.parse(XmltvParser.open(bytes), from, to, wantedIds = null)
        assertEquals(emptyMap<String, Any>(), g.programmes)
    }

    @Test fun `a DOCTYPE with an external SYSTEM id is refused, never fetched`() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE tv SYSTEM \"http://192.0.2.1/tv.dtd\"><tv><programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"x\"><title>Hi</title></programme></tv>"
        val started = System.nanoTime()
        val g = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), from, to, wantedIds = null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(emptyMap<String, Any>(), g.programmes)
        assertTrue("took ${elapsedMs}ms, looks like it tried to fetch", elapsedMs < 5000)
    }

    @Test fun `a parameter entity declaring guide is refused too`() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE tv [<!ENTITY % p SYSTEM \"http://192.0.2.1/x.dtd\"> %p; ]><tv><programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"x\"><title>Hi</title></programme></tv>"
        val g = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), from, to, wantedIds = null)
        assertEquals(emptyMap<String, Any>(), g.programmes)
    }

    @Test fun `a gzip guide truncated mid-stream is reported as truncated, never thrown`() {
        val full = File(dir, "guide.xml.gz").readBytes()
        val half = full.copyOf(full.size / 2)
        val g = XmltvParser.parse(XmltvParser.open(half), from, to, wantedIds = null)
        assertTrue(g.truncated)
    }

    @Test fun `a corrupt gzip header never throws, from open or from parse`() {
        val corrupt = File(dir, "guide.xml.gz").readBytes()
        corrupt[2] = 1.toByte() // a bogus compression method byte
        val stream = XmltvParser.open(corrupt) // must not throw
        val g = XmltvParser.parse(stream, from, to, wantedIds = null)
        assertTrue(g.programmes.isEmpty())
    }

    @Test fun `the deadline is checked between channels too, not just programmes`() {
        val doc = "<tv>" + (0 until 3000).joinToString("") { i -> "<channel id=\"c$i\"><display-name>Canal $i</display-name></channel>" } + "</tv>"
        var checks = 0
        val g = XmltvParser.parse(ByteArrayInputStream(doc.toByteArray()), from, to, wantedIds = null, deadline = { ++checks >= 1 })
        assertTrue(g.truncated)
        assertTrue(g.displayNames.size < 3000)
    }

    @Test fun `the per-channel cap keeps the earliest programmes even out of document order`() {
        val doc = "<tv>" +
            "<programme start=\"20260927140000 +0000\" stop=\"20260927140100 +0000\" channel=\"c\"><title>P4</title></programme>" +
            "<programme start=\"20260927130000 +0000\" stop=\"20260927130100 +0000\" channel=\"c\"><title>P3</title></programme>" +
            "<programme start=\"20260927120000 +0000\" stop=\"20260927120100 +0000\" channel=\"c\"><title>P2</title></programme>" +
            "<programme start=\"20260927110000 +0000\" stop=\"20260927110100 +0000\" channel=\"c\"><title>P1</title></programme>" +
            "<programme start=\"20260927100000 +0000\" stop=\"20260927100100 +0000\" channel=\"c\"><title>P0</title></programme>" +
            "</tv>"
        val g = XmltvParser.parse(ByteArrayInputStream(doc.toByteArray()), from, to, wantedIds = null, maxPerChannel = 3)
        assertEquals(listOf("P0", "P1", "P2"), g.programmes.getValue("c").map { it.title })
    }

    @Test fun `distinct channel ids are capped at 5000 when everything is wanted`() {
        val doc = "<tv>" + (0 until 5005).joinToString("") { i -> "<programme start=\"20260927120000 +0000\" stop=\"20260927130000 +0000\" channel=\"c$i\"><title>P</title></programme>" } + "</tv>"
        val g = XmltvParser.parse(ByteArrayInputStream(doc.toByteArray()), from, to, wantedIds = null)
        assertEquals(5000, g.programmes.size)
    }
}
