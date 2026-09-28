package com.arkiv.player.data.live

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A stand-in for org.json's JSONObject/JSONArray.similar(), which android.jar's compile-time stub
// (used on this project's unit test classpath) doesn't declare. See PluginWebGlobalsTest.kt.
private fun jsonSimilar(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> {
        val aKeys = a.keys().asSequence().toSet()
        aKeys == b.keys().asSequence().toSet() && aKeys.all { jsonSimilar(a.get(it), b.get(it)) }
    }
    a is JSONArray && b is JSONArray ->
        a.length() == b.length() && (0 until a.length()).all { jsonSimilar(a.get(it), b.get(it)) }
    else -> a == b
}

/** Reads docs/plugins/fixtures/live -- the SAME files the kit's node tests read (Task 14). */
class M3uParserTest {
    private val dir = File("../docs/plugins/fixtures/live")

    private fun json(r: M3uResult): JSONObject = JSONObject()
        .put("total", r.total).put("skipped", r.skipped)
        .put("entries", org.json.JSONArray(r.entries.map { e ->
            JSONObject().put("name", e.name).put("url", e.url).put("tvgId", e.tvgId).put("tvgName", e.tvgName)
                .put("logo", e.logo).put("number", e.number).put("group", e.group).put("language", e.language)
                .put("country", e.country).put("headers", JSONObject(e.headers as Map<*, *>))
        }))

    private fun check(name: String) {
        val parsed = M3uParser.parse(M3uParser.decode(File(dir, "$name.m3u").readBytes()))
        val expected = JSONObject(File(dir, "$name.expected.json").readText())
        assertTrue("$name:\n${json(parsed).toString(1)}", jsonSimilar(expected, json(parsed)))
    }

    @Test fun `a plain list with quoted commas and EXTGRP`() = check("basic")
    @Test fun `a BOM and CRLF line ends`() = check("bom-crlf")
    @Test fun `a Latin-1 file reads its accents`() = check("latin1")
    @Test fun `broken entries are skipped and counted, the rest survive`() = check("broken")
    @Test fun `VLC, Kodi and pipe headers`() = check("headers")
    @Test fun `an unterminated quote swallows the title, never the next entry`() = check("unterminated-quote")
    @Test fun `one huge line with no line breaks is a single skipped entry`() = check("huge-line")

    @Test fun `a huge list keeps the cap and counts every valid entry`() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(6000) { append("#EXTINF:-1 group-title=\"G${it % 7}\",Canal $it\nhttps://live.example.com/$it.m3u8\n") }
        }
        val r = M3uParser.parse(text, maxEntries = 5000)
        assertEquals(5000, r.entries.size)
        assertEquals(6000, r.total)
        assertEquals("Canal 4999", r.entries.last().name)
    }

    @Test fun `the time budget stops the parse and says so`() {
        val text = "#EXTM3U\n" + (1..5000).joinToString("") { "#EXTINF:-1,C$it\nhttps://x.example.com/$it\n" }
        var checks = 0
        val r = M3uParser.parse(text, deadline = { ++checks >= 2 })
        assertTrue(r.stoppedEarly)
        assertTrue(r.entries.size < 5000)
    }

    @Test fun `empty or garbage input is an empty result, never a crash`() {
        assertEquals(M3uResult(emptyList(), 0, 0), M3uParser.parse(""))
        assertEquals(0, M3uParser.parse(M3uParser.decode(ByteArray(64) { it.toByte() })).total)
    }

    @Test fun `a file parses exactly like its decoded text, without loading it whole`() {
        listOf("basic", "bom-crlf", "latin1", "broken", "headers", "unterminated-quote", "huge-line").forEach { name ->
            val f = File(dir, "$name.m3u")
            assertEquals(name, M3uParser.parse(M3uParser.decode(f.readBytes())), M3uParser.parse(f))
        }
    }

    @Test fun `hidden and refused entries are counted apart and never spend the cap`() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(10) { append("#EXTINF:-1 group-title=\"XXX\",A$it\nhttps://live.example.com/a$it.m3u8\n") }
            repeat(4) { append("#EXTINF:-1,E$it\nhttps://evil.example.org/$it.m3u8\n") }
            repeat(5) { append("#EXTINF:-1,C$it\nhttps://live.example.com/$it.m3u8\n") }
        }
        val r = M3uParser.parse(text, maxEntries = 3, hide = { it.group == "XXX" }, allow = { "evil" !in it })
        assertEquals(listOf("C0", "C1", "C2"), r.entries.map { it.name })
        assertEquals(5, r.total)
        assertEquals(10, r.hidden)
        assertEquals(4, r.refused)
    }
}
