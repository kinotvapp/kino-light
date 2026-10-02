package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of starting a cast remux mid-file ([TsStartPoint], [TsStartLocator]), against
 * `remux-sync-fixture.ts` (90 s of HEVC with a CRA every 4 s + AAC, as ffmpeg muxes it: PAT/PMT
 * every ~100 ms, the first timestamp 1.48 s). Whether the remux that reads from there stays in
 * step is `RemuxMidFileSyncTest`'s.
 */
class TsStartPointTest {

    private val ts: ByteArray =
        javaClass.classLoader!!.getResourceAsStream("remux-sync-fixture.ts")!!.use { it.readBytes() }

    private fun locator(bytes: ByteArray = ts, reads: MutableList<Pair<Long, Int>>? = null) =
        TsStartLocator(bytes.size.toLong(), { offset, size ->
            reads?.add(offset to size)
            if (offset >= bytes.size) null else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + size).toInt())
        })

    @Test
    fun `the grid point is the last 5-minute mark at least 30 s before the phone`() {
        assertEquals(0L, TsStartPoint.gridMs(0L))
        assertEquals(0L, TsStartPoint.gridMs(5 * 60_000L + 29_999L))
        assertEquals(300_000L, TsStartPoint.gridMs(5 * 60_000L + 30_000L))
        // The two measured casts: 36:46 waited 101 s, 21:10 waited 84 s from the top.
        assertEquals(35 * 60_000L, TsStartPoint.gridMs(36 * 60_000L + 46_000L))
        assertEquals(20 * 60_000L, TsStartPoint.gridMs(21 * 60_000L + 10_000L))
        // Just past a mark the previous one is taken: the keyframe after the mark can't overshoot.
        assertEquals(30 * 60_000L, TsStartPoint.gridMs(35 * 60_000L + 10_000L))
        assertEquals(0L, TsStartPoint.gridMs(-1L))
    }

    @Test
    fun `the head gives the tables, the video PID and the clock's zero`() {
        val head = TsStartPoint.head(ts)!!
        assertEquals(2 * MpegTs.PACKET, head.tables.size)
        assertEquals(0x47.toByte(), head.tables[0])
        assertEquals(0, TsStartPoint.pid(head.tables, 0))
        assertEquals(256, head.videoPid)
        assertEquals(setOf(256, 257), head.mediaPids)
        // ffmpeg starts the clock at 1.4 s + the B-frame delay; the video PES goes first here.
        assertEquals(133_200L, head.firstPts)
    }

    @Test
    fun `no head in bytes that are not a transport stream`() {
        assertNull(TsStartPoint.head(ByteArray(4096) { 0x47 }))
        assertNull(TsStartPoint.head(ByteArray(10)))
        assertNull(locator(ByteArray(4 shl 20)).locate(60_000L))
    }

    @Test
    fun `a keyframe is told without a random access flag, by the NAL units that open it`() {
        val head = TsStartPoint.head(ts)!!
        val start = locator().locate(41_000L)!!
        val packet = ts.copyOfRange(start.byteOffset.toInt(), start.byteOffset.toInt() + MpegTs.PACKET)
        assertTrue(MpegTs.isRandomAccess(packet, 0))
        // The same packet with random_access_indicator cleared: its VPS/SPS still say keyframe.
        packet[5] = (packet[5].toInt() and 0x40.inv()).toByte()
        assertTrue(!MpegTs.isRandomAccess(packet, 0))
        assertNotNull(TsStartPoint.firstKeyframe(packet, head, 0L))
    }

    @Test
    fun `the locator finds the first keyframe at or after the point, in a handful of reads`() {
        val reads = ArrayList<Pair<Long, Int>>()
        val start = locator(reads = reads).locate(61_000L)!!
        // A CRA every 4 s from 0: the first at or after 61 s is at 64 s.
        assertEquals(64_000L, start.startMs)
        assertTrue(MpegTs.isRandomAccess(ts, start.byteOffset.toInt()))
        assertEquals(TsStartPoint.head(ts)!!.tables.toList(), start.tables.toList())
        assertTrue("reads: $reads", reads.size <= 12)
    }

    @Test
    fun `the same point always lands on the same keyframe, wherever the probes fall`() {
        val first = locator().locate(41_000L)!!
        // Another file length (garbage appended past the end) moves every interpolated probe.
        val longer = ts + ByteArray(ts.size / 3) { 0x47 }
        val tsOnly = TsStartLocator(longer.size.toLong(), { offset, size ->
            if (offset >= longer.size) null else longer.copyOfRange(offset.toInt(), minOf(longer.size.toLong(), offset + size).toInt())
        })
        // (no timestamp at all in the last 128 KB of that one: the tail search widens past the padding)
        assertEquals(first.byteOffset, tsOnly.locate(41_000L)!!.byteOffset)
        assertEquals(first.byteOffset, locator().locate(40_500L)!!.byteOffset)
        assertEquals(first.byteOffset, locator().locate(44_000L)!!.byteOffset)
    }

    @Test
    fun `no start past the end, at the top, or when a read fails`() {
        assertNull(locator().locate(0L))
        assertNull(locator().locate(95_000L))
        val failing = TsStartLocator(ts.size.toLong(), { offset, size ->
            if (offset > 0L) null else ts.copyOfRange(0, size)
        })
        assertNull(failing.locate(41_000L))
    }

    /** A copy of [bytes] with each packet from [from] on passed through [edit] (its offset, its PID). */
    private fun rewritten(bytes: ByteArray, from: Int, edit: (ByteArray, Int, Int) -> Unit): ByteArray {
        val out = bytes.copyOf()
        val align = MpegTs.alignment(out)
        var i = align + (maxOf(0, from - align) + MpegTs.PACKET - 1) / MpegTs.PACKET * MpegTs.PACKET
        while (i + MpegTs.PACKET <= out.size) {
            edit(out, i, TsStartPoint.pid(out, i))
            i += MpegTs.PACKET
        }
        return out
    }

    /** A null packet (PID 0x1FFF, payload only): padding, no PES, no PCR. */
    private fun toNull(buf: ByteArray, i: Int) {
        buf[i] = 0x47
        buf[i + 1] = 0x1F
        buf[i + 2] = 0xFF.toByte()
        buf[i + 3] = 0x10
        for (k in 4 until MpegTs.PACKET) buf[i + k] = 0xFF.toByte()
    }

    /** The video packet loses its payload_unit_start: a continuation, no PES header, no PTS. */
    private fun clearVideoStart(buf: ByteArray, i: Int, pid: Int) {
        if (pid == 256) buf[i + 1] = (buf[i + 1].toInt() and 0x40.inv()).toByte()
    }

    @Test
    fun `a tail with audio PES but no video PES start dates the end by the audio`() {
        // The last ~200 KB: video packets still there, but none opens a PES (the measured Xuper tail).
        val bytes = rewritten(ts, ts.size - 200 * 1024) { buf, i, pid -> clearVideoStart(buf, i, pid) }
        val tail = bytes.copyOfRange(bytes.size - TsStartLocator.PROBE_BYTES, bytes.size)
        val head = TsStartPoint.head(bytes)!!
        assertTrue(TsStartPoint.videoPes(tail, head).isEmpty())
        assertEquals(TsStartPoint.ClockSource.OTHER_PES, TsStartPoint.lastClock(tail, head)!!.source)
        val logs = ArrayList<String>()
        val start = TsStartLocator(bytes.size.toLong(), { offset, size ->
            if (offset >= bytes.size) null else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + size).toInt())
        }, { logs += it }).locate(61_000L)!!
        assertEquals(64_000L, start.startMs)
        assertEquals(locator().locate(61_000L)!!.byteOffset, start.byteOffset)
        assertTrue("logs: $logs", logs.any { "end clock: the audio PES" in it })
    }

    @Test
    fun `a tail of padding only widens backwards until a timestamp`() {
        // The last 300 KB are null packets: no PES, no PCR in the 128 KB window.
        val bytes = rewritten(ts, ts.size - 300 * 1024) { buf, i, _ -> toNull(buf, i) }
        val reads = ArrayList<Pair<Long, Int>>()
        val start = locator(bytes, reads).locate(41_000L)!!
        assertEquals(locator().locate(41_000L)!!.byteOffset, start.byteOffset)
        // The tail window, then the widened one up to 1 MiB from the end.
        assertTrue("reads: $reads", reads.any { it.second > TsStartLocator.PROBE_BYTES && it.second < TsStartLocator.SCAN_BYTES })
        // Nothing but padding in 4 MiB: no end clock, a remux from 0:00.
        val padding = ByteArray(6 shl 20).also { var i = 0; while (i + MpegTs.PACKET <= it.size) { toNull(it, i); i += MpegTs.PACKET } }
        assertNull(locator(ts.copyOf(TsStartLocator.HEAD_BYTES) + padding).locate(41_000L))
    }

    @Test
    fun `a probe window without a video PES start is dated by its audio, or widened`() {
        val plain = locator().locate(61_000L)!!
        val head = TsStartPoint.head(ts)!!
        // Every probe read (shorter than a scan read; not the head, not the tail) has its video PES starts cleared.
        fun probeReads(edit: (ByteArray, Int, Int) -> Unit): Pair<TsStart?, List<String>> {
            val logs = ArrayList<String>()
            val start = TsStartLocator(ts.size.toLong(), { offset, size ->
                val buf = ts.copyOfRange(offset.toInt(), minOf(ts.size.toLong(), offset + size).toInt())
                if (offset > 0L && size < TsStartLocator.SCAN_BYTES && offset + size < ts.size) rewritten(buf, 0, edit) else buf
            }, { logs += it }).locate(61_000L)
            return start to logs
        }
        val (byAudio, audioLogs) = probeReads { buf, i, pid -> clearVideoStart(buf, i, pid) }
        assertEquals("logs: $audioLogs", plain.byteOffset, byAudio!!.byteOffset)
        assertTrue("logs: $audioLogs", audioLogs.any { "dated by the audio PES" in it })
        assertTrue("logs: $audioLogs", audioLogs.any { "scanning from byte" in it })
        // A probe window of padding only: the probe widens to 1 MiB, which has the video.
        val (widened, widenLogs) = probeReads { buf, i, _ -> toNull(buf, i) }
        assertEquals("logs: $widenLogs", plain.byteOffset, widened!!.byteOffset)
        assertTrue("logs: $widenLogs", widenLogs.any { "dated by the video PES" in it })
        val probe = ts.copyOfRange(700_000, 700_000 + TsStartLocator.PROBE_BYTES)
        assertEquals(TsStartPoint.ClockSource.VIDEO_PES, TsStartPoint.firstClock(probe, head)!!.source)
    }

    @Test
    fun `timestamps are read across the 33-bit wrap`() {
        assertEquals(90_000L, TsStartPoint.ticksAfter(MpegTs.PCR_WRAP - 45_000L, 45_000L))
    }
}
