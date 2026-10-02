package com.arkiv.player.playback

import kotlin.math.abs

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
 * What locating start points in one title has taught ([TsStartLocator]), kept per title so the next
 * locate there -- a re-cast, another audio, a seek back, another grid point -- skips the reads it
 * already paid for: the file's size, its head (tables, PIDs, the clock's zero), the clock at its
 * end, every (byte, time) a probe dated, and the keyframe each grid point landed on. A grid point
 * found before costs no read at all; another one starts from the nearest dated bytes instead of the
 * whole file. Immutable; [with] makes the next one. Stored next to the remux cache
 * (`TsRemuxer`), [write]/[read] its compact binary form.
 */
class TsStartIndex(
    val total: Long,
    val head: TsStartPoint.Head,
    val end: Sample,
    samples: List<Sample>,
    val starts: Map<Long, Found>,
) {
    /** A dated byte of the file: [ticks] of 90 kHz past the head's zero, and what dated it. */
    data class Sample(val offset: Long, val ticks: Long, val source: TsStartPoint.ClockSource)

    /** The keyframe a grid point landed on: its byte and its time. */
    data class Found(val byte: Long, val ms: Long)

    /** In byte order, one per offset, at most [MAX_SAMPLES] (evenly thinned past that). */
    val samples: List<Sample> = samples.distinctBy { it.offset }.sortedBy { it.offset }.let { all ->
        if (all.size <= MAX_SAMPLES) all else List(MAX_SAMPLES) { all[it * all.size / MAX_SAMPLES] }
    }

    /** The start [targetMs] landed on before, or null. */
    fun startFor(targetMs: Long): TsStart? = starts[targetMs]?.let { TsStart(it.byte, it.ms, head.tables) }

    /** This index plus [learned] samples and, when given, the keyframe [targetMs] landed on. */
    fun with(learned: List<Sample>, targetMs: Long = 0L, found: Found? = null): TsStartIndex =
        TsStartIndex(total, head, end, samples + learned, if (found == null) starts else starts + (targetMs to found))

    fun write(out: java.io.DataOutputStream) {
        out.writeInt(VERSION)
        out.writeLong(total)
        out.writeInt(head.tables.size)
        out.write(head.tables)
        out.writeInt(head.videoPid)
        out.writeInt(head.videoStreamType)
        out.writeInt(head.mediaPids.size)
        head.mediaPids.forEach(out::writeInt)
        out.writeLong(head.firstPts)
        writeSample(out, end)
        out.writeInt(samples.size)
        samples.forEach { writeSample(out, it) }
        out.writeInt(starts.size)
        starts.forEach { (ms, f) -> out.writeLong(ms); out.writeLong(f.byte); out.writeLong(f.ms) }
    }

    companion object {
        private const val VERSION = 1
        const val MAX_SAMPLES = 256
        private const val MAX_TABLES = 64 * 1024

        private fun writeSample(out: java.io.DataOutputStream, s: Sample) {
            out.writeLong(s.offset)
            out.writeLong(s.ticks)
            out.writeByte(s.source.ordinal)
        }

        private fun readSample(input: java.io.DataInputStream): Sample =
            Sample(input.readLong(), input.readLong(), TsStartPoint.ClockSource.entries[input.readUnsignedByte()])

        /** What [write] wrote, or null for anything else (another version, a truncated file). */
        fun read(input: java.io.DataInputStream): TsStartIndex? = runCatching {
            if (input.readInt() != VERSION) return null
            val total = input.readLong()
            val tables = ByteArray(input.readInt().also { require(it in 1..MAX_TABLES) }).also(input::readFully)
            val videoPid = input.readInt()
            val videoType = input.readInt()
            val media = HashSet<Int>().apply { repeat(input.readInt().also { require(it in 1..64) }) { add(input.readInt()) } }
            val head = TsStartPoint.Head(tables, videoPid, videoType, media, input.readLong())
            val end = readSample(input)
            val samples = List(input.readInt().also { require(it in 0..MAX_SAMPLES) }) { readSample(input) }
            val starts = HashMap<Long, Found>()
            repeat(input.readInt().also { require(it in 0..10_000) }) { starts[input.readLong()] = Found(input.readLong(), input.readLong()) }
            TsStartIndex(total, head, end, samples, starts)
        }.getOrNull()
    }
}

/**
 * Finds the [TsStart] for a grid point in a remote MPEG-TS of [total] bytes, through [read] (a
 * ranged read: `size` bytes at `offset`, null on failure; called from two threads at once for the
 * head and the tail). A handful of reads, never the file: the head (tables and the clock's zero)
 * and the tail (where the clock ends) in parallel, a few interpolated probes to land a little
 * before the point, then forward to the first keyframe at or after it.
 *
 * Every read costs a round trip to a CDN that can take 1-3 s to answer (a Xuper title cast from
 * 36 min: 24 s in 8 reads), so the reads are what is saved, not the bytes: once the bracket is
 * tight the probe reads a window long enough to hold the keyframe too, and the scan starts where
 * that window ended. What it learns goes to [index] (seeded from [known], the title's earlier
 * locates): a point located before costs no read, another starts from the nearest dated bytes.
 * [stats] counts the reads and times each phase.
 *
 * Deterministic for a given file and point -- the keyframe found does not depend on where the
 * probes happened to land, nor on what an index held -- which is what lets a later cast reuse what
 * an earlier one remuxed.
 */
class TsStartLocator(
    private val total: Long,
    private val read: (offset: Long, size: Int) -> ByteArray?,
    private val log: (String) -> Unit = {},
    known: TsStartIndex? = null,
    /**
     * How long the title runs (ms), when the phone's player knows: the first probes then go out with
     * the head and the tail instead of a round trip after them. 0 = unknown.
     */
    private val durationHintMs: Long = 0L,
    /** Probes read at once in a round. */
    private val parallelReads: Int = PARALLEL_READS,
    /** Probes read with the head and the tail when [durationHintMs] is known. */
    private val earlyProbes: Int = EARLY_PROBES,
) {
    /** What this title's locates know, [known] plus what [locate] learned; null until a head and an end were read. */
    @Volatile var index: TsStartIndex? = known?.takeIf { it.total == total }
        private set

    val stats = Stats()

    /** Reads asked for, bytes they returned, and the time each phase took. */
    class Stats {
        private val requestCount = java.util.concurrent.atomic.AtomicInteger()
        private val byteCount = java.util.concurrent.atomic.AtomicLong()
        val requests: Int get() = requestCount.get()
        val bytes: Long get() = byteCount.get()
        var endsMs = 0L; internal set
        var probes = 0; internal set
        var probesMs = 0L; internal set
        var scanMs = 0L; internal set
        /** Answered from the index without a read. */
        var cached = false; internal set

        internal fun count(n: Int) { requestCount.incrementAndGet(); byteCount.addAndGet(n.toLong()) }

        /** The time of each phase, for a log line. */
        fun phases(): String =
            if (cached) "from the title's index" else "head+tail ${endsMs}ms, $probes probe(s) ${probesMs}ms, scan ${scanMs}ms"

        override fun toString(): String = "$requests read(s), ${bytes / 1024}KB · ${phases()}"
    }

    private fun fetch(offset: Long, size: Int): ByteArray? = read(offset, size).also { stats.count(it?.size ?: 0) }

    /** The start for [targetMs] (title time), or null when the stream cannot be read that way. */
    fun locate(targetMs: Long): TsStart? {
        if (targetMs <= 0L || total <= HEAD_BYTES * 2L) return null
        index?.startFor(targetMs)?.let { stats.cached = true; return it }
        val target = targetMs * 90L
        var t = System.currentTimeMillis()
        // Without an index the head and the tail are read first; with the title's duration known
        // (the phone's player has it) the first probes go out with them, aimed by bytes per second.
        var early: List<Pair<Long, ByteArray?>> = emptyList()
        val known = index ?: run {
            val offsets = earlyOffsets(target)
            val (fresh, bufs) = ends(offsets) ?: return null
            early = offsets.zip(bufs)
            fresh
        }.also { index = it }
        stats.endsMs = System.currentTimeMillis() - t
        val head = known.head
        val end = known.end
        if (target >= end.ticks) return null.also { log("the point is past the end") }
        // Anything dated at or below this is before the point's keyframe (see GUARD_TICKS).
        val below = target - GUARD_TICKS
        val dated = ArrayList(known.samples + Sample(0L, 0L, TsStartPoint.ClockSource.VIDEO_PES) + end)
        val learned = ArrayList<Sample>()
        fun date(s: Sample) { learned += s; dated += s }
        early.forEach { (from, buf) -> probeAt(from, PROBE_BYTES, head, buf)?.let { p -> date(p.first); p.last?.let { date(it) } } }
        var lo = dated[0]
        var hi = end
        fun bracket() {
            lo = dated.filter { it.ticks <= below }.maxBy { it.offset }
            hi = dated.filter { it.offset > lo.offset && it.ticks > below }.minByOrNull { it.offset } ?: end
        }
        // Where the scan picks up when the last window was scanned already (past lo), or -1.
        var scanFrom = -1L
        t = System.currentTimeMillis()
        rounds@ for (round in 0 until MAX_ROUNDS) {
            bracket()
            if (target - lo.ticks <= CLOSE_TICKS || hi.offset - lo.offset <= SCAN_BYTES) break
            // Interpolate towards a little before the point from the dated byte nearest it, at the
            // rate between the two nearest (a secant: the local rate, not the whole bracket's), kept
            // inside the bracket; how far that can miss grows with the distance interpolated.
            val aim = target - LEAD_TICKS
            val (anchor, rate) = secant(dated, lo, hi, aim)
            val miss = maxOf(MIN_MISS_TICKS, (abs(aim - anchor.ticks) * MISS_FRACTION).toLong())
            fun byteAt(ticks: Long): Long = (anchor.offset + rate * (ticks - anchor.ticks)).toLong()
                .coerceIn(lo.offset + 1, hi.offset - PROBE_BYTES)
            val windowBytes = rate * (miss + LEAD_TICKS + REACH_TICKS)
            if (windowBytes > MAX_WINDOW_BYTES) {
                // Too far to land on the keyframe yet: date a few bytes around the aim at once, which
                // brackets the point closely (or extrapolates from nearer) for the next round.
                val step = 2.0 * miss / parallelReads
                val offsets = (0 until parallelReads).map { j -> byteAt(aim + ((j - (parallelReads - 1) / 2.0) * step).toLong()) }
                val probes = probesAt(offsets.distinct(), PROBE_BYTES, head) ?: return null.also { learn(learned) }
                probes.forEach { p -> date(p.first); p.last?.let { date(it) } }
                continue
            }
            // Close enough: one window from before the worst miss to past the point holds the keyframe.
            val from = byteAt(aim - miss)
            val probe = probesAt(listOf(from), windowBytes.toLong().coerceIn(PROBE_BYTES.toLong(), total - from).toInt(), head)
                ?.single() ?: return null.also { learn(learned) }
            date(probe.first)
            probe.last?.let { date(it) }
            if (probe.first.offset >= hi.offset) {
                log("the probe at byte $from found its first timestamp past the upper bound: interpolation stops")
                break
            }
            if (probe.first.ticks > below || probe.first.source != TsStartPoint.ClockSource.VIDEO_PES) continue
            lo = probe.first
            // The window from its first video PES on: the first keyframe at or after the point in it
            // is the one a scan from there would find.
            TsStartPoint.firstKeyframe(probe.buf, head, target)?.let { k ->
                stats.probesMs = System.currentTimeMillis() - t
                return found(targetMs, probe.start + k.offset, k.pts, head, learned, "in the window at byte ${probe.start}")
            }
            // None in it, but the point went by inside it or right after: the keyframe is further on,
            // and the scan goes on from where the window ended.
            val last = probe.last ?: lo
            if (last.ticks > below || target - last.ticks <= CLOSE_TICKS) {
                scanFrom = probe.start + probe.buf.size - MpegTs.PACKET
                if (last.ticks <= below) lo = last
                break@rounds
            }
        }
        stats.probesMs = System.currentTimeMillis() - t
        t = System.currentTimeMillis()
        // Forward from there to the first keyframe at or after the point. A probe dated by audio or a
        // PCR is not where the video of that time is muxed (it can run a little ahead): start the scan
        // some seconds' worth of bytes earlier, so the keyframe cannot sit behind it.
        var at = if (scanFrom >= 0L) scanFrom else lo.offset
        if (scanFrom < 0L && lo.source != TsStartPoint.ClockSource.VIDEO_PES) {
            at = (lo.offset - (INEXACT_SLACK_TICKS.toDouble() * end.offset / end.ticks).toLong()).coerceAtLeast(0L)
            log("the last probe was dated by the ${lo.source.label}: scanning from byte $at, ${lo.offset - at}B before it")
        }
        val scanStart = at
        var scanned = 0L
        // The first read reaches past the point by a GOP at the bracket's rate (with room for a scene
        // heavier than that); a keyframe further away gets longer reads, not more of them.
        val rate = (hi.offset - lo.offset).toDouble() / maxOf(1L, hi.ticks - lo.ticks)
        var chunk = (SCAN_SAFETY * rate * (target - lo.ticks + REACH_TICKS)).toLong()
            .coerceIn(SCAN_BYTES.toLong(), MAX_CHUNK_BYTES.toLong())
        while (scanned < MAX_SCAN_BYTES && at < total) {
            val n = minOf(chunk, total - at, MAX_SCAN_BYTES - scanned).toInt()
            val buf = fetch(at, n) ?: return null.also { learn(learned) }
            TsStartPoint.firstKeyframe(buf, head, target)?.let { k ->
                stats.scanMs = System.currentTimeMillis() - t
                return found(targetMs, at + k.offset, k.pts, head, learned, "${at + k.offset - scanStart}B scanned from byte $scanStart")
            }
            // Re-read the last packet's worth so a packet cut by the edge is seen whole.
            at += (n - MpegTs.PACKET).coerceAtLeast(1)
            scanned += n
            chunk = minOf(chunk * 2, MAX_CHUNK_BYTES.toLong())
        }
        stats.scanMs = System.currentTimeMillis() - t
        learn(learned)
        log("no keyframe within ${MAX_SCAN_BYTES}B after the point")
        return null
    }

    /**
     * Where to interpolate from towards [aim], and at what rate (bytes per tick): the dated video
     * byte nearest [aim] in time, at the rate between it and the next nearest one at least
     * [MIN_SLOPE_TICKS] apart; the bracket's end nearest [aim] at the bracket's rate when there is
     * no such pair, or its rate is not positive.
     */
    private fun secant(dated: List<Sample>, lo: Sample, hi: Sample, aim: Long): Pair<Sample, Double> {
        val bracket = (if (aim - lo.ticks <= hi.ticks - aim) lo else hi) to (hi.offset - lo.offset).toDouble() / (hi.ticks - lo.ticks)
        val video = dated.filter { it.source == TsStartPoint.ClockSource.VIDEO_PES }.sortedBy { abs(it.ticks - aim) }
        val a = video.firstOrNull() ?: return bracket
        val b = video.firstOrNull { abs(it.ticks - a.ticks) >= MIN_SLOPE_TICKS } ?: return bracket
        val rate = (b.offset - a.offset).toDouble() / (b.ticks - a.ticks)
        return if (rate > 0.0 && a.offset in lo.offset..hi.offset) a to rate else bracket
    }

    private fun found(targetMs: Long, byte: Long, pts: Long, head: TsStartPoint.Head, learned: List<Sample>, where: String): TsStart {
        val ticks = TsStartPoint.ticksAfter(head.firstPts, pts)
        val ms = ticks / 90L
        index = index?.with(learned + Sample(byte, ticks, TsStartPoint.ClockSource.VIDEO_PES), targetMs, TsStartIndex.Found(byte, ms))
        log("keyframe for ${targetMs}ms at ${ms}ms (byte $byte, $where)")
        return TsStart(byte, ms, head.tables)
    }

    private fun learn(learned: List<Sample>) {
        if (learned.isNotEmpty()) index = index?.with(learned)
    }

    /**
     * The head and the tail, read at once: the size is known before either, so neither waits for
     * the other's round trip. The tail is only parsed with the head's PIDs.
     */
    /**
     * The head and the tail, read at once -- the size is known before either, so neither waits for
     * the other's round trip -- and [extra] [PROBE_BYTES] probes with them. The tail is only parsed
     * with the head's PIDs. The new index and the probes' bytes (null for a failed one), or null.
     */
    private fun ends(extra: List<Long>): Pair<TsStartIndex, List<ByteArray?>>? {
        val tailStart = (total - WIDEN_BYTES[0]).coerceAtLeast(HEAD_BYTES.toLong())
        stats.probes += extra.size
        val reads = inParallel(
            listOf({ fetch(0L, HEAD_BYTES) }, { fetch(tailStart, (total - tailStart).toInt()) }) +
                extra.map { from -> { fetch(from, PROBE_BYTES) } },
        )
        val head = reads[0]?.let(TsStartPoint::head) ?: return null.also { log("no PAT/PMT/video in the head") }
        val end = endClock(head, tailStart, reads[1]) ?: return null
        return TsStartIndex(total, head, end, emptyList(), emptyMap()) to reads.drop(2)
    }

    /**
     * Where the first probes go when the title's duration is known ([durationHintMs]): around a
     * little before [target], at the file's mean bytes per second, as far apart as that can miss.
     * None without a duration.
     */
    private fun earlyOffsets(target: Long): List<Long> {
        val duration = durationHintMs * 90L
        if (duration <= target || earlyProbes <= 0) return emptyList()
        val rate = total.toDouble() / duration
        val aim = target - LEAD_TICKS
        val miss = maxOf(MIN_MISS_TICKS, (aim * MISS_FRACTION).toLong())
        val step = EARLY_SPREAD * miss / earlyProbes
        val from = HEAD_BYTES.toLong()
        val until = total - WIDEN_BYTES[0] - PROBE_BYTES
        if (until <= from) return emptyList()
        return (0 until earlyProbes).map { j ->
            (rate * (aim + (j - (earlyProbes - 1) / 2.0) * step)).toLong().coerceIn(from, until)
        }.distinct()
    }

    /**
     * The last timestamp of the file: the tail window ([WIDEN_BYTES]`[0]`, already read as [first]
     * from [firstStart]), then further back while a window holds none. Measured on a Xuper title
     * (911 MB, HEVC+AAC) the last 128 KB held no video PES start; any clock works there, it only
     * bounds the interpolation.
     */
    private fun endClock(head: TsStartPoint.Head, firstStart: Long, first: ByteArray?): Sample? {
        var regionEnd = total
        for ((k, span) in WIDEN_BYTES.withIndex()) {
            val regionStart = (total - span).coerceAtLeast(HEAD_BYTES.toLong())
            if (regionStart >= regionEnd) continue
            val buf = (if (k == 0 && regionStart == firstStart) first else fetch(regionStart, (regionEnd - regionStart).toInt()))
                ?: return null.also { log("the tail read at byte $regionStart failed") }
            TsStartPoint.lastClock(buf, head)?.let { c ->
                val probe = Sample(regionStart + c.offset, ticksOf(head, c), c.source)
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

    /** A probe read: the bytes from [start], their first timestamp and their last video PES after it. */
    private class Probe(val start: Long, val buf: ByteArray, val first: Sample, val last: Sample?)

    /**
     * A [window] read at each of [offsets] (at once: each costs a round trip, not the bytes), dated by
     * its first timestamp; one that holds none is widened forwards (1 MiB, 4 MiB). Null when a read
     * fails or a window has no timestamp even widened.
     */
    private fun probesAt(offsets: List<Long>, window: Int, head: TsStartPoint.Head): List<Probe>? {
        stats.probes += offsets.size
        val first = inParallel(offsets.map { from -> { fetch(from, minOf(window.toLong(), total - from).toInt()) } })
        return offsets.mapIndexed { k, from -> probeAt(from, window, head, first[k]) ?: return null }
    }

    private fun probeAt(from: Long, window: Int, head: TsStartPoint.Head, firstRead: ByteArray?): Probe? {
        var regionStart = from
        for ((k, span) in (longArrayOf(window.toLong()) + WIDEN_BYTES.filter { it > window }).withIndex()) {
            val regionEnd = minOf(from + span, total)
            if (regionEnd - regionStart < MpegTs.PACKET) break
            val buf = (if (k == 0) firstRead else fetch(regionStart, (regionEnd - regionStart).toInt()))
                ?: return null.also { log("the probe read at byte $regionStart failed") }
            TsStartPoint.firstClock(buf, head)?.let { c ->
                val first = Sample(regionStart + c.offset, ticksOf(head, c), c.source)
                if (c.source != TsStartPoint.ClockSource.VIDEO_PES || k > 0) {
                    log("probe at byte $from: dated by the ${c.source.label} ${first.offset - from}B after it")
                }
                val last = TsStartPoint.videoPes(buf, head).lastOrNull()
                    ?.takeIf { regionStart + it.offset > first.offset }
                    ?.let { Sample(regionStart + it.offset, TsStartPoint.ticksAfter(head.firstPts, it.pts), TsStartPoint.ClockSource.VIDEO_PES) }
                return Probe(regionStart, buf, first, last)
            }
            regionStart = regionEnd - (MpegTs.PACKET - 1)
        }
        log("no timestamp within ${WIDEN_BYTES.last()}B after byte $from")
        return null
    }

    /**
     * Runs [reads] at once, the first on this thread and the rest on their own, and returns their
     * results in order (null for one that threw). An interrupt of this thread cancels the others.
     */
    private fun inParallel(reads: List<() -> ByteArray?>): List<ByteArray?> {
        if (reads.size == 1) return listOf(reads[0]())
        val others = reads.drop(1).map { r ->
            java.util.concurrent.FutureTask { r() }.also { Thread(it, "ts-start-read").apply { isDaemon = true }.start() }
        }
        try {
            val mine = reads[0]()
            return listOf(mine) + others.map { f ->
                try { f.get() } catch (e: java.util.concurrent.ExecutionException) { null }
            }
        } catch (e: Throwable) {
            others.forEach { it.cancel(true) }
            throw e
        }
    }

    /**
     * [clock]'s ticks past the head's zero. An audio PTS or a PCR just behind the zero (a PCR runs
     * ahead of the PTS it paces) would wrap to ~26 h: it reads as the zero instead.
     */
    private fun ticksOf(head: TsStartPoint.Head, clock: TsStartPoint.Clock): Long {
        val ticks = TsStartPoint.ticksAfter(head.firstPts, clock.pts)
        return if (clock.source != TsStartPoint.ClockSource.VIDEO_PES && ticks > MpegTs.PCR_WRAP / 2) 0L else ticks
    }

    companion object {
        /**
         * The windows a timestamp is looked for in, widening while one holds none: the tail's
         * window, then 1 MiB, then 4 MiB (as the duration's tail search, [TsTailPcrExtractor]).
         */
        val WIDEN_BYTES = longArrayOf(128L * 1024, 1L shl 20, 4L shl 20)

        /** How far behind an audio- or PCR-dated probe the keyframe scan starts (ticks of 90 kHz: 3 s). */
        const val INEXACT_SLACK_TICKS = 3L * 90_000

        /** The head read: PAT and PMT go by every ~100 ms, so a few hundred KB always hold both. */
        const val HEAD_BYTES = 512 * 1024
        /** A probe while the bracket is wide: only its first timestamp is wanted. */
        const val PROBE_BYTES = 64 * 1024
        /** The first forward read while looking for the keyframe: a GOP of a ~1.5 Mbps title. */
        const val SCAN_BYTES = 1024 * 1024
        /** Forward reads double up to this. */
        const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
        /** Past this the stream has no keyframe the start point can use (or the probes were far off). */
        const val MAX_SCAN_BYTES = 24L * 1024 * 1024
        /** Interpolation stops once a probe lands within this much before the point (ticks of 90 kHz: 10 s). */
        const val CLOSE_TICKS = 10L * 90_000
        /** Probes aim this far before the point (5 s), so the scan after them only goes forward. */
        const val LEAD_TICKS = 4L * 90_000
        /**
         * A probe dated within this of the point (1 s) counts as past it: a frame muxed after the
         * keyframe can show before it (B-frames), and the bracket's low end must be before the keyframe.
         */
        const val GUARD_TICKS = 90_000L
        /** How far past the point a window or the first scan read reaches (8 s): a GOP and some. */
        const val REACH_TICKS = 6L * 90_000
        /** The longest window read to land on the keyframe at once; a longer one is probed for first. */
        const val MAX_WINDOW_BYTES = 3145728
        /** How far an interpolation can miss, as a share of how far it reaches from a dated byte. */
        const val MISS_FRACTION = 0.1
        /** ...and at least (3 s). */
        const val MIN_MISS_TICKS = 3L * 90_000
        /** Reads at once in a round (the CDN serves several connections to one file side by side). */
        const val PARALLEL_READS = 1
        /** Probes that go out with the head and the tail when the duration is known. */
        const val EARLY_PROBES = 2
        /** How far apart they go, as a share of how far the first interpolation can miss. */
        const val EARLY_SPREAD = 1.0
        /** Two dated bytes closer than this in time (10 s) do not measure a rate. */
        const val MIN_SLOPE_TICKS = 10L * 90_000
        /** Rounds of probing at most (each one round trip). */
        const val MAX_ROUNDS = 8
        /** The first scan read assumes the scene may be this much heavier than the bracket's average. */
        const val SCAN_SAFETY = 1.0
    }
}

private typealias Sample = TsStartIndex.Sample
