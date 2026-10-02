package com.arkiv.player.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle a TV gets is the phone's conversion of the source's file. Wrong here means "Ã±" on the
 * TV, a cue that never shows, or a receiver that rejects the whole WebVTT -- all silent on the phone.
 */
class CastSubtitleTextTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,500\nAño nuevo\n\n2\n00:01:00,000 --> 00:01:03,000\nSegunda línea\ncon dos\n"

    @Test
    fun `UTF-8 with and without a BOM decodes as UTF-8`() {
        assertEquals("Año", CastSubtitleText.decode("Año".toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Año".toByteArray(Charsets.UTF_8)
        assertEquals("Año", CastSubtitleText.decode(bom))
    }

    @Test
    fun `a Windows-1252 Spanish SRT is not read as UTF-8 garbage`() {
        val bytes = "¿Qué pasó, niño? “Sí” – adiós".toByteArray(charset("windows-1252"))
        assertEquals("¿Qué pasó, niño? “Sí” – adiós", CastSubtitleText.decode(bytes))
    }

    @Test
    fun `UTF-16 is read with its BOM and without it`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hola niño".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hola niño", CastSubtitleText.decode(le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hola niño".toByteArray(Charsets.UTF_16BE)
        assertEquals("Hola niño", CastSubtitleText.decode(be))
        val bare = srt.toByteArray(Charsets.UTF_16LE)
        assertEquals(srt, CastSubtitleText.decode(bare))
    }

    @Test
    fun `an SRT parses into cues with comma milliseconds`() {
        val cues = CastSubtitleText.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1_000, 2_500, "Año nuevo"), cues[0])
        assertEquals(SubtitleCue(60_000, 63_000, "Segunda línea\ncon dos"), cues[1])
    }

    @Test
    fun `CRLF, a BOM char, dots, no index and a missing blank line are all tolerated`() {
        val messy = "﻿00:00:01.000 --> 00:00:02.000\r\nUno\r\n2\r\n00:00:03,5 --> 00:00:04,000\r\nDos\r\n00:00:05,000 --> 00:00:06,000\r\nTres"
        val cues = CastSubtitleText.parse(messy)
        assertEquals(listOf("Uno", "Dos", "Tres"), cues.map { it.text })
        assertEquals(3_500L, cues[1].startMs)
    }

    @Test
    fun `font tags, ASS overrides and unknown tags go, italics and bold stay`() {
        val cues = CastSubtitleText.parse("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<font color=\"#ffff00\"><i>Hola</i></font> <B>tú</B> <span>x</span>\n")
        assertEquals("<i>Hola</i> <b>tú</b> x", cues.single().text)
    }

    @Test
    fun `a cue whose text is only tags, or that ends before it starts, is dropped`() {
        val cues = CastSubtitleText.parse("1\n00:00:01,000 --> 00:00:02,000\n<font></font>\n\n2\n00:00:05,000 --> 00:00:04,000\nAl revés\n\n3\n00:00:06,000 --> 00:00:07,000\nBien\n")
        assertEquals(listOf("Bien"), cues.map { it.text })
    }

    @Test
    fun `WebVTT input skips header, NOTE and STYLE, ignores cue settings and unescapes entities`() {
        val vtt = "WEBVTT - algo\n\nSTYLE\n::cue { color: red }\n\nNOTE esto no es un cue\n\nid-1\n00:01.000 --> 00:02.000 align:start position:10%\n<v Ana>Tom &amp; Jerry &lt;3</v>\n\n00:00:03.000 --> 00:00:04.000\n<c.yellow>Otro</c> <00:00:03.500>resto\n"
        val cues = CastSubtitleText.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1_000, 2_000, "Tom & Jerry <3"), cues[0])
        assertEquals("Otro resto", cues[1].text)
    }

    @Test
    fun `WebVTT output escapes what WebVTT can't hold raw and keeps the allowed tags`() {
        val out = CastSubtitleText.toVtt(listOf(SubtitleCue(3_723_004, 3_724_000, "<i>Tom & Jerry</i> <3 -->")))
        assertTrue(out.startsWith("WEBVTT\n\n"))
        assertTrue(out.contains("01:02:03.004 --> 01:02:04.000\n"))
        assertTrue(out.contains("<i>Tom &amp; Jerry</i> &lt;3 →"))
        assertFalse(out.substringAfter("-->").substringAfter("\n").contains("-->"))
    }

    @Test
    fun `round trip SRT to VTT to cues keeps times and text`() {
        val cues = CastSubtitleText.parse(srt)
        assertEquals(cues, CastSubtitleText.parse(CastSubtitleText.toVtt(cues)))
    }

    @Test
    fun `SRT output is numbered with CRLF and comma ms, and its bytes are UTF-8 with a BOM`() {
        val out = CastSubtitleText.toSrt(CastSubtitleText.parse(srt))
        assertTrue(out.startsWith("1\r\n00:00:01,000 --> 00:00:02,500\r\nAño nuevo\r\n\r\n2\r\n"))
        val bytes = CastSubtitleText.utf8SrtBytes(out)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        assertEquals(out, CastSubtitleText.decode(bytes))
        assertEquals(out, String(CastSubtitleText.latin1SrtBytes(out), charset("windows-1252")))
    }

    @Test
    fun `shifting moves cues earlier, clips one straddling zero and drops those before it`() {
        val cues = listOf(SubtitleCue(1_000, 2_000, "a"), SubtitleCue(9_000, 11_000, "b"), SubtitleCue(20_000, 21_000, "c"))
        assertEquals(cues, CastSubtitleText.shift(cues, 0))
        assertEquals(
            listOf(SubtitleCue(0, 1_000, "b"), SubtitleCue(10_000, 11_000, "c")),
            CastSubtitleText.shift(cues, 10_000),
        )
    }

    @Test
    fun `a window keeps the cues that show at some point inside it`() {
        val cues = listOf(SubtitleCue(0, 5_000, "a"), SubtitleCue(5_500, 7_000, "b"), SubtitleCue(12_000, 13_000, "c"))
        assertEquals(listOf("a", "b"), CastSubtitleText.window(cues, 4_000, 10_000).map { it.text })
        assertEquals(emptyList<SubtitleCue>(), CastSubtitleText.window(cues, 7_000, 12_000))
    }

    @Test
    fun `time stamps in every shape seen in the wild`() {
        assertEquals(3_723_004L, CastSubtitleText.timeMs("01:02:03,004"))
        assertEquals(63_500L, CastSubtitleText.timeMs("01:03.5"))
        assertEquals(3_723_000L, CastSubtitleText.timeMs("1:02:03"))
        assertEquals(3_723_250L, CastSubtitleText.timeMs("01:02:03:250"))
        assertNull(CastSubtitleText.timeMs("aa:bb"))
    }
}
