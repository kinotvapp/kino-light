package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineSubtitleFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeSub(dir: File, episodeId: String, index: Int, format: String, body: String): File {
        val f = OfflineSubtitleFiles.fileFor(dir, episodeId, index, format)
        f.writeText(body)
        return f
    }

    @Test
    fun `picks vtt or srt from the format`() {
        val dir = tmp.root
        assertEquals("magis_ep1.sub.0.vtt", OfflineSubtitleFiles.fileFor(dir, "magis:ep1", 0, "vtt").name)
        assertEquals("magis_ep1.sub.1.srt", OfflineSubtitleFiles.fileFor(dir, "magis:ep1", 1, "srt").name)
        // Unknown/blank format defaults to vtt (what the portal serves).
        assertEquals("magis_ep1.sub.2.vtt", OfflineSubtitleFiles.fileFor(dir, "magis:ep1", 2, "").name)
    }

    @Test
    fun `round-trips the manifest keeping the real language label`() {
        val dir = tmp.root
        val id = "magis:ep1"
        val es = writeSub(dir, id, 0, "vtt", "WEBVTT")
        val en = writeSub(dir, id, 1, "srt", "1\n00:00")
        OfflineSubtitleFiles.writeManifest(
            dir, id,
            listOf(
                OfflineSubtitleFiles.Saved(es, "Español", srt = false),
                OfflineSubtitleFiles.Saved(en, "English", srt = true),
            ),
        )

        val read = OfflineSubtitleFiles.read(dir, id)
        assertEquals(2, read.size)
        assertEquals("Español", read[0].lang)
        assertEquals(false, read[0].srt)
        assertEquals(en, read[1].file)
        assertEquals("English", read[1].lang)
        assertEquals(true, read[1].srt)
    }

    @Test
    fun `empty when there is no manifest`() {
        assertEquals(emptyList<OfflineSubtitleFiles.Saved>(), OfflineSubtitleFiles.read(tmp.root, "magis:ghost"))
    }

    @Test
    fun `does not write a manifest for an empty subtitle list`() {
        val dir = tmp.root
        OfflineSubtitleFiles.writeManifest(dir, "magis:ep1", emptyList())
        assertEquals(emptyList<OfflineSubtitleFiles.Saved>(), OfflineSubtitleFiles.read(dir, "magis:ep1"))
    }

    @Test
    fun `skips an entry whose sidecar file is gone`() {
        val dir = tmp.root
        val id = "magis:ep1"
        val es = writeSub(dir, id, 0, "vtt", "WEBVTT")
        val en = writeSub(dir, id, 1, "vtt", "WEBVTT")
        OfflineSubtitleFiles.writeManifest(
            dir, id,
            listOf(
                OfflineSubtitleFiles.Saved(es, "Español", srt = false),
                OfflineSubtitleFiles.Saved(en, "English", srt = false),
            ),
        )
        assertTrue(en.delete())

        val read = OfflineSubtitleFiles.read(dir, id)
        assertEquals(1, read.size)
        assertEquals("Español", read[0].lang)
    }

    /** Every file the download writes shares the media's `sanitize(episodeId)` prefix, so the
     *  removal sweep in LocalDownloadManager (delete by that prefix) takes the subtitles too. */
    @Test
    fun `every sidecar and the manifest share the episode prefix`() {
        val dir = tmp.root
        val id = "magis:ep1"
        val prefix = "${LocalFilePaths.sanitize(id)}."
        val sub = writeSub(dir, id, 0, "vtt", "WEBVTT")
        OfflineSubtitleFiles.writeManifest(dir, id, listOf(OfflineSubtitleFiles.Saved(sub, "Español", false)))

        val written = dir.listFiles()!!.map { it.name }
        assertTrue(written.isNotEmpty())
        assertTrue(written.all { it.startsWith(prefix) })
    }
}
