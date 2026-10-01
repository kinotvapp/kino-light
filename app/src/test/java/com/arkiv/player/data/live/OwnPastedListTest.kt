package com.arkiv.player.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnPastedListTest {
    private val m3u = "﻿#EXTM3U\r\n#EXTINF:-1 group-title=\"Noticias\",Uno\r\nhttps://tv.example.com/uno.m3u8\r\n" +
        "#EXTINF:-1,Casa\r\nhttp://192.168.1.5/casa.m3u8\r\n"
    private val w3u = """{"name":"Mi lista","groups":[{"name":"Deportes","stations":[{"name":"Uno","url":"https://tv.example.com/uno.m3u8"}]},
        {"name":"Más","url":"https://lists.example.com/mas.w3u"},{"name":"Casa","url":"http://10.0.0.2/x.w3u"}]}"""

    private fun ok(c: OwnPastedCheck) = c as OwnPastedCheck.Ok
    private fun refused(c: OwnPastedCheck) = (c as OwnPastedCheck.Refused).message

    @Test fun `pasted M3U is detected by its content, normalised and counts only public channels`() {
        val r = ok(OwnPastedList.check(m3u))
        assertEquals(OwnPastedFormat.M3U, r.format)
        assertTrue(r.text.startsWith("#EXTM3U\n"))
        assertTrue('\r' !in r.text)
        assertEquals("Encontré 1 canal", r.summary)
        assertEquals(OwnPastedList.digest(r.text), r.digest)
    }

    @Test fun `pasted W3U JSON is detected, links to private hosts are not counted`() {
        val r = ok(OwnPastedList.check("  \n$w3u\n"))
        assertEquals(OwnPastedFormat.W3U, r.format)
        assertEquals("Lista Wiseplay (W3U): encontré 1 canal y 1 lista enlazada, que se carga al guardar", r.summary)
    }

    @Test fun `empty, web pages, foreign JSON and lists without channels are refused in Spanish`() {
        assertEquals(OwnPastedList.EMPTY, refused(OwnPastedList.check("  \n ")))
        assertTrue(refused(OwnPastedList.check("<!DOCTYPE html><html></html>")).contains("página web"))
        assertTrue(refused(OwnPastedList.check("""{"hola":1}""")).contains("W3U"))
        assertTrue(refused(OwnPastedList.check("#EXTM3U\n#EXTINF:-1,Casa\nhttp://192.168.1.5/a.m3u8\n")).contains("no encontré canales"))
        assertTrue(refused(OwnPastedList.check("""{"stations":[{"name":"x","url":"rtmp://a.example.com/x"}]}""")).contains("no tiene canales"))
    }

    @Test fun `more than 2 MB after normalisation is refused, suggesting a URL`() {
        val line = "#EXTINF:-1,Canal\nhttps://tv.example.com/a.m3u8\n"
        val big = "#EXTM3U\n" + line.repeat(OwnPastedList.MAX_BYTES / line.length + 10)
        val msg = refused(OwnPastedList.check(big))
        assertEquals(OwnPastedList.TOO_LARGE, msg)
        assertTrue(msg.contains("2 MB") && msg.contains("dirección"))
        // CRLF that normalises under the cap passes.
        val nearly = "#EXTM3U\r\n" + line.replace("\n", "\r\n").repeat((OwnPastedList.MAX_BYTES - 100) / line.length)
        assertTrue(nearly.toByteArray().size > OwnPastedList.MAX_BYTES)
        assertTrue(OwnPastedList.check(nearly) is OwnPastedCheck.Ok)
    }

    @Test fun `a file is filtered by extension, size and then content`() {
        val bytes = m3u.toByteArray()
        assertEquals(OwnPastedFormat.M3U, ok(OwnPastedList.checkFile("lista.M3U8", bytes, truncated = false)).format)
        assertEquals(OwnPastedFormat.W3U, ok(OwnPastedList.checkFile("lista.json", w3u.toByteArray(), truncated = false)).format)
        // The format is told by the content, not the extension: a .m3u holding W3U JSON works.
        assertEquals(OwnPastedFormat.W3U, ok(OwnPastedList.checkFile("lista.m3u", w3u.toByteArray(), truncated = false)).format)
        assertEquals(OwnPastedFormat.M3U, ok(OwnPastedList.checkFile(null, bytes, truncated = false)).format)
        assertEquals(OwnPastedList.WRONG_FILE, refused(OwnPastedList.checkFile("foto.jpg", bytes, truncated = false)))
        assertEquals(OwnPastedList.TOO_LARGE, refused(OwnPastedList.checkFile("lista.m3u", bytes, truncated = true)))
        // Latin-1 files are read like downloads are.
        val latin = "#EXTM3U\n#EXTINF:-1,Señal\nhttps://tv.example.com/s.m3u8\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Encontré 1 canal", ok(OwnPastedList.checkFile("l.m3u", latin, truncated = false)).summary)
    }

    @Test fun `a file name becomes a list name`() {
        assertEquals("Mis canales favoritos", OwnPastedList.nameFromFile("Mis canales favoritos.m3u8"))
        assertEquals("", OwnPastedList.nameFromFile(null))
    }

    @Test fun `pasted urls are recognised and tied to their id`() {
        assertEquals("kino-list:abc", OwnPastedList.urlFor("abc"))
        assertTrue(OwnPastedList.isPasted("kino-list:abc"))
        assertTrue(!OwnPastedList.isPasted("https://a.example.com/kino-list:abc"))
    }

    @Test fun `content is split into sync-sized parts and joined back only when whole and intact`() {
        // Incompressible content: the worst case for the number of parts.
        val rnd = java.util.Random(7)
        val text = buildString { repeat(300_000) { append(('!' + rnd.nextInt(90))) } }
        val digest = OwnPastedList.digest(text)
        val parts = OwnPastedList.split("s1", text, digest, updatedAt = 42L)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.data.length <= OwnPastedList.PART_CHARS && it.parts == parts.size && it.digest == digest && it.updatedAt == 42L })
        assertTrue(parts.all { p -> p.data.all { it.isLetterOrDigit() || it == '-' || it == '_' } })
        assertEquals(text, OwnPastedList.join(parts.shuffled(rnd), digest))
        assertNull(OwnPastedList.join(parts.drop(1), digest))                        // one missing
        assertNull(OwnPastedList.join(parts, OwnPastedList.digest("otra")))          // other content
        val tampered = parts.toMutableList().also { it[0] = it[0].copy(data = it[0].data.reversed()) }
        assertNull(OwnPastedList.join(tampered, digest))                             // bytes changed
    }

    @Test fun `a 2 MB list fits in the part cap, and an inflating bomb is refused`() {
        val rnd = java.util.Random(1)
        val text = buildString { while (length < OwnPastedList.MAX_BYTES) append(('!' + rnd.nextInt(90))) }.take(OwnPastedList.MAX_BYTES)
        val parts = OwnPastedList.split("s1", text, OwnPastedList.digest(text), 1L)
        assertTrue(parts.size <= OwnPastedList.MAX_PARTS)
        val bomb = "a".repeat(OwnPastedList.MAX_BYTES + 10)
        val bombParts = OwnPastedList.split("s1", bomb, OwnPastedList.digest(bomb), 1L)
        assertNull(OwnPastedList.join(bombParts, OwnPastedList.digest(bomb)))
        assertNotNull(OwnPastedList.join(OwnPastedList.split("s1", "x", OwnPastedList.digest("x"), 1L), OwnPastedList.digest("x")))
    }
}
