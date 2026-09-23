package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The container comes from the BYTES, not from the file name.
 *
 * It's the same rule that already governed magis, applied to the cast side: there used to be
 * three different MIME tables guessing by extension and disagreeing with each other
 * —`TorrentStreamServer` sent everything unknown to matroska, `LocalFileServer` to mp4 and
 * `CastRequestBuilder` to mp4 too—, and that string is exactly what the Chromecast receiver and
 * the DLNA renderer use to decide whether to open the stream. libVLC probes and survives; they
 * don't.
 */
class VideoContainerTest {

    /** [n]-byte header with [bytes] written from [offset]. The rest zeroed. */
    private fun header(n: Int, offset: Int, bytes: ByteArray): ByteArray =
        ByteArray(n).also { bytes.copyInto(it, offset) }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    // ---- Signature ----

    @Test fun `mp4 is recognized by ftyp`() {
        val h = header(64, 4, ascii("ftypisom"))
        assertEquals(Container.MP4, VideoContainer.bySignature(h))
    }

    @Test fun `old mov is recognized by moov`() {
        val h = header(64, 4, ascii("moov"))
        assertEquals(Container.MP4, VideoContainer.bySignature(h))
    }

    /** Matroska and WebM share the EBML magic: DocType tells them apart, not the four bytes. */
    @Test fun `matroska and webm share magic and DocType tells them apart`() {
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val mkv = header(64, 0, ebml).also { ascii("matroska").copyInto(it, 24) }
        val webm = header(64, 0, ebml).also { ascii("webm").copyInto(it, 24) }
        assertEquals(Container.MATROSKA, VideoContainer.bySignature(mkv))
        assertEquals(Container.WEBM, VideoContainer.bySignature(webm))
    }

    /** With no DocType in sight it falls back to matroska, the common case and the superset. */
    @Test fun `ebml with no readable DocType falls back to matroska`() {
        val h = header(64, 0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        assertEquals(Container.MATROSKA, VideoContainer.bySignature(h))
    }

    /**
     * A single 0x47 isn't enough: it's such a common byte it shows up in any file. What
     * identifies a TS is the sync byte REPEATED every 188 bytes.
     */
    @Test fun `mpegts requires the sync byte repeated every 188 bytes`() {
        val ts = ByteArray(600).also {
            it[0] = 0x47; it[188] = 0x47; it[376] = 0x47
        }
        assertEquals(Container.MPEGTS, VideoContainer.bySignature(ts))
    }

    @Test fun `a lone 0x47 is not mpegts`() {
        val h = ByteArray(600).also { it[0] = 0x47 }
        assertNull(VideoContainer.bySignature(h))
    }

    /** m2ts puts 4 timestamp bytes in front of each packet: the sync starts at 4. */
    @Test fun `m2ts is recognized with the sync shifted four bytes`() {
        val m2ts = ByteArray(700).also {
            it[4] = 0x47; it[196] = 0x47; it[388] = 0x47
        }
        assertEquals(Container.MPEGTS, VideoContainer.bySignature(m2ts))
    }

    @Test fun `avi is recognized by RIFF plus AVI`() {
        val h = header(64, 0, ascii("RIFF")).also { ascii("AVI ").copyInto(it, 8) }
        assertEquals(Container.AVI, VideoContainer.bySignature(h))
    }

    /** A WAV also starts with RIFF: with no "AVI " it's not an AVI. */
    @Test fun `RIFF with no AVI is not avi`() {
        val h = header(64, 0, ascii("RIFF")).also { ascii("WAVE").copyInto(it, 8) }
        assertNull(VideoContainer.bySignature(h))
    }

    @Test fun `mpeg program stream is recognized by the pack header`() {
        val h = header(64, 0, byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte()))
        assertEquals(Container.MPEGPS, VideoContainer.bySignature(h))
    }

    @Test fun `asf wmv is recognized by its GUID`() {
        val guid = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        )
        assertEquals(Container.ASF, VideoContainer.bySignature(header(64, 0, guid)))
    }

    @Test fun `ogg is recognized by OggS`() {
        assertEquals(Container.OGG, VideoContainer.bySignature(header(64, 0, ascii("OggS"))))
    }

    @Test fun `an empty or short header does not make up a container`() {
        assertNull(VideoContainer.bySignature(ByteArray(0)))
        assertNull(VideoContainer.bySignature(ByteArray(3)))
    }

    @Test fun `bytes that are not from any known container`() {
        assertNull(VideoContainer.bySignature(ByteArray(600) { 0x5A }))
    }

    // ---- Extension (fallback) ----

    @Test fun `the extension covers the known containers`() {
        assertEquals(Container.MP4, VideoContainer.byExtension("movie.mp4"))
        assertEquals(Container.MP4, VideoContainer.byExtension("movie.m4v"))
        assertEquals(Container.MP4, VideoContainer.byExtension("movie.MOV"))
        assertEquals(Container.MATROSKA, VideoContainer.byExtension("movie.mkv"))
        assertEquals(Container.WEBM, VideoContainer.byExtension("movie.webm"))
        assertEquals(Container.MPEGTS, VideoContainer.byExtension("movie.ts"))
        assertEquals(Container.MPEGTS, VideoContainer.byExtension("movie.m2ts"))
        assertEquals(Container.AVI, VideoContainer.byExtension("movie.avi"))
        assertEquals(Container.MPEGPS, VideoContainer.byExtension("movie.mpg"))
        assertEquals(Container.ASF, VideoContainer.byExtension("movie.wmv"))
        assertEquals(Container.OGG, VideoContainer.byExtension("movie.ogv"))
    }

    @Test fun `the extension ignores the querystring and the fragment`() {
        assertEquals(Container.MATROSKA, VideoContainer.byExtension("http://x/y/z.mkv?t=1&u=2"))
        assertEquals(Container.MP4, VideoContainer.byExtension("http://x/y/z.mp4#frag"))
    }

    @Test fun `with no known extension it does not guess`() {
        assertNull(VideoContainer.byExtension("http://cdn/stream"))
        assertNull(VideoContainer.byExtension("file.txt"))
        assertNull(VideoContainer.byExtension(""))
    }

    // ---- The combined decision ----

    /**
     * The case that motivates all this: `LocalFilePaths.fileNameFor` saves as `.mp4` everything
     * downloaded from the NUC (yt-dlp often produces mkv and the source URL is a web page, with
     * no video extension). The bytes tell the truth and the name lies: the signature wins.
     */
    @Test fun `the signature beats the name when they disagree`() {
        val mkv = header(64, 0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        assertEquals(Container.MATROSKA, VideoContainer.of(mkv, "chapter-3.mp4"))
    }

    @Test fun `with no recognizable signature the name is used`() {
        assertEquals(Container.MATROSKA, VideoContainer.of(ByteArray(600), "chapter-3.mkv"))
    }

    /**
     * With no signature AND no extension nothing is made up: mp4 is the MIME most receivers
     * accept, and lying there is strictly better than sending nothing (the receiver rejects the
     * item with no type).
     */
    @Test fun `with no signature or extension it falls back to mp4`() {
        assertEquals(Container.MP4, VideoContainer.of(ByteArray(600), "thing"))
    }

    @Test fun `the MIMEs are what Chromecast and DLNA expect`() {
        assertEquals("video/mp4", Container.MP4.mime)
        assertEquals("video/x-matroska", Container.MATROSKA.mime)
        assertEquals("video/webm", Container.WEBM.mime)
        assertEquals("video/mp2t", Container.MPEGTS.mime)
        assertEquals("video/x-msvideo", Container.AVI.mime)
        assertEquals("video/mpeg", Container.MPEGPS.mime)
        assertEquals("video/x-ms-asf", Container.ASF.mime)
        assertEquals("video/ogg", Container.OGG.mime)
    }

    /**
     * How many bytes have to be read from the file for the signature to be conclusive. m2ts sets
     * the floor: 4 (offset) + 2×192 (step) + 1 = 389. Reading less leaves m2ts unrecognized.
     */
    @Test fun `the header size is enough for m2ts's sync`() {
        assert(VideoContainer.SIGNATURE_BYTES >= 389)
    }

    // ---- "Is this a video?" (the single list) ----

    /**
     * There used to be FOUR video extension lists in the app and none agreed with another:
     * `TorrentEngine` with no mpg/wmv/ogv, `MetadataParser` with no ts/m2ts, `LocalFilePaths` with
     * no m2ts, and `SubtitleFilePicker` with its own copy. Every gap is a file the app doesn't
     * see: a `.ts` in an archive.org item didn't show up as an episode, and a `.m2ts` downloaded
     * from a torrent got saved with the name changed to `.mp4`.
     */
    @Test fun `isVideo recognizes every container we know how to play`() {
        listOf(
            "a.mkv", "a.mp4", "a.m4v", "a.mov", "a.webm", "a.ts", "a.m2ts", "a.mts",
            "a.avi", "a.mpg", "a.mpeg", "a.wmv", "a.asf", "a.ogv", "a.ogg",
        ).forEach { assert(VideoContainer.isVideo(it)) { "should be video: $it" } }
    }

    @Test fun `isVideo says no to what is not video`() {
        listOf("info.txt", "sub.srt", "cover.jpg", "movie", "movie.nfo", "")
            .forEach { assert(!VideoContainer.isVideo(it)) { "should NOT be video: $it" } }
    }

    /** A name with dots in the title doesn't confuse it: only the last segment counts. */
    @Test fun `isVideo only looks at the last extension`() {
        assert(VideoContainer.isVideo("Show.S01E02.1080p.WEB-DL.mkv"))
        assert(!VideoContainer.isVideo("Show.S01E02.1080p.mkv.srt"))
    }

    /**
     * The NORMALIZED extension, for whoever has to name a file.
     *
     * `LocalFilePaths.fileNameFor` used to pull it with a bare `substringAfterLast('.')` on
     * whatever it was given, and the NUC download hands it a page URL: from
     * `https://site.com/movie` that cut returns `"com/movie"`. It didn't break —it's on no list,
     * so it fell back to mp4— but it could never get it right either.
     */
    @Test fun `the normalized extension serves to name the file`() {
        assertEquals("mkv", VideoContainer.videoExtension("https://site.com/x/y.mkv?t=1"))
        assertEquals("m2ts", VideoContainer.videoExtension("BluRay/00001.m2ts"))
        assertEquals("mp4", VideoContainer.videoExtension("MOVIE.MP4"))
        assertNull(VideoContainer.videoExtension("https://site.com/movie"))
        assertNull(VideoContainer.videoExtension("https://site.com/movie.html"))
    }

    // ---- From disk ----

    private fun tempFile(name: String, content: ByteArray): java.io.File =
        java.io.File.createTempFile("cont", "-$name").apply {
            deleteOnExit()
            writeBytes(content)
        }

    /**
     * The NUC's real case: yt-dlp produces an mkv and `LocalFilePaths.fileNameFor` saves it as
     * `.mp4` because the source URL is a web page, with no video extension. The bytes rule.
     */
    @Test fun `an mkv saved with an mp4 name is recognized by its bytes`() {
        val mkv = ByteArray(600).also {
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(it)
            ascii("matroska").copyInto(it, 24)
        }
        val f = tempFile("chapter.mp4", mkv)
        assertEquals(Container.MATROSKA, VideoContainer.ofFile(f))
    }

    /** A torrent that just started may not have its head on disk yet: the name wins. */
    @Test fun `an empty file is resolved by the name`() {
        val f = tempFile("chapter.mkv", ByteArray(0))
        assertEquals(Container.MATROSKA, VideoContainer.ofFile(f))
    }

    @Test fun `a file that does not exist is resolved by the name`() {
        val f = java.io.File("/does/not/exist/movie.avi")
        assertEquals(Container.AVI, VideoContainer.ofFile(f))
    }

    @Test fun `with no bytes or useful extension it falls back to mp4`() {
        val f = tempFile("thing", ByteArray(0))
        assertEquals(Container.MP4, VideoContainer.ofFile(f))
    }

    /** A file shorter than the signature must not blow up or make anything up: it falls to the name. */
    @Test fun `a file shorter than the signature does not blow up`() {
        val f = tempFile("chunk.ts", ByteArray(10) { 0x47 })
        assertEquals(Container.MPEGTS, VideoContainer.ofFile(f))
    }
}
