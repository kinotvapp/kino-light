package com.arkiv.player.playback

import kotlin.math.max

/**
 * A long progressive MPEG-TS (HEVC + AAC) made up on the fly, byte range by byte range, so a test can
 * point [TsStartLocator] at a title the size of the measured Xuper one (912 MB, ~1h45m) without
 * holding it in memory.
 *
 * The layout: PAT + PMT, then per video frame its video packets, the first opening its PES with a
 * PTS (24 fps), the second an audio PES. A keyframe (HEVC IDR, told by its NAL type only, no
 * random_access_indicator) opens every GOP, whose length varies; frame sizes follow scenes of
 * varying bitrate, so byte and time are not proportional -- what makes the interpolation work.
 * Deterministic for a [seed].
 */
class SyntheticTs(
    durationSec: Int = 6_300,
    targetBytes: Long = 912L * 1000 * 1000,
    seed: Long = 7L,
    /** Each scene's bitrate, as a multiple of the mean (before normalising to [targetBytes]). */
    sceneLevels: ClosedFloatingPointRange<Double> = 0.25..3.0,
    /** The last packets carry no video PES start (the measured Xuper tail): the end is dated by audio. */
    private val audioOnlyTailPackets: Int = 200 * 1024 / MpegTs.PACKET,
) {
    private val frames = durationSec * FPS

    /** How long the title runs, as the phone's player would report it. */
    val durationMs = durationSec * 1000L
    /** First packet of each frame's unit (its video PES start, an audio PES, the rest of the frame); one past the end last. */
    private val unitStart = LongArray(frames + 1)
    private val keyframe = BooleanArray(frames)

    /** The stream's size in bytes. */
    val size: Long

    init {
        val rnd = java.util.Random(seed)
        val weight = DoubleArray(frames)
        var f = 0
        while (f < frames) {
            // A scene: 5-90 s at its own bitrate.
            val scene = minOf(frames - f, (5 + rnd.nextInt(86)) * FPS)
            val level = sceneLevels.start + rnd.nextDouble() * (sceneLevels.endInclusive - sceneLevels.start)
            var gopLeft = 0
            for (k in f until f + scene) {
                if (gopLeft == 0) {
                    keyframe[k] = true
                    gopLeft = (1 + rnd.nextInt(5)) * FPS // GOPs of 1-5 s
                }
                gopLeft--
                weight[k] = level * (if (keyframe[k]) 8.0 else 0.6 + rnd.nextDouble() * 0.8)
            }
            f += scene
        }
        val videoBudget = targetBytes / MpegTs.PACKET - HEADER_PACKETS - frames.toLong()
        val perWeight = videoBudget / weight.sum()
        var at = HEADER_PACKETS.toLong()
        for (k in 0 until frames) {
            unitStart[k] = at
            at += 1 + max(1L, (weight[k] * perWeight).toLong())
        }
        unitStart[frames] = at
        size = at * MpegTs.PACKET
    }

    /** The zero every time is counted from: the first video PES's PTS. */
    val firstPts = 126_000L

    fun ptsOf(frame: Int): Long = firstPts + frame * TICKS_PER_FRAME

    /** The bytes [offset, offset + size) of the stream (fewer at the end), as a ranged read returns them. */
    fun read(offset: Long, size: Int): ByteArray? {
        if (offset < 0 || offset >= this.size) return null
        val n = minOf(size.toLong(), this.size - offset).toInt()
        val out = ByteArray(n)
        var packet = offset / MpegTs.PACKET
        val scratch = ByteArray(MpegTs.PACKET)
        var written = 0
        var skip = (offset - packet * MpegTs.PACKET).toInt()
        var frame = frameOf(packet)
        while (written < n) {
            if (frame < 0 && packet >= HEADER_PACKETS) frame = 0
            while (frame >= 0 && frame < frames - 1 && packet >= unitStart[frame + 1]) frame++
            packetAt(packet, frame, scratch)
            val take = minOf(MpegTs.PACKET - skip, n - written)
            System.arraycopy(scratch, skip, out, written, take)
            written += take
            skip = 0
            packet++
        }
        return out
    }

    /** The first keyframe whose PTS is at or after [ms] past the zero: what a locate must land on. */
    fun keyframeAtOrAfter(ms: Long): Pair<Long, Long>? {
        val ticks = ms * 90
        for (k in 0 until frames) {
            if (keyframe[k] && k * TICKS_PER_FRAME >= ticks) return unitStart[k] * MpegTs.PACKET to k * TICKS_PER_FRAME / 90
        }
        return null
    }

    private fun frameOf(packet: Long): Int {
        if (packet < HEADER_PACKETS) return -1
        var lo = 0
        var hi = frames - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (unitStart[mid] <= packet) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun packetAt(packet: Long, frame: Int, p: ByteArray) {
        p.fill(0xFF.toByte())
        p[0] = 0x47
        when {
            packet == 0L -> pat(p)
            packet == 1L -> pmt(p)
            frame < 0 -> nullPacket(p)
            packet == unitStart[frame] && packet < size / MpegTs.PACKET - audioOnlyTailPackets ->
                pes(p, VIDEO_PID, 0xE0, ptsOf(frame), if (keyframe[frame]) IDR_NAL else TRAIL_NAL)
            packet == unitStart[frame] + 1 -> pes(p, AUDIO_PID, 0xC0, ptsOf(frame) - 2_000L, null)
            else -> continuation(p, VIDEO_PID)
        }
    }

    private fun header(p: ByteArray, pid: Int, start: Boolean) {
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        p[3] = 0x10
    }

    private fun nullPacket(p: ByteArray) = header(p, 0x1FFF, false)

    private fun continuation(p: ByteArray, pid: Int) = header(p, pid, false)

    private fun pes(p: ByteArray, pid: Int, streamId: Int, pts: Long, nal: Int?) {
        header(p, pid, true)
        val h = intArrayOf(0, 0, 1, streamId, 0, 0, 0x80, 0x80, 5)
        h.forEachIndexed { k, v -> p[4 + k] = v.toByte() }
        val w = pts and 0x1FFFFFFFFL
        p[13] = (0x21 or ((w shr 29).toInt() and 0x0E)).toByte()
        p[14] = (w shr 22).toByte()
        p[15] = (((w shr 14).toInt() and 0xFE) or 1).toByte()
        p[16] = (w shr 7).toByte()
        p[17] = (((w shl 1).toInt() and 0xFE) or 1).toByte()
        if (nal != null) {
            p[18] = 0; p[19] = 0; p[20] = 1; p[21] = nal.toByte(); p[22] = 1
        }
    }

    private fun pat(p: ByteArray) {
        header(p, 0, true)
        // pointer, table 0, length 13, ts id 1, version, section 0/0, program 1 → PMT PID 0x1000, CRC
        val s = intArrayOf(0, 0x00, 0xB0, 13, 0, 1, 0xC1, 0, 0, 0, 1, 0xF0, 0x00, 0, 0, 0, 0)
        s.forEachIndexed { k, v -> p[4 + k] = v.toByte() }
    }

    private fun pmt(p: ByteArray) {
        header(p, PMT_PID, true)
        // pointer, table 2, length 23, program 1, version, 0/0, PCR PID, info 0, HEVC 0x100, AAC 0x101, CRC
        val s = intArrayOf(
            0, 0x02, 0xB0, 23, 0, 1, 0xC1, 0, 0, 0xE1, 0x00, 0xF0, 0x00,
            0x24, 0xE1, 0x00, 0xF0, 0x00,
            0x0F, 0xE1, 0x01, 0xF0, 0x00,
            0, 0, 0, 0,
        )
        s.forEachIndexed { k, v -> p[4 + k] = v.toByte() }
    }

    companion object {
        const val FPS = 24
        const val TICKS_PER_FRAME = 90_000L / FPS
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        const val PMT_PID = 0x1000
        private const val HEADER_PACKETS = 2
        /** HEVC NAL headers: IDR_W_RADL (19) and TRAIL_R (1). */
        private const val IDR_NAL = 19 shl 1
        private const val TRAIL_NAL = 1 shl 1
    }
}
