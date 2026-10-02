package com.arkiv.player.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Taking the right subtitle out of a SubDL zip, and refusing what is not one. */
class SubtitleZipTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `the srt is taken over junk, a vtt and a mac fork`() {
        val zip = zipOf(
            "__MACOSX/._movie.srt" to "junk -->".toByteArray(),
            "movie.nfo" to "x".toByteArray(),
            "movie.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nvtt\n".toByteArray(),
            "sub/movie.srt" to srt.toByteArray(),
        )
        assertEquals(srt, String(SubtitleZip.extract(zip)!!))
    }

    @Test
    fun `a season pack gives the episode asked for`() {
        val zip = zipOf(
            "Show.S02E01.srt" to "1\n00:00:01,000 --> 00:00:02,000\nuno\n".toByteArray(),
            "Show.2x10.srt" to "1\n00:00:01,000 --> 00:00:02,000\ndiez\n".toByteArray(),
            "Show.S02E03.srt" to "1\n00:00:01,000 --> 00:00:02,000\ntres\n".toByteArray(),
        )
        assertTrue(String(SubtitleZip.extract(zip, 2, 3)!!).contains("tres"))
        assertTrue(String(SubtitleZip.extract(zip, 2, 10)!!).contains("diez"))
        assertTrue(SubtitleZip.matchesEpisode("show.e07.720p.srt", null, 7))
        assertFalse(SubtitleZip.matchesEpisode("Show.S01E17.srt", 1, 7))
        assertFalse(SubtitleZip.matchesEpisode("Show.S03E07.srt", 1, 7))
    }

    @Test
    fun `a plain subtitle passes, a rar or garbage does not, nor an oversized entry`() {
        assertEquals(srt, String(SubtitleZip.extract(srt.toByteArray())!!))
        assertNull(SubtitleZip.extract("Rar!\u001a\u0007\u0000junk".toByteArray()))
        assertNull(SubtitleZip.extract("<html>no</html>".toByteArray()))
        assertNull(SubtitleZip.extract(zipOf("readme.txt" to "x".toByteArray())))
        val huge = ByteArray(SubtitleZip.MAX_ENTRY_BYTES + 10) { 'a'.code.toByte() }
        assertNull(SubtitleZip.extract(zipOf("big.srt" to huge)))
    }
}
