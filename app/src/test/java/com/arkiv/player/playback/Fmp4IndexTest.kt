package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Chromecast remux (a fragmented MP4 written by media3's muxer, often still growing) cut into
 * HLS segments that each play on their own. See [Fmp4Fixture] for the file and the reader.
 */
class Fmp4IndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun remux() = Fmp4Fixture.copyTo(tmp.root)

    @Test
    fun `reads the tracks and their codec strings out of the moov`() {
        val index = Fmp4Fixture.indexOf(remux())
        val video = index.tracks.single { it.handler == "vide" }
        val audio = index.tracks.single { it.handler == "soun" }
        assertEquals("avc1.F4000A", video.codec)
        assertEquals(320 to 136, video.width to video.height)
        assertEquals(90_000L, video.timescale)
        assertEquals("mp4a.40.2", audio.codec)
        assertEquals(44_100L, audio.timescale)
        assertTrue(index.initEnd > 0)
    }

    @Test
    fun `every fragment is found, back to back, and they add up to the title`() {
        val index = Fmp4Fixture.indexOf(remux())
        val fragments = index.fragments
        assertEquals(listOf(3.0, 3.0, 2.0), fragments.map { Math.round(it.durationSec * 1000) / 1000.0 })
        fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        // Each fragment's decode time is where the previous one ended, on every track.
        index.tracks.forEach { track ->
            var expected = 0L
            fragments.forEach { f ->
                val traf = f.trafs.single { it.trackId == track.id }
                assertEquals(expected, traf.baseDecodeTime)
                expected += traf.durationTicks
            }
        }
    }

    @Test
    fun `a file still being written only yields the fragments that are whole`() {
        val file = remux()
        val full = Fmp4Fixture.indexOf(file).fragments
        // Cut points everywhere: inside the moov, inside moofs, inside mdats, on boundaries.
        val cuts = (0..file.length() step 997).toList() + full.flatMap { listOf(it.start, it.end - 1, it.end) }
        cuts.forEach { length ->
            val partial = Fmp4Fixture.indexOf(file, length).fragments
            assertEquals("at $length", full.subList(0, partial.size), partial)
            assertEquals("at $length", full.count { it.end <= length }, partial.size)
        }
    }

    @Test
    fun `indexing the growing file in steps gives the same result as all at once`() {
        val file = remux()
        val all = Fmp4Fixture.indexOf(file)
        val grown = Fmp4Index()
        java.io.RandomAccessFile(file, "r").use { raf ->
            var length = 0L
            while (length < file.length()) {
                length = minOf(file.length(), length + 7_001)
                val l = length
                grown.update(l) { offset, size ->
                    val n = minOf(size.toLong(), l - offset).toInt().coerceAtLeast(0)
                    ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
                }
            }
        }
        assertEquals(all.fragments, grown.fragments)
        assertEquals(all.tracks, grown.tracks)
    }

    @Test
    fun `each segment on its own carries the same samples at the same times as the whole file`() {
        val file = remux()
        val index = Fmp4Fixture.indexOf(file)
        val whole = Fmp4Fixture.readSamples(file.readBytes())
        assertEquals(240 + 344, whole.size)
        // Segments of one fragment each, so every fragment is checked standing alone.
        val segments = RemuxHls.segments(index.fragments, complete = true, targetSec = 0.1)
        assertEquals(index.fragments.size, segments.size)
        val fromSegments = segments.flatMap { s ->
            val (init, media) = Fmp4Fixture.segmentBytes(file, index, s)
            // A FRESH reader per segment: what a segment player has.
            Fmp4Fixture.readSamples(init + media)
        }
        assertEquals(whole, fromSegments)
    }

    @Test
    fun `without the rewrite a later fragment would start at time zero`() {
        val file = remux()
        val index = Fmp4Fixture.indexOf(file)
        val f = index.fragments[1]
        val all = file.readBytes()
        val init = all.copyOfRange(0, index.initEnd.toInt())
        val raw = all.copyOfRange(f.start.toInt(), f.end.toInt())
        // Unrewritten: no tfdt, and the base offset points into the file, not this buffer.
        val rawFirst = runCatching { Fmp4Fixture.readSamples(init + raw).first().decodeTime }.getOrNull()
        val (i, rewritten) = Fmp4Fixture.segmentBytes(file, index, RemuxHls.Segment(1, 1, f.durationSec))
        val video = index.tracks.single { it.handler == "vide" }.id
        val fixedFirst = Fmp4Fixture.readSamples(i + rewritten).first { it.trackId == video }.decodeTime
        assertEquals(3L * 90_000, fixedFirst)
        assertNotEquals(fixedFirst, rawFirst)
    }

    @Test
    fun `the rewrite moves data_offset by exactly what the moof grew`() {
        val file = remux()
        val index = Fmp4Fixture.indexOf(file)
        val f = index.fragments[1]
        val moof = file.readBytes().copyOfRange(f.start.toInt(), f.start.toInt() + f.moofSize)
        val out = Fmp4Index.rewriteMoof(moof, f.start, index.baseTimes(f))
        // Two trafs: each loses the 8-byte base_data_offset and gains a 20-byte tfdt.
        assertEquals(moof.size + 2 * 12, out.size)
        assertEquals(out.size.toLong(), Fmp4Index.u32(out, 0))
        assertEquals("moof", Fmp4Index.fourcc(out, 4))
        val trafs = Fmp4Index().parseMoof(out)
        assertEquals(index.baseTimes(f), trafs.associate { it.trackId to it.tfdt })
    }

    @Test
    fun `hevc codec string follows ISO 14496-15 Annex E`() {
        // hvcC of an x265 Main / level 3.1 stream: profile_space 0, tier Main, profile 1,
        // compatibility flags 0x60000000, constraints B0 00 00 00 00 00, level 93.
        val hvcC = byteArrayOf(1, 0x01, 0x60, 0, 0, 0, 0xB0.toByte(), 0, 0, 0, 0, 0, 93)
        val moov = moovWithVideoEntry("hvc1", box("hvcC", hvcC))
        val track = Fmp4Index().parseMoov(moov).single()
        assertEquals("hvc1.1.6.L93.B0", track.codec)
        assertEquals(1280 to 536, track.width to track.height)
    }

    /** A moov with one video trak whose stsd holds a single [entry] sample entry carrying [config]. */
    private fun moovWithVideoEntry(entry: String, config: ByteArray): ByteArray {
        val visual = ByteArray(78).also {
            it[24] = (1280 shr 8).toByte(); it[25] = (1280 and 0xFF).toByte()
            it[26] = (536 shr 8).toByte(); it[27] = (536 and 0xFF).toByte()
        }
        val stsd = box("stsd", ByteArray(4) + Fmp4Index.int32(1) + box(entry, visual + config))
        val hdlr = box("hdlr", ByteArray(8) + "vide".toByteArray() + ByteArray(13))
        val mdhd = box("mdhd", ByteArray(12) + Fmp4Index.int32(90_000) + ByteArray(8))
        val tkhd = box("tkhd", ByteArray(12) + Fmp4Index.int32(1) + ByteArray(68))
        return box("moov", box("trak", tkhd + box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd)))))
    }

    private fun box(type: String, body: ByteArray): ByteArray = Fmp4Index.int32(body.size + 8) + type.toByteArray() + body
}
