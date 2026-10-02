package com.arkiv.player.playback

/**
 * Where a cast remux that does NOT begin at 0:00 starts reading its MPEG-TS: the byte of a video
 * keyframe ([byteOffset]), the title time that keyframe is shown at ([startMs], the phone player's
 * clock: 0 = the first timestamp in the file) and the stream's PAT + PMT packets ([tables]), which
 * the remux reads first -- a TsExtractor dropped in the middle of a file knows no PIDs until the
 * next PAT/PMT goes by, and the keyframe would go by before it.
 *
 * Why the remux reads from a byte instead of being clipped (`ClippingConfiguration`): media3's
 * fragmented muxer writes no `tfdt` and no edit list, so each track of the output starts at decode
 * time 0 on ITS first sample. Clipped (or simply read from the middle), the first audio sample in
 * the file sits up to half a second before the keyframe the video has to start on, and both land
 * on 0: measured on a test stream at -459 ms, the "sound ahead of the picture" heard on the
 * KALLEY. Reading from the keyframe's own packet with Transformer's
 * `setEnsureFileStartsOnVideoFrameEnabled` drops the audio before it, so both tracks start within
 * one audio frame of each other: -29 and -19 ms in `RemuxMidFileSyncTest`, where the from-the-top
 * remux every cast has used measures -61 ms (the rest is the server's summed `tfdt` against
 * GOPs with more or fewer leading pictures than the first, a frame either way).
 */
class TsStart(val byteOffset: Long, val startMs: Long, val tables: ByteArray) {
    override fun toString(): String = "TsStart(byte=$byteOffset, at=${startMs}ms, tables=${tables.size}B)"
}

/**
 * The pure half of starting a remux mid-file: the grid of start points, and reading PAT/PMT, PES
 * timestamps and keyframes out of raw transport-stream bytes. No I/O, so every edge is pinned on
 * the JVM; [TsStartLocator] does the reading.
 */
object TsStartPoint {

    /**
     * Start points are a grid, not the exact position: every re-cast near the same place lands on
     * the same keyframe, so the same key and the same remux on disk (`RemuxLeftover` reuses what an
     * earlier cast wrote). Five minutes keeps the wait for the phone's position at a fifth of a
     * from-zero remux's at worst (a remux runs ~15x real time: <= 20 s + the lead).
     */
    const val GRID_MS = 300_000L

    /** How far before the phone's position the grid point has to be: the keyframe after the grid point must not overshoot it. */
    const val PREROLL_MS = 30_000L

    /**
     * Where a remux for a cast from [positionMs] begins, on the grid: the last grid point at least
     * [PREROLL_MS] before it. 0 (a remux from the top) when there is none.
     */
    fun gridMs(positionMs: Long): Long {
        val from = positionMs - PREROLL_MS
        if (from < GRID_MS) return 0L
        return from / GRID_MS * GRID_MS
    }

    /** Stream types (ISO 13818-1 / H.222) the start point understands, by codec. */
    private const val STREAM_H264 = 0x1B
    private const val STREAM_HEVC = 0x24

    /** What the first bytes of the file say: its tables, the PIDs and the clock's zero. */
    class Head(
        /** The PAT packet and the PMT packet, as they are in the file. */
        val tables: ByteArray,
        val videoPid: Int,
        val videoStreamType: Int,
        /** Audio and video PIDs: the PES timestamps that set the player's clock. */
        val mediaPids: Set<Int>,
        /** PTS of the first PES on [mediaPids] in byte order: what the phone player calls 0. */
        val firstPts: Long,
    )

    /** A PES start found in a buffer: the packet's offset in it and its PTS. */
    data class Pes(val offset: Int, val pts: Long)

    /**
     * The head of the file ([buf] = its first bytes, from byte 0), or null when it does not hold a
     * PAT, a PMT with a video stream this understands (H.264, HEVC) and a timestamp after them.
     */
    fun head(buf: ByteArray): Head? {
        val start = MpegTs.alignment(buf)
        if (start < 0) return null
        var pat: ByteArray? = null
        var pmtPid = -1
        var pmt: ByteArray? = null
        var videoPid = -1
        var videoType = 0
        val media = HashSet<Int>()
        var i = start
        while (i + MpegTs.PACKET <= buf.size) {
            val pid = pid(buf, i)
            if (pat == null && pid == 0 && pusi(buf, i)) {
                val s = sectionStart(buf, i)
                if (s != null && s + 12 <= i + MpegTs.PACKET) {
                    pmtPid = ((buf[s + 10].toInt() and 0x1F) shl 8) or (buf[s + 11].toInt() and 0xFF)
                    pat = buf.copyOfRange(i, i + MpegTs.PACKET)
                }
            } else if (pat != null && pmt == null && pid == pmtPid && pusi(buf, i)) {
                val s = sectionStart(buf, i) ?: return null
                val sectionLength = ((buf[s + 1].toInt() and 0x0F) shl 8) or (buf[s + 2].toInt() and 0xFF)
                val end = minOf(s + 3 + sectionLength - 4, i + MpegTs.PACKET)
                val infoLength = ((buf[s + 10].toInt() and 0x0F) shl 8) or (buf[s + 11].toInt() and 0xFF)
                var q = s + 12 + infoLength
                while (q + 5 <= end) {
                    val type = buf[q].toInt() and 0xFF
                    val esPid = ((buf[q + 1].toInt() and 0x1F) shl 8) or (buf[q + 2].toInt() and 0xFF)
                    val esInfo = ((buf[q + 3].toInt() and 0x0F) shl 8) or (buf[q + 4].toInt() and 0xFF)
                    if (videoPid < 0 && (type == STREAM_H264 || type == STREAM_HEVC)) {
                        videoPid = esPid
                        videoType = type
                    }
                    media += esPid
                    q += 5 + esInfo
                }
                if (videoPid < 0) return null
                pmt = buf.copyOfRange(i, i + MpegTs.PACKET)
            } else if (pmt != null && pid in media && pusi(buf, i)) {
                val pts = pesPts(buf, i) ?: run { i += MpegTs.PACKET; continue }
                return Head(pat!! + pmt!!, videoPid, videoType, media, pts)
            }
            i += MpegTs.PACKET
        }
        return null
    }

    /** First video PES in [buf] (any frame) and its PTS: where in the title a byte of the file is. */
    fun firstVideoPes(buf: ByteArray, head: Head): Pes? = videoPes(buf, head, first = true).firstOrNull()

    /** Every video PES that starts in [buf], with its PTS, in byte order (only the first with [first]). */
    fun videoPes(buf: ByteArray, head: Head, first: Boolean = false): List<Pes> {
        val start = MpegTs.alignment(buf)
        if (start < 0) return emptyList()
        val out = ArrayList<Pes>()
        var i = start
        while (i + MpegTs.PACKET <= buf.size) {
            if (pid(buf, i) == head.videoPid && pusi(buf, i)) {
                pesPts(buf, i)?.let {
                    out += Pes(i, it)
                    if (first) return out
                }
            }
            i += MpegTs.PACKET
        }
        return out
    }

    /** What dated a byte of the file: a video PES's PTS, else another media PES's (audio), else a PCR. */
    enum class ClockSource(val label: String) {
        VIDEO_PES("video PES"),
        OTHER_PES("audio PES"),
        PCR("PCR"),
    }

    /** A timestamp found in a buffer: the packet's offset in it, the 90 kHz value and what it came from. */
    data class Clock(val offset: Int, val pts: Long, val source: ClockSource)

    /**
     * The first timestamp in [buf]: its first video PES when it has one, else its first other media
     * PES (audio), else its first PCR. A real file's window can hold no video PES start at all -- an
     * audio-only or padded tail past the last frame, or the middle of one big frame -- and the
     * byte-to-time interpolation only needs some clock of the title there, not a video one.
     */
    fun firstClock(buf: ByteArray, head: Head): Clock? = clock(buf, head, last = false)

    /** The last timestamp in [buf], with the same preference as [firstClock]. */
    fun lastClock(buf: ByteArray, head: Head): Clock? = clock(buf, head, last = true)

    private fun clock(buf: ByteArray, head: Head, last: Boolean): Clock? {
        val start = MpegTs.alignment(buf)
        if (start < 0) return null
        var video: Clock? = null
        var other: Clock? = null
        var pcr: Clock? = null
        var i = start
        while (i + MpegTs.PACKET <= buf.size) {
            val pid = pid(buf, i)
            if (pid in head.mediaPids && pusi(buf, i)) {
                val pts = pesPts(buf, i)
                if (pts != null && pid == head.videoPid) {
                    video = Clock(i, pts, ClockSource.VIDEO_PES)
                    if (!last) return video
                } else if (pts != null && (last || other == null)) {
                    other = Clock(i, pts, ClockSource.OTHER_PES)
                }
            }
            if (last || pcr == null) MpegTs.readPcr(buf, i)?.let { pcr = Clock(i, it.base90k, ClockSource.PCR) }
            i += MpegTs.PACKET
        }
        return video ?: other ?: pcr
    }

    /**
     * First keyframe in [buf] whose PTS is at least [minPts] ticks past [head]'s zero (wrap-aware):
     * a video PES that starts in its packet and either declares a random access point or opens
     * with the codec's parameter sets / an IDR-IRAP picture. Null when [buf] has none.
     */
    fun firstKeyframe(buf: ByteArray, head: Head, minPts: Long): Pes? {
        val start = MpegTs.alignment(buf)
        if (start < 0) return null
        var i = start
        while (i + MpegTs.PACKET <= buf.size) {
            if (pid(buf, i) == head.videoPid && pusi(buf, i)) {
                val pts = pesPts(buf, i)
                if (pts != null && ticksAfter(head.firstPts, pts) >= minPts && isKeyframe(buf, i, head.videoStreamType)) {
                    return Pes(i, pts)
                }
            }
            i += MpegTs.PACKET
        }
        return null
    }

    /** Ticks of the 90 kHz clock from [zero] to [pts], across a wrap of the 33-bit counter. */
    fun ticksAfter(zero: Long, pts: Long): Long = MpegTs.deltaTicks(zero, pts)

    private fun isKeyframe(buf: ByteArray, i: Int, streamType: Int): Boolean {
        if (MpegTs.isRandomAccess(buf, i)) return true
        // No random_access_indicator: look at the NAL units that open the PES (this packet only).
        val p = payloadStart(buf, i) ?: return false
        val headerLength = buf[p + 8].toInt() and 0xFF
        var q = p + 9 + headerLength
        val end = i + MpegTs.PACKET
        while (q + 3 < end) {
            if (buf[q].toInt() == 0 && buf[q + 1].toInt() == 0 && buf[q + 2].toInt() == 1) {
                val nal = buf[q + 3].toInt() and 0xFF
                if (streamType == STREAM_HEVC) {
                    val type = (nal shr 1) and 0x3F
                    // VPS/SPS/PPS, or an IRAP picture (BLA/IDR/CRA).
                    if (type in 32..34 || type in 16..21) return true
                    if (type in 0..9) return false
                } else {
                    val type = nal and 0x1F
                    // SPS/PPS or an IDR slice.
                    if (type == 7 || type == 8 || type == 5) return true
                    if (type == 1) return false
                }
                q += 3
            } else {
                q++
            }
        }
        return false
    }

    internal fun pid(buf: ByteArray, i: Int): Int = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)

    internal fun pusi(buf: ByteArray, i: Int): Boolean = buf[i + 1].toInt() and 0x40 != 0

    /** Offset of the packet's payload, or null when it has none. */
    private fun payloadStart(buf: ByteArray, i: Int): Int? {
        val afc = (buf[i + 3].toInt() shr 4) and 0x3
        if (afc != 1 && afc != 3) return null
        val p = if (afc == 3) i + 5 + (buf[i + 4].toInt() and 0xFF) else i + 4
        return p.takeIf { it < i + MpegTs.PACKET }
    }

    /** Start of the PSI section in a packet that opens one (after its pointer_field). */
    private fun sectionStart(buf: ByteArray, i: Int): Int? {
        val p = payloadStart(buf, i) ?: return null
        val s = p + 1 + (buf[p].toInt() and 0xFF)
        return s.takeIf { it + 3 <= i + MpegTs.PACKET }
    }

    /** The PTS of the PES that starts in the packet at [i], or null (no PES header, no PTS). */
    fun pesPts(buf: ByteArray, i: Int): Long? {
        val p = payloadStart(buf, i) ?: return null
        if (p + 14 > i + MpegTs.PACKET) return null
        if (buf[p].toInt() != 0 || buf[p + 1].toInt() != 0 || buf[p + 2].toInt() != 1) return null
        if (buf[p + 7].toInt() and 0x80 == 0) return null
        val b = { k: Int -> buf[p + 9 + k].toLong() and 0xFF }
        return (((b(0) shr 1) and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
    }
}

/**
 * Finds the [TsStart] for a grid point in a remote MPEG-TS of [total] bytes, through [read] (a
 * ranged read: `size` bytes at `offset`, null on failure). A handful of reads, never the file:
 * the head (tables and the clock's zero), the tail (where the clock ends), a few interpolated
 * probes to land a little before the point, then forward to the first keyframe at or after it.
 *
 * Deterministic for a given file and point -- the keyframe found does not depend on where the
 * probes happened to land -- which is what lets a later cast reuse what an earlier one remuxed.
 */
class TsStartLocator(
    private val total: Long,
    private val read: (offset: Long, size: Int) -> ByteArray?,
    private val log: (String) -> Unit = {},
) {
    /** The start for [targetMs] (title time), or null when the stream cannot be read that way. */
    fun locate(targetMs: Long): TsStart? {
        if (targetMs <= 0L || total <= HEAD_BYTES * 2L) return null
        val head = read(0L, HEAD_BYTES)?.let(TsStartPoint::head) ?: return null.also { log("no PAT/PMT/video in the head") }
        val target = targetMs * 90L
        // The clock at the end: the last timestamp of the tail, widening backwards until there is one.
        val end = endClock(head) ?: return null
        var lo = Probe(0L, 0L, TsStartPoint.ClockSource.VIDEO_PES)
        var hi = end
        if (target >= hi.ticks) return null.also { log("the point is past the end") }
        // Interpolate towards a little before the point.
        for (step in 0 until MAX_PROBES) {
            if (target - lo.ticks <= CLOSE_TICKS || hi.offset - lo.offset <= SCAN_BYTES) break
            val guess = (lo.offset + (hi.offset - lo.offset) * ((target - CLOSE_TICKS / 2 - lo.ticks).toDouble() / (hi.ticks - lo.ticks)))
                .toLong().coerceIn(lo.offset + 1, hi.offset - PROBE_BYTES)
            val probe = clockAfter(guess, head) ?: return null
            if (probe.offset >= hi.offset) {
                log("the probe at byte $guess found its first timestamp past the upper bound: interpolation stops")
                break
            }
            if (probe.ticks <= target) lo = probe else hi = probe
        }
        // Forward from there to the first keyframe at or after the point. A probe dated by audio or a
        // PCR is not where the video of that time is muxed (it can run a little ahead): start the scan
        // some seconds' worth of bytes earlier, so the keyframe cannot sit behind it.
        var at = lo.offset
        if (lo.source != TsStartPoint.ClockSource.VIDEO_PES) {
            at = (lo.offset - (INEXACT_SLACK_TICKS.toDouble() * end.offset / end.ticks).toLong()).coerceAtLeast(0L)
            log("the last probe was dated by the ${lo.source.label}: scanning from byte $at, ${lo.offset - at}B before it")
        }
        var scanned = 0L
        while (scanned < MAX_SCAN_BYTES && at < total) {
            val n = minOf(SCAN_BYTES.toLong(), total - at).toInt()
            val buf = read(at, n) ?: return null
            TsStartPoint.firstKeyframe(buf, head, target)?.let { k ->
                val byte = at + k.offset
                val ms = TsStartPoint.ticksAfter(head.firstPts, k.pts) / 90L
                log("keyframe for ${targetMs}ms at ${ms}ms (byte $byte, ${scanned + k.offset}B scanned past the last probe)")
                return TsStart(byte, ms, head.tables)
            }
            // Re-read the last packet's worth so a packet cut by the edge is seen whole.
            at += (n - MpegTs.PACKET).coerceAtLeast(1)
            scanned += n
        }
        log("no keyframe within ${MAX_SCAN_BYTES}B after the point")
        return null
    }

    /**
     * The last timestamp of the file: the tail window ([WIDEN_BYTES]`[0]`), then further back while
     * a window holds none. Measured on a Xuper title (911 MB, HEVC+AAC) the last 128 KB held no video
     * PES start; any clock works there, it only bounds the interpolation.
     */
    private fun endClock(head: TsStartPoint.Head): Probe? {
        var regionEnd = total
        for (span in WIDEN_BYTES) {
            val regionStart = (total - span).coerceAtLeast(HEAD_BYTES.toLong())
            if (regionStart >= regionEnd) continue
            val buf = read(regionStart, (regionEnd - regionStart).toInt())
                ?: return null.also { log("the tail read at byte $regionStart failed") }
            TsStartPoint.lastClock(buf, head)?.let { c ->
                val probe = Probe(regionStart + c.offset, ticksOf(head, c), c.source)
                if (c.source != TsStartPoint.ClockSource.VIDEO_PES || span > WIDEN_BYTES[0]) {
                    log("end clock: the ${c.source.label} ${total - probe.offset}B before the end (looked through the last ${span}B)")
                }
                return probe
            }
            // Keep a packet's worth of overlap so a packet cut by the edge is seen whole.
            regionEnd = regionStart + MpegTs.PACKET - 1
        }
        log("no timestamp in the last ${WIDEN_BYTES.last()}B")
        return null
    }

    /** The first timestamp at or after [from]: a [PROBE_BYTES] window, widened forwards while it holds none. */
    private fun clockAfter(from: Long, head: TsStartPoint.Head): Probe? {
        var regionStart = from
        for (span in WIDEN_BYTES) {
            val regionEnd = minOf(from + span, total)
            if (regionEnd - regionStart < MpegTs.PACKET) break
            val buf = read(regionStart, (regionEnd - regionStart).toInt())
                ?: return null.also { log("the probe read at byte $regionStart failed") }
            TsStartPoint.firstClock(buf, head)?.let { c ->
                val probe = Probe(regionStart + c.offset, ticksOf(head, c), c.source)
                if (c.source != TsStartPoint.ClockSource.VIDEO_PES || span > WIDEN_BYTES[0]) {
                    log("probe at byte $from: dated by the ${c.source.label} ${probe.offset - from}B after it")
                }
                return probe
            }
            regionStart = regionEnd - (MpegTs.PACKET - 1)
        }
        log("no timestamp within ${WIDEN_BYTES.last()}B after byte $from")
        return null
    }

    /**
     * [clock]'s ticks past the head's zero. An audio PTS or a PCR just behind the zero (a PCR runs
     * ahead of the PTS it paces) would wrap to ~26 h: it reads as the zero instead.
     */
    private fun ticksOf(head: TsStartPoint.Head, clock: TsStartPoint.Clock): Long {
        val ticks = TsStartPoint.ticksAfter(head.firstPts, clock.pts)
        return if (clock.source != TsStartPoint.ClockSource.VIDEO_PES && ticks > MpegTs.PCR_WRAP / 2) 0L else ticks
    }

    private data class Probe(val offset: Long, val ticks: Long, val source: TsStartPoint.ClockSource)

    companion object {
        /**
         * The windows a timestamp is looked for in, widening while one holds none: the probe size,
         * then 1 MiB, then 4 MiB (as the duration's tail search, [TsTailPcrExtractor]).
         */
        val WIDEN_BYTES = longArrayOf(PROBE_BYTES.toLong(), 1L shl 20, 4L shl 20)

        /** How far behind an audio- or PCR-dated probe the keyframe scan starts (ticks of 90 kHz: 3 s). */
        const val INEXACT_SLACK_TICKS = 3L * 90_000

        /** The head read: PAT and PMT go by every ~100 ms, so a few hundred KB always hold both. */
        const val HEAD_BYTES = 512 * 1024
        const val PROBE_BYTES = 128 * 1024
        /** One forward read while looking for the keyframe: a GOP of a ~1.5 Mbps title. */
        const val SCAN_BYTES = 1024 * 1024
        /** Past this the stream has no keyframe the start point can use (or the probes were far off). */
        const val MAX_SCAN_BYTES = 24L * 1024 * 1024
        const val MAX_PROBES = 8
        /** Interpolation stops once a probe lands within this much before the point (ticks of 90 kHz: 10 s). */
        const val CLOSE_TICKS = 10L * 90_000
    }
}
