package com.arkiv.player.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The "dirty JSON" real Wiseplay lists are: BOM, trailing commas, comments, HTML around them. */
class LenientJsonTest {
    private fun parse(text: String) = LenientJson.parse(text)

    private fun refused(text: String, maxDepth: Int = LenientJson.MAX_DEPTH, maxNodes: Int = LenientJson.MAX_NODES): String {
        try {
            LenientJson.parse(text, maxDepth, maxNodes)
        } catch (e: LenientJson.Invalid) {
            return e.message.orEmpty()
        }
        fail("accepted: ${text.take(60)}")
        return ""
    }

    @Test fun `plain JSON reads as maps, lists, strings, numbers, booleans and null`() {
        val v = parse("""{"a":"x","b":[1,2.5,true,false,null],"c":{"d":"\u00e1\n\"q\""}}""") as Map<*, *>
        assertEquals("x", v["a"])
        assertEquals(listOf(1L, 2.5, true, false, null), v["b"])
        assertEquals("á\n\"q\"", (v["c"] as Map<*, *>)["d"])
    }

    @Test fun `a BOM, trailing commas and comments are tolerated`() {
        val v = parse("\uFEFF{\n // lista\n \"groups\": [ {\"name\":\"A\",}, ], /* fin */ }") as Map<*, *>
        assertEquals(listOf(mapOf("name" to "A")), v["groups"])
    }

    @Test fun `HTML around the JSON (a blog or paste page) is skipped`() {
        val v = parse("<html><body><pre>{\"name\":\"Lista\"}</pre></body></html>") as Map<*, *>
        assertEquals("Lista", v["name"])
    }

    @Test fun `single-quoted strings and raw control characters inside strings are read`() {
        val v = parse("{'name':'Mi\tlista'}") as Map<*, *>
        assertEquals("Mi\tlista", v["name"])
    }

    @Test fun `a duplicate key keeps the last value instead of failing`() {
        assertEquals("b", (parse("""{"k":"a","k":"b"}""") as Map<*, *>)["k"])
    }

    @Test fun `deep nesting is refused before it can overflow the stack`() {
        assertTrue(refused("[".repeat(100_000)).contains("deep"))
        assertTrue(refused("{\"a\":".repeat(40) + "1" + "}".repeat(40)).contains("deep"))
    }

    @Test fun `too many values are refused`() {
        assertTrue(refused("[" + (1..50).joinToString(",") + "]", maxNodes = 10).contains("values"))
    }

    @Test fun `garbage is refused, never a crash`() {
        listOf("", "hola", "{", "{\"a\":}", "[1,,2]", "{\"a\" 1}", "\"\\u12\"", "nul").forEach { refused(it) }
    }

    @Test fun `a number too large for a long is a double, not a crash`() {
        assertEquals(1e30, (parse("[1000000000000000000000000000000]") as List<*>)[0])
    }

    @Test fun `no JSON start at all gives null`() {
        assertNull(LenientJson.start("#EXTM3U\n"))
        assertEquals(3, LenientJson.start("<p>{\"a\":1}"))
    }
}
