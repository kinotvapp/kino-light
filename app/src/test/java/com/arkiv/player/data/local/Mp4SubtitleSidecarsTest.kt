package com.arkiv.player.data.local

import com.arkiv.player.data.subtitles.SavedOnlineSubtitle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A prepared download's subtitles: `<video>.<lang>.srt`, UTF-8 with a BOM ([Mp4SubtitleSidecars]). */
class Mp4SubtitleSidecarsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test fun `named after the video and the language, a second one of a language numbered`() {
        val taken = mutableSetOf<String>()
        val a = Mp4SubtitleSidecars.nameFor("magis_7", "es", taken).also { taken += it }
        val b = Mp4SubtitleSidecars.nameFor("magis_7", "en", taken).also { taken += it }
        val c = Mp4SubtitleSidecars.nameFor("magis_7", "es", taken).also { taken += it }
        val d = Mp4SubtitleSidecars.nameFor("magis_7", "es", taken)
        assertEquals(listOf("magis_7.es.srt", "magis_7.en.srt", "magis_7.es.2.srt", "magis_7.es.3.srt"), listOf(a, b, c, d))
    }

    @Test fun `the language code from how sources spell it`() {
        val cases = mapOf(
            "es" to "es", "es-419" to "es", "spa" to "es", "Español" to "es", "Español (Latino)" to "es", "Latino" to "es",
            "English" to "en", "Inglés" to "en", "eng" to "en", "pt-BR" to "pt", "Portugués" to "pt", "ger" to "de",
            "Français" to "fr", "" to "und", "Forzados" to "und",
        )
        cases.forEach { (label, code) -> assertEquals(label, code, Mp4SubtitleSidecars.codeFor(label)) }
    }

    @Test fun `a Windows-1252 SRT comes out UTF-8 with a BOM`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nAñejo, ¿qué tal?\r\n\r\n".toByteArray(charset("windows-1252"))
        val out = Mp4SubtitleSidecars.toSrtBytes(srt)!!
        assertArrayEquals(bom, out.copyOf(3))
        val text = String(out, 3, out.size - 3, Charsets.UTF_8)
        assertTrue(text, text.contains("Añejo, ¿qué tal?"))
        assertTrue(text, text.contains("00:00:01,000 --> 00:00:02,500"))
    }

    @Test fun `WebVTT becomes SRT`() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 line:90%\n<c.yellow>Hola</c> &amp; <i>adiós</i>\n".toByteArray()
        val text = String(Mp4SubtitleSidecars.toSrtBytes(vtt)!!, Charsets.UTF_8).removePrefix("﻿")
        assertEquals("1\r\n00:00:01,000 --> 00:00:02,000\r\nHola & <i>adiós</i>\r\n\r\n", text)
    }

    @Test fun `a file with no cue is not a subtitle`() {
        assertNull(Mp4SubtitleSidecars.toSrtBytes("WEBVTT\n\n".toByteArray()))
    }

    @Test fun `the download's own and the online subtitles become one SRT each, the manifest follows`() {
        val dir = tmp.newFolder("Movies")
        val cache = tmp.newFolder("online_subtitles")
        val id = "magis:7"
        val video = File(dir, "magis_7.mp4").apply { writeBytes(ByteArray(10)) }
        val es = OfflineSubtitleFiles.fileFor(dir, id, 0, "vtt").apply { writeText("WEBVTT\n\n00:01.000 --> 00:02.000\nHola\n") }
        val en = OfflineSubtitleFiles.fileFor(dir, id, 1, "srt").apply { writeText("1\n00:00:03,000 --> 00:00:04,000\nHello\n") }
        OfflineSubtitleFiles.writeManifest(dir, id, listOf(OfflineSubtitleFiles.Saved(es, "Español", false), OfflineSubtitleFiles.Saved(en, "English", true)))
        val online = File(cache, "abc.srt").apply { writeText("1\n00:00:05,000 --> 00:00:06,000\nOtra\n") }
        val saved = Mp4SubtitleSidecars.rewrite(dir, id, video, listOf(SavedOnlineSubtitle("es", "Español · OpenSubtitles", online.path)))

        assertEquals(listOf("magis_7.es.srt", "magis_7.en.srt", "magis_7.es.2.srt"), saved.map { it.file.name })
        saved.forEach { assertArrayEquals(it.file.name, bom, it.file.readBytes().copyOf(3)) }
        assertFalse("the old names are gone", es.exists() || en.exists())
        assertTrue("the online cache is never touched", online.exists())
        // The manifest keeps the labels; the online copy is not offered while the online one is there…
        assertEquals(listOf("Español", "English"), OfflineSubtitleFiles.read(dir, id).map { it.lang })
        assertEquals(3, OfflineSubtitleFiles.readAll(dir, id).size)
        // …and is, once the cache lost it.
        online.delete()
        assertEquals(listOf("Español", "English", "Español · OpenSubtitles"), OfflineSubtitleFiles.read(dir, id).map { it.lang })

        // Again: the same names, nothing duplicated.
        val again = Mp4SubtitleSidecars.rewrite(dir, id, video, emptyList())
        assertEquals(saved.map { it.file.name }, again.map { it.file.name })
        assertEquals(setOf("magis_7.mp4", "magis_7.es.srt", "magis_7.en.srt", "magis_7.es.2.srt", "magis_7.subs.json"), dir.list()!!.toSet())
    }

    @Test fun `an adopted twin's file gives the sidecars this episode's name`() {
        val dir = tmp.newFolder("Movies")
        val video = File(dir, "magis_twin.mp4").apply { writeBytes(ByteArray(10)) }
        val online = File(tmp.root, "x.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n") }
        val saved = Mp4SubtitleSidecars.rewrite(dir, "magis:7", video, listOf(SavedOnlineSubtitle("es", "Español", online.path)))
        assertEquals(listOf("magis_7.es.srt"), saved.map { it.file.name })
    }

    @Test fun `nothing to do without subtitles`() {
        val dir = tmp.newFolder("Movies")
        assertEquals(emptyList<OfflineSubtitleFiles.Saved>(), Mp4SubtitleSidecars.rewrite(dir, "magis:7", File(dir, "magis_7.mp4"), emptyList()))
        assertTrue(dir.list()!!.isEmpty())
    }
}
