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
    @Test fun `EXTHTTP JSON headers, the pipe winning over them, and no control characters in any header`() = check("exthttp")

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
        listOf("basic", "bom-crlf", "latin1", "broken", "headers", "unterminated-quote", "huge-line", "exthttp").forEach { name ->
            val f = File(dir, "$name.m3u")
            assertEquals(name, M3uParser.parse(M3uParser.decode(f.readBytes())), M3uParser.parse(f))
        }
    }

    @Test fun `a KODIPROP ClearKey license is captured as the entry's DRM key`() {
        val text = "#EXTM3U\n" +
            "#EXTINF:-1,Canal protegido\n" +
            "#KODIPROP:inputstream.adaptive.license_type=clearkey\n" +
            "#KODIPROP:inputstream.adaptive.license_key=0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210\n" +
            "https://live.example.com/protegido.m3u8\n"
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("0123456789abcdef0123456789abcdef", entry.drmKeyId)
        assertEquals("fedcba9876543210fedcba9876543210", entry.drmKey)
    }

    @Test fun `a KODIPROP license of an unsupported type is ignored, not half-kept`() {
        val text = "#EXTM3U\n" +
            "#EXTINF:-1,Canal widevine\n" +
            "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\n" +
            "#KODIPROP:inputstream.adaptive.license_key=https://license.example.com/acquire\n" +
            "https://live.example.com/widevine.m3u8\n"
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("", entry.drmKeyId)
        assertEquals("", entry.drmKey)
    }

    @Test fun `a plain entry with no KODIPROP DRM has empty DRM fields, same as before this feature`() {
        val text = "#EXTM3U\n#EXTINF:-1,Canal libre\nhttps://live.example.com/libre.m3u8\n"
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("", entry.drmKeyId)
        assertEquals("", entry.drmKey)
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

    @Test fun `the header's url-tvg and x-tvg-url give the list's guides, http(s) only, deduplicated and capped`() {
        val text = "#EXTM3U url-tvg=\"https://epg.example.com/a.xml.gz, https://epg.example.com/b.xml,file:///sdcard/x.xml\" " +
            "x-tvg-url=\"https://epg.example.com/b.xml,http://epg.example.org/c.xml,https://epg.example.org/d.xml\"\n" +
            "#EXTINF:-1,Canal\nhttps://live.example.com/1.m3u8\n"
        assertEquals(
            listOf("https://epg.example.com/a.xml.gz", "https://epg.example.com/b.xml", "http://epg.example.org/c.xml"),
            M3uParser.parse(text).epgUrls,
        )
    }

    @Test fun `a list with no header guide has none, and a header later in the file is ignored`() {
        assertEquals(emptyList<String>(), M3uParser.parse("#EXTM3U\n#EXTINF:-1,A\nhttps://x.example.com/a\n").epgUrls)
        val late = "#EXTM3U\n#EXTINF:-1,A\nhttps://x.example.com/a\n#EXTM3U url-tvg=\"https://epg.example.com/late.xml\"\n"
        assertEquals(emptyList<String>(), M3uParser.parse(late).epgUrls)
    }

    @Test fun `the file parse reads the header guides too`() {
        val f = File.createTempFile("tvg", ".m3u").apply { deleteOnExit() }
        f.writeText("\uFEFF#EXTM3U x-tvg-url=\"https://epg.example.com/g.xml\"\n#EXTINF:-1,A\nhttps://x.example.com/a\n")
        assertEquals(listOf("https://epg.example.com/g.xml"), M3uParser.parse(f).epgUrls)
    }

    @Test fun `org w3 clearkey is ClearKey too`() {
        val text = "#EXTM3U\n#EXTINF:-1,C\n" +
            "#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey\n" +
            "#KODIPROP:inputstream.adaptive.license_key=0123456789ABCDEF0123456789abcdef:fedcba9876543210fedcba9876543210\n" +
            "https://live.example.com/p.mpd\n"
        val e = M3uParser.parse(text).entries.single()
        assertEquals("0123456789abcdef0123456789abcdef", e.drmKeyId)
        assertEquals("fedcba9876543210fedcba9876543210", e.drmKey)
    }

    @Test fun `a ClearKey license in its JSON form (base64url keys) becomes the hex pair`() {
        // kid 0123456789abcdef0123456789abcdef, k fedcba9876543210fedcba9876543210, base64url without padding.
        val text = "#EXTM3U\n#EXTINF:-1,C\n" +
            "#KODIPROP:inputstream.adaptive.license_type=clearkey\n" +
            "#KODIPROP:inputstream.adaptive.license_key={\"keys\":[{\"kty\":\"oct\",\"kid\":\"ASNFZ4mrze8BI0VniavN7w\",\"k\":\"_ty6mHZUMhD-3LqYdlQyEA\"}],\"type\":\"temporary\"}\n" +
            "https://live.example.com/p.mpd\n"
        val e = M3uParser.parse(text).entries.single()
        assertEquals("0123456789abcdef0123456789abcdef", e.drmKeyId)
        assertEquals("fedcba9876543210fedcba9876543210", e.drmKey)
    }

    @Test fun `a ClearKey pair that is not 16-byte hex is dropped, the channel kept`() {
        listOf("zz:yy", "0123:4567", "{\"keys\":[{\"kid\":\"bad\",\"k\":\"bad\"}]}", "{\"keys\":").forEach { key ->
            val text = "#EXTM3U\n#EXTINF:-1,C\n#KODIPROP:inputstream.adaptive.license_type=clearkey\n" +
                "#KODIPROP:inputstream.adaptive.license_key=$key\nhttps://live.example.com/p.mpd\n"
            val e = M3uParser.parse(text).entries.single()
            assertEquals(key, "", e.drmKeyId)
            assertEquals(key, "", e.drmKey)
        }
    }
}
