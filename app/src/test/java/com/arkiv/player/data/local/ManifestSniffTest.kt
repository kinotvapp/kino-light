package com.arkiv.player.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recognizing an HLS/DASH/Smooth manifest from what the server actually answered, not from the URL. */
class ManifestSniffTest {

    @Test fun `a manifest content type is recognized whatever its case or parameters`() {
        listOf(
            "application/vnd.apple.mpegurl", "application/x-mpegURL", "audio/mpegurl", "audio/x-mpegurl",
            "application/dash+xml", "application/vnd.ms-sstr+xml", "Application/VND.Apple.MpegURL; charset=utf-8",
        ).forEach { assertTrue(it, ManifestSniff.isManifestMime(it)) }
    }

    @Test fun `a video or unknown content type is not a manifest`() {
        listOf("video/mp4", "video/x-matroska", "application/octet-stream", "text/plain", "", null)
            .forEach { assertFalse(it.toString(), ManifestSniff.isManifestMime(it)) }
    }

    @Test fun `a body that starts like an HLS playlist is a manifest`() {
        assertTrue(ManifestSniff.looksLikeManifest("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()))
        // A BOM and leading whitespace do not hide it.
        assertTrue(ManifestSniff.looksLikeManifest(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n #EXTM3U".toByteArray()))
    }

    @Test fun `a body that starts like a DASH or Smooth manifest is a manifest`() {
        assertTrue(ManifestSniff.looksLikeManifest("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
        assertTrue(ManifestSniff.looksLikeManifest("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD profiles=\"x\">".toByteArray()))
        assertTrue(ManifestSniff.looksLikeManifest("<?xml version=\"1.0\"?><SmoothStreamingMedia MajorVersion=\"2\">".toByteArray()))
    }

    @Test fun `video bytes, an unrelated xml or an empty body are not a manifest`() {
        // An MP4's first box.
        assertFalse(ManifestSniff.looksLikeManifest(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(100)))
        assertFalse(ManifestSniff.looksLikeManifest("VIDEO-BYTES".toByteArray()))
        assertFalse(ManifestSniff.looksLikeManifest("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\">".toByteArray()))
        assertFalse(ManifestSniff.looksLikeManifest(ByteArray(0)))
        // "#EXT" alone (a stray comment) is not the playlist header.
        assertFalse(ManifestSniff.looksLikeManifest("#EXTINF:10,\n".toByteArray()))
    }

    @Test fun `the sniff window is small, so a real file's first bytes are enough`() {
        assertTrue(ManifestSniff.SNIFF_BYTES in 64..4096)
    }
}
