package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalFilePathsTest {

    @Test
    fun `sanitizes characters not valid in a file name`() {
        assertEquals("web_series_123__s01e02", LocalFilePaths.sanitize("web:series:123::s01e02"))
    }

    @Test
    fun `keeps letters, numbers, dot, hyphen and underscore`() {
        assertEquals("Show.S01E02-1080p_x265", LocalFilePaths.sanitize("Show.S01E02-1080p_x265"))
    }

    @Test
    fun `takes the extension from the origin name`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie S01E01 1080p.mkv"))
    }

    @Test
    fun `falls back to mp4 if the origin has no extension`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "sin extension"))
    }

    @Test
    fun `falls back to mp4 if there's no origin name`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", null))
    }

    /** A long "extension" is part of the title, not an extension (e.g. "Peli 2024.Latino"). */
    @Test
    fun `ignores an implausible extension and falls back to mp4`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "Peli 2024.Latino"))
    }

    @Test
    fun `normalizes the extension to lowercase`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie.MKV"))
    }

    /**
     * `m2ts` was missing from the list here but not from `TorrentEngine`'s: a remuxed Blu-ray could
     * be picked to download and then got saved with its name changed to `.mp4`. With a single
     * shared list (`VideoContainer`) that kind of hole disappears.
     */
    @Test
    fun `keeps the extension of the containers we know how to play`() {
        assertEquals("ep1.m2ts", LocalFilePaths.fileNameFor("ep1", "BluRay/00001.m2ts"))
        assertEquals("ep1.ts", LocalFilePaths.fileNameFor("ep1", "ABC_media.ts"))
        assertEquals("ep1.avi", LocalFilePaths.fileNameFor("ep1", "Peli.DivX.avi"))
    }

    /**
     * The NUC download used to get a PAGE URL as its origin name. Cutting at the last dot on that
     * returns garbage (`sitio.com/peli` → `"com/peli"`), so the name has to be normalized as a URL
     * before looking at its extension.
     */
    @Test
    fun `a page URL contributes no extension and falls back to mp4`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "https://allcalidad.com/peli-x/"))
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "https://sitio.com/ver/peli"))
    }

    @Test
    fun `a URL with a video file does contribute its extension`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "https://cdn.com/a/b.mkv?token=x"))
    }

    @Test
    fun `the partial appends dot part`() {
        assertEquals("ep1.mkv.part", LocalFilePaths.partOf(File("/tmp/ep1.mkv")).name)
    }
}
