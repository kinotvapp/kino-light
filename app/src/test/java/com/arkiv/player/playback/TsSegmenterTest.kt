package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting an MPEG-TS into HLS segments by BYTE RANGE, on real PCR boundaries.
 *
 * The spike cut by prorated bytes, which is what makes its seeking bad: `#EXTINF` was a guess from
 * the file size, so the receiver's idea of "minute 12" drifted from the actual bytes. Here every
 * boundary is a packet that carries a PCR, so the offset is exact AND its timestamp is read rather
 * than estimated.
 *
 * The whole file is never scanned. A 950 MB title is 5 million packets, and reading all of them to
 * build a playlist would cost more than the playback it enables. Instead each boundary is *probed*:
 * seek to the byte where that second should roughly land, read a small window, and take the first
 * PCR in it. The estimate only has to be close enough to land inside the window; the PCR it finds
 * is what makes the result exact.
 */
class TsSegmenterTest {

    private val PACKET = 188

    /** A TS packet on [pid] carrying [base90k] as its PCR. Same shape as TsDurationProbeTest's. */
    private fun packetWithPcr(pid: Int, base90k: Long): ByteArray {
        val p = ByteArray(PACKET)
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x20.toByte()                 // adaptation field only
        p[4] = 7                             // adaptation length
        p[5] = 0x10                          // PCR flag
        p[6] = ((base90k shr 25) and 0xFF).toByte()
        p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte()
        p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = (((base90k and 1L) shl 7)).toByte()
        return p
    }

    /**
     * A packet that carries a PCR *and* has `random_access_indicator` set: a decoder can start
     * here. In a real stream this is the packet holding the start of an IDR frame.
     */
    private fun packetWithKeyframe(pid: Int, base90k: Long): ByteArray =
        packetWithPcr(pid, base90k).also { it[5] = (it[5].toInt() or 0x40).toByte() }

    private fun packetWithoutPcr(pid: Int): ByteArray {
        val p = ByteArray(PACKET)
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10.toByte()                 // payload only
        return p
    }

    /**
     * A synthetic transport stream: [seconds] seconds at one PCR every [pcrEveryPackets] packets,
     * the rest payload. PCR advances 90 kHz per second, spread evenly over the packets.
     */
    private fun stream(
        seconds: Int,
        pcrEveryPackets: Int = 10,
        packetsPerSecond: Int = 100,
        /** Keyframe cadence. `null` builds a stream with NO random access point anywhere, which a
         *  real transport stream never is -- only the test for that refusal uses it. */
        keyframeEverySec: Int? = 2,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val totalPackets = seconds * packetsPerSecond
        for (i in 0 until totalPackets) {
            val pcr = 90_000L * i / packetsPerSecond
            val keyframe = keyframeEverySec != null && i % (keyframeEverySec * packetsPerSecond) == 0
            when {
                keyframe -> out.write(packetWithKeyframe(0x100, pcr))
                i % pcrEveryPackets == 0 -> out.write(packetWithPcr(0x100, pcr))
                else -> out.write(packetWithoutPcr(0x101))
            }
        }
        return out.toByteArray()
    }

    private fun readerOver(bytes: ByteArray): (Long, Int) -> ByteArray = { offset, size ->
        val from = offset.coerceIn(0, bytes.size.toLong()).toInt()
        val to = (from + size).coerceAtMost(bytes.size)
        bytes.copyOfRange(from, to)
    }

    @Test
    fun `covers the whole file with no gaps and no overlaps`() {
        val bytes = stream(seconds = 60)
        val segs = TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes))

        assertTrue("should produce several segments, got ${segs.size}", segs.size >= 2)
        assertEquals("starts at byte 0", 0L, segs.first().start)
        var cursor = 0L
        segs.forEach { s ->
            assertEquals("segment $s must start where the previous one ended", cursor, s.start)
            cursor += s.length
        }
        assertEquals("the last segment must end at EOF", bytes.size.toLong(), cursor)
    }

    /** A cut in the middle of a packet hands the decoder a truncated one; every offset is a packet. */
    @Test
    fun `every boundary lands on a packet boundary`() {
        val bytes = stream(seconds = 60)
        TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes)).forEach {
            assertEquals("offset ${it.start} is not a multiple of 188", 0L, it.start % PACKET)
        }
    }

    /**
     * The point of the whole exercise: durations are READ from the PCR difference between one
     * boundary and the next, not derived from the byte size. Here the stream is exactly 60 s.
     */
    @Test
    fun `durations add up to the real duration, from the PCRs`() {
        val bytes = stream(seconds = 60)
        val total = TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes))
            .sumOf { it.durationSec }
        assertEquals(60.0, total, 1.5)
    }

    /** Each segment should be near the target, or the receiver's buffering behaviour changes. */
    @Test
    fun `segments come out near the requested length`() {
        val bytes = stream(seconds = 60)
        TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes)).dropLast(1).forEach {
            assertTrue("segment of ${it.durationSec}s is nowhere near 10s", it.durationSec in 5.0..15.0)
        }
    }

    /**
     * Reading is sampled, not exhaustive -- that is the whole reason this is usable on a 950 MB
     * file. A 60 s stream at 100 packets/s is ~1.1 MB; the segmenter must not read all of it.
     */
    @Test
    fun `does not read the whole file`() {
        val bytes = stream(seconds = 60)
        var read = 0L
        val counting: (Long, Int) -> ByteArray = { off, size ->
            val b = readerOver(bytes)(off, size)
            read += b.size
            b
        }
        TsSegmenter.segment(bytes.size.toLong(), 10.0, counting)
        assertTrue("read $read of ${bytes.size} bytes -- that is a full scan", read < bytes.size / 2)
    }

    /**
     * THE test that separates this from the spike it replaces, and the reason the others are not
     * enough on their own: a constant-bitrate stream makes prorated durations and PCR durations
     * agree, so it cannot tell the two apart.
     *
     * This stream is deliberately variable: 30 s dense (200 packets/s) then 30 s sparse
     * (50 packets/s). Prorating by byte size would hand every 10 s segment the same 1250 packets
     * and therefore claim ~10 s for stretches that really run 6 s and 25 s -- which is exactly the
     * drift that makes seeking land in the wrong place. Reading the PCRs, every segment is ~10 s
     * and it is the BYTE lengths that differ, by about 4x.
     */
    @Test
    fun `on a variable bitrate stream the durations are right and the byte lengths are not equal`() {
        val out = java.io.ByteArrayOutputStream()
        var packet = 0
        fun emit(seconds: Int, packetsPerSecond: Int, startSec: Int) {
            for (i in 0 until seconds * packetsPerSecond) {
                val pcr = 90_000L * startSec + 90_000L * i / packetsPerSecond
                when {
                    i % (2 * packetsPerSecond) == 0 -> out.write(packetWithKeyframe(0x100, pcr))
                    packet % 10 == 0 -> out.write(packetWithPcr(0x100, pcr))
                    else -> out.write(packetWithoutPcr(0x101))
                }
                packet++
            }
        }
        emit(seconds = 30, packetsPerSecond = 200, startSec = 0)
        emit(seconds = 30, packetsPerSecond = 50, startSec = 30)
        val bytes = out.toByteArray()

        val segs = TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes))
        assertTrue("expected several segments, got ${segs.size}", segs.size >= 4)

        // Durations are read, so they hold despite the bitrate change.
        segs.dropLast(1).forEach {
            assertTrue("segment of ${it.durationSec}s should be about 10s", it.durationSec in 8.0..12.0)
        }
        assertEquals(60.0, segs.sumOf { it.durationSec }, 2.0)

        // And the byte lengths are NOT uniform -- which is what proves they were not prorated.
        val longest = segs.maxOf { it.length }
        val shortest = segs.minOf { it.length }
        assertTrue(
            "byte lengths $shortest..$longest look prorated, not cut on PCR",
            longest > shortest * 2,
        )
    }

    /**
     * THE fix for "it plays in sections". A segment must begin where a decoder can START, which
     * in a transport stream means a packet with `random_access_indicator` set -- the IDR frame.
     * Cutting on any PCR lands mid-GOP: the receiver gets a segment it cannot begin decoding,
     * stalls, and re-requests it. Measured 2026-09-12 casting Magis: it looped 375, 375, 376,
     * 375, 375, 376 and the position froze for 18 s at a time.
     *
     * Here keyframes are every 4 s while PCRs are every 0.1 s, so cutting on "any PCR" would give
     * boundaries that are almost never keyframes. Every boundary must be one.
     */
    @Test
    fun `every boundary is a random access point, not merely a PCR`() {
        val out = java.io.ByteArrayOutputStream()
        val offsetsWithKeyframe = HashSet<Long>()
        var written = 0L
        val packetsPerSecond = 100
        for (i in 0 until 60 * packetsPerSecond) {
            val pcr = 90_000L * i / packetsPerSecond
            val packet = when {
                // A keyframe every 4 s.
                i % (4 * packetsPerSecond) == 0 -> {
                    offsetsWithKeyframe.add(written); packetWithKeyframe(0x100, pcr)
                }
                i % 10 == 0 -> packetWithPcr(0x100, pcr)
                else -> packetWithoutPcr(0x101)
            }
            out.write(packet)
            written += packet.size
        }
        val bytes = out.toByteArray()

        val segs = TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes))
        assertTrue("expected several segments, got ${segs.size}", segs.size >= 3)
        // The first starts at byte 0 by construction; every later boundary must be a keyframe.
        segs.drop(1).forEach {
            assertTrue(
                "segment starting at ${it.start} is not a random access point -- the receiver " +
                    "cannot begin decoding there",
                it.start in offsetsWithKeyframe,
            )
        }
    }

    /** No keyframes at all (or not a TS): don't hand back boundaries a decoder cannot use. */
    @Test
    fun `returns nothing when the stream has no random access points`() {
        val bytes = stream(seconds = 60, keyframeEverySec = null)   // PCRs, no random access
        assertTrue(TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes)).isEmpty())
    }

    // --- segmentByBitrate: the remote path, where probing is unaffordable ---

    @Test
    fun `bitrate segments cover the file with no gaps and land on packet boundaries`() {
        val total = 900_000_000L
        val segs = TsSegmenter.segmentByBitrate(total, 7200.0, 10.0)
        assertTrue(segs.size > 100)
        var cursor = 0L
        segs.forEach {
            assertEquals(cursor, it.start)
            assertEquals("offset ${it.start} is not a multiple of 188", 0L, it.start % PACKET)
            cursor += it.length
        }
        assertEquals(total, cursor)
    }

    @Test
    fun `bitrate durations add up to the duration given`() {
        val segs = TsSegmenter.segmentByBitrate(900_000_000L, 7200.0, 10.0)
        assertEquals(7200.0, segs.sumOf { it.durationSec }, 1.0)
    }

    @Test
    fun `bitrate path refuses to invent a playlist without a duration`() {
        assertTrue(TsSegmenter.segmentByBitrate(900_000L, 0.0, 10.0).isEmpty())
        assertTrue(TsSegmenter.segmentByBitrate(0L, 7200.0, 10.0).isEmpty())
    }

    @Test
    fun `a short stream is one bitrate segment`() {
        val segs = TsSegmenter.segmentByBitrate(50_000L, 4.0, 10.0)
        assertEquals(1, segs.size)
        assertEquals(50_000L, segs[0].length)
    }

    /** The playlist is what the receiver parses; its shape is not negotiable. */
    @Test
    fun `playlist gives every segment its own uri and no byterange`() {
        val segs = TsSegmenter.segmentByBitrate(100_000L, 30.0, 10.0)
        val m3u8 = TsSegmenter.playlist(segs) { "/seg?n=$it" }
        assertTrue(m3u8.startsWith("#EXTM3U\n"))
        assertTrue(m3u8.contains("#EXT-X-VERSION:3"))
        assertTrue(m3u8.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(m3u8.contains("#EXT-X-TARGETDURATION:"))
        assertTrue("must end the list or the receiver waits for more", m3u8.endsWith("#EXT-X-ENDLIST\n"))
        // One URI per segment, each distinct.
        val uris = m3u8.lines().filter { it.startsWith("/seg?") }
        assertEquals(segs.size, uris.size)
        assertEquals(segs.size, uris.toSet().size)
        assertEquals("/seg?n=0", uris.first())
    }

    /**
     * The receiver does NOT implement `EXT-X-BYTERANGE` (measured: it fetched the segment URI four
     * times with no `Range` header and pulled the first 63 KB each time). Emitting the tag again
     * would reintroduce exactly that failure, so its absence is the assertion.
     */
    @Test
    fun `playlist never emits EXT-X-BYTERANGE`() {
        val m3u8 = TsSegmenter.playlist(TsSegmenter.segmentByBitrate(100_000L, 30.0, 10.0)) { "/seg?n=$it" }
        assertTrue("the receiver ignores this tag; the range must live in the URL", !m3u8.contains("BYTERANGE"))
    }

    @Test
    fun `an empty segment list is not a playlist`() {
        assertEquals("", TsSegmenter.playlist(emptyList()) { "/seg?n=$it" })
    }

    /** Not a transport stream, or no PCR anywhere: say so instead of inventing a playlist. */
    @Test
    fun `returns nothing when there are no PCRs to anchor on`() {
        val junk = ByteArray(200_000) { 0x11 }
        assertTrue(TsSegmenter.segment(junk.size.toLong(), 10.0, readerOver(junk)).isEmpty())
    }

    @Test
    fun `returns nothing for an empty file`() {
        assertTrue(TsSegmenter.segment(0L, 10.0, readerOver(ByteArray(0))).isEmpty())
    }

    /**
     * A stream shorter than one segment is one segment, not zero and not a split with a
     * zero-length tail.
     */
    @Test
    fun `a stream shorter than the target is a single segment`() {
        val bytes = stream(seconds = 4)
        val segs = TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes))
        assertEquals(1, segs.size)
        assertEquals(0L, segs[0].start)
        assertEquals(bytes.size.toLong(), segs[0].length)
    }

    /**
     * The PCR is a 33-bit counter that wraps every ~26.5 h. A stream that wraps mid-file must not
     * produce a negative duration -- the same case `TsDurationProbe.durationMs` already handles.
     */
    @Test
    fun `survives a PCR wrap mid-stream`() {
        val wrap = 1L shl 33
        val out = java.io.ByteArrayOutputStream()
        // 40 boundaries' worth, starting just under the wrap so it rolls over partway.
        for (i in 0 until 4000) {
            if (i % 10 == 0) {
                val pcr = (wrap - 90_000L * 10 + 90_000L * i / 100) % wrap
                out.write(packetWithPcr(0x100, pcr))
            } else {
                out.write(packetWithoutPcr(0x101))
            }
        }
        val bytes = out.toByteArray()
        TsSegmenter.segment(bytes.size.toLong(), 10.0, readerOver(bytes)).forEach {
            assertTrue("negative/absurd duration ${it.durationSec}s across the wrap", it.durationSec >= 0.0)
        }
    }
}
