package com.arkiv.player.playback

/**
 * Index of a fragmented MP4 that may still be GROWING, and the rewrite that lets each of its
 * fragments be served on its own as an HLS segment. See [RemuxHlsServer] for why it is served
 * that way at all.
 *
 * Why the moofs are REWRITTEN on the way out: media3's `FragmentedMp4Writer` (the remux's muxer)
 * writes `traf = tfhd + trun` and nothing else -- no `tfdt`, checked against media3-muxer 1.11.1's
 * `Boxes` -- and its `tfhd` carries an absolute `base_data_offset` into the file. A progressive
 * reader walks the file from the top and does not care. A segment player (MSE) fed one fragment at
 * a time places a fragment with no `tfdt` at time zero, and resolves an absolute file offset
 * against a segment that starts somewhere else. So every served fragment gets a `tfdt` (its
 * track's decode time: the durations of every earlier fragment, summed), its `tfhd` switched to
 * default-base-is-moof, and its `trun.data_offset` moved by however much the moof grew. The
 * samples are never touched.
 *
 * Pure: reads through a callback, so it is tested on the JVM against files written by the very
 * muxer the remux uses.
 */
class Fmp4Index {

    data class Track(
        val id: Int,
        /** `vide`, `soun`, ... from the track's `hdlr`. */
        val handler: String,
        val timescale: Long,
        /** Four-cc of the first sample entry: `hvc1`, `avc1`, `mp4a`, ... */
        val sampleEntry: String,
        /** RFC 6381 codec string, or null when the sample entry is one this does not know. */
        val codec: String?,
        val width: Int = 0,
        val height: Int = 0,
        /** `trex.default_sample_duration`, the last fallback for a run that states none. */
        val defaultSampleDuration: Long = 0,
    )

    /** One track's share of a fragment: how long it lasts and when it starts, in its timescale. */
    data class Traf(val trackId: Int, val durationTicks: Long, val baseDecodeTime: Long)

    /** One `moof` + `mdat`, complete on disk. [end] is exclusive. */
    data class Fragment(
        val start: Long,
        val moofSize: Int,
        val end: Long,
        val trafs: List<Traf>,
        val durationSec: Double,
    ) {
        val size: Long get() = end - start
    }

    /** End of the `moov`: bytes [0, initEnd) are the HLS init segment. -1 until it is on disk. */
    @Volatile var initEnd: Long = -1L
        private set

    @Volatile var tracks: List<Track> = emptyList()
        private set

    private val fragmentList = ArrayList<Fragment>()

    /** Fragments found so far, in file order. A snapshot: the list keeps growing behind it. */
    val fragments: List<Fragment>
        @Synchronized get() = ArrayList(fragmentList)

    /** Where the next unread box starts. */
    @Volatile var scannedTo: Long = 0L
        private set

    private val nextBaseTime = HashMap<Int, Long>()

    @Synchronized
    fun reset() {
        initEnd = -1L
        tracks = emptyList()
        fragmentList.clear()
        scannedTo = 0L
        nextBaseTime.clear()
    }

    /**
     * Reads whatever was appended since the last call. [read] returns up to `size` bytes at
     * `offset` (fewer only at the end of the file). Only boxes complete within [length] are taken:
     * a fragment counts once its whole `mdat` is on disk, never before.
     */
    @Synchronized
    fun update(length: Long, read: (offset: Long, size: Int) -> ByteArray) {
        while (scannedTo + 8 <= length) {
            val head = read(scannedTo, minOf(16L, length - scannedTo).toInt())
            val box = boxHeader(head) ?: return
            val (size, type) = box
            val boxEnd = scannedTo + size
            if (boxEnd > length) return
            when (type) {
                "moov" -> {
                    if (size > MAX_HEADER_BOX) return
                    tracks = parseMoov(read(scannedTo, size.toInt()))
                    initEnd = boxEnd
                    scannedTo = boxEnd
                }
                "moof" -> {
                    if (size > MAX_HEADER_BOX) return
                    // The mdat behind it has to be complete too, or this fragment is not yet.
                    if (boxEnd + 8 > length) return
                    val next = boxHeader(read(boxEnd, minOf(16L, length - boxEnd).toInt())) ?: return
                    if (next.second != "mdat") {
                        // Not the layout this was written for: skip the moof rather than guess.
                        scannedTo = boxEnd
                        continue
                    }
                    val fragmentEnd = boxEnd + next.first
                    if (fragmentEnd > length) return
                    if (tracks.isNotEmpty()) addFragment(scannedTo, read(scannedTo, size.toInt()), fragmentEnd)
                    scannedTo = fragmentEnd
                }
                else -> scannedTo = boxEnd
            }
        }
    }

    /** (size, type) of the box whose header starts [head], or null when it cannot be told yet. */
    private fun boxHeader(head: ByteArray): Pair<Long, String>? {
        if (head.size < 8) return null
        var size = u32(head, 0)
        var headerSize = 8
        if (size == 1L) {
            if (head.size < 16) return null
            size = u64(head, 8)
            headerSize = 16
        }
        // Size 0 = "to the end of the file", which a file still being written cannot honour.
        if (size == 0L || size < headerSize) return null
        return size to fourcc(head, 4)
    }

    private fun addFragment(start: Long, moof: ByteArray, end: Long) {
        val trafs = parseMoof(moof).map { t ->
            val track = tracks.firstOrNull { it.id == t.trackId }
            val ticks = t.totalDuration(track?.defaultSampleDuration ?: 0L)
            val base = t.tfdt ?: (nextBaseTime[t.trackId] ?: 0L)
            nextBaseTime[t.trackId] = base + ticks
            Traf(t.trackId, ticks, base)
        }
        fragmentList += Fragment(start, moof.size, end, trafs, durationOf(trafs))
    }

    /** The video track's length when there is one (fragments are cut on it), else the longest. */
    private fun durationOf(trafs: List<Traf>): Double {
        fun sec(t: Traf): Double {
            val scale = tracks.firstOrNull { it.id == t.trackId }?.timescale ?: return 0.0
            return if (scale > 0) t.durationTicks.toDouble() / scale else 0.0
        }
        val video = tracks.firstOrNull { it.handler == "vide" }
        trafs.firstOrNull { it.trackId == video?.id }?.let { return sec(it) }
        return trafs.maxOfOrNull { sec(it) } ?: 0.0
    }

    /** Decode time of each track at the start of [fragment], as the rewritten `tfdt`s state it. */
    fun baseTimes(fragment: Fragment): Map<Int, Long> = fragment.trafs.associate { it.trackId to it.baseDecodeTime }

    // ---- moof ------------------------------------------------------------------------------

    internal class RawTraf(
        val trackId: Int,
        val defaultDuration: Long?,
        val tfdt: Long?,
        /** Per run: each sample's duration when the run states them, else null. */
        val runDurations: List<LongArray?>,
        val runSampleCounts: List<Int>,
    ) {
        fun totalDuration(trexDefault: Long): Long {
            var total = 0L
            runDurations.forEachIndexed { i, durations ->
                total += durations?.sum() ?: (runSampleCounts[i] * (defaultDuration ?: trexDefault))
            }
            return total
        }
    }

    internal fun parseMoof(moof: ByteArray): List<RawTraf> {
        val out = ArrayList<RawTraf>()
        children(moof, 8, moof.size) { type, s, e -> if (type == "traf") out += parseTraf(moof, s, e) }
        return out
    }

    private fun parseTraf(b: ByteArray, from: Int, to: Int): RawTraf {
        var trackId = 0
        var defaultDuration: Long? = null
        var tfdt: Long? = null
        val runs = ArrayList<LongArray?>()
        val counts = ArrayList<Int>()
        children(b, from, to) { type, s, e ->
            when (type) {
                "tfhd" -> {
                    val flags = s32(b, s) and 0xFFFFFF
                    trackId = s32(b, s + 4)
                    var p = s + 8
                    if (flags and 0x1 != 0) p += 8
                    if (flags and 0x2 != 0) p += 4
                    if (flags and 0x8 != 0) defaultDuration = u32(b, p)
                }
                "tfdt" -> tfdt = if ((b[s].toInt() and 0xFF) == 1) u64(b, s + 4) else u32(b, s + 4)
                "trun" -> {
                    val tf = s32(b, s) and 0xFFFFFF
                    val count = s32(b, s + 4)
                    var p = s + 8
                    if (tf and 0x1 != 0) p += 4
                    if (tf and 0x4 != 0) p += 4
                    counts += count
                    if (tf and 0x100 != 0) {
                        val stride = 4 + (if (tf and 0x200 != 0) 4 else 0) +
                            (if (tf and 0x400 != 0) 4 else 0) + (if (tf and 0x800 != 0) 4 else 0)
                        val durations = LongArray(count)
                        for (i in 0 until count) {
                            if (p + 4 > e) break
                            durations[i] = u32(b, p)
                            p += stride
                        }
                        runs += durations
                    } else {
                        runs += null
                    }
                }
            }
        }
        return RawTraf(trackId, defaultDuration, tfdt, runs, counts)
    }

    // ---- moov ------------------------------------------------------------------------------

    internal fun parseMoov(moov: ByteArray): List<Track> {
        val defaults = HashMap<Int, Long>()
        val found = ArrayList<Track>()
        children(moov, 8, moov.size) { type, s, e ->
            when (type) {
                "trak" -> parseTrak(moov, s, e)?.let { found += it }
                "mvex" -> children(moov, s, e) { t, cs, _ ->
                    // trex: version/flags, track_ID, default_sample_description_index, default_sample_duration
                    if (t == "trex") defaults[s32(moov, cs + 4)] = u32(moov, cs + 12)
                }
            }
        }
        return found.map { it.copy(defaultSampleDuration = defaults[it.id] ?: 0L) }
    }

    private fun parseTrak(b: ByteArray, from: Int, to: Int): Track? {
        var id = 0
        var handler = ""
        var timescale = 0L
        var entry = ""
        var codec: String? = null
        var width = 0
        var height = 0
        children(b, from, to) { type, s, e ->
            when (type) {
                "tkhd" -> id = s32(b, s + if ((b[s].toInt() and 0xFF) == 1) 20 else 12)
                "mdia" -> children(b, s, e) { t, ms, me ->
                    when (t) {
                        "mdhd" -> timescale = u32(b, ms + if ((b[ms].toInt() and 0xFF) == 1) 20 else 12)
                        "hdlr" -> handler = fourcc(b, ms + 8)
                        "minf" -> children(b, ms, me) { t2, ns, ne ->
                            if (t2 == "stbl") children(b, ns, ne) { t3, ss, se ->
                                if (t3 == "stsd" && ss + 16 <= se) {
                                    // stsd: version/flags + entry_count, then the first sample entry.
                                    val entryStart = ss + 8
                                    entry = fourcc(b, entryStart + 4)
                                    val entryEnd = minOf(entryStart + u32(b, entryStart).toInt(), se)
                                    val body = entryStart + 8
                                    if (handler == "vide" || entry in VIDEO_ENTRIES) {
                                        // VisualSampleEntry: 6 reserved + 2 index + 16 pre-defined, then width/height,
                                        // then 50 more bytes before its child boxes (78 in all).
                                        if (body + 28 <= entryEnd) {
                                            width = u16(b, body + 24)
                                            height = u16(b, body + 26)
                                        }
                                        codec = videoCodec(b, entry, body + 78, entryEnd)
                                    } else {
                                        // AudioSampleEntry: 28 bytes before its child boxes.
                                        codec = audioCodec(b, entry, body + 28, entryEnd)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (id == 0) return null
        return Track(id, handler, timescale, entry, codec, width, height)
    }

    private fun videoCodec(b: ByteArray, entry: String, from: Int, to: Int): String? {
        if (from > to) return null
        var codec: String? = null
        children(b, from, to) { t, s, e ->
            when (t) {
                "avcC" -> if (s + 4 <= e) {
                    codec = String.format(
                        java.util.Locale.US, "%s.%02X%02X%02X", entry,
                        b[s + 1].toInt() and 0xFF, b[s + 2].toInt() and 0xFF, b[s + 3].toInt() and 0xFF,
                    )
                }
                "hvcC" -> if (s + 13 <= e) codec = hevcCodec(entry, b, s)
            }
        }
        return codec
    }

    /** ISO/IEC 14496-15 Annex E: `hvc1.<space><profile>.<compat, bit-reversed>.<tier><level>[.<constraints>]`. */
    private fun hevcCodec(entry: String, b: ByteArray, s: Int): String {
        val byte1 = b[s + 1].toInt() and 0xFF
        val space = when (byte1 shr 6) { 1 -> "A"; 2 -> "B"; 3 -> "C"; else -> "" }
        val tier = if (byte1 and 0x20 != 0) "H" else "L"
        val profile = byte1 and 0x1F
        val reversed = Integer.reverse(s32(b, s + 2)).toLong() and 0xFFFFFFFFL
        val level = b[s + 12].toInt() and 0xFF
        val constraints = (0 until 6).map { b[s + 6 + it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        return buildString {
            append(entry).append('.').append(space).append(profile)
            append('.').append(java.lang.Long.toHexString(reversed).uppercase())
            append('.').append(tier).append(level)
            constraints.forEach { append('.').append(String.format(java.util.Locale.US, "%02X", it)) }
        }
    }

    private fun audioCodec(b: ByteArray, entry: String, from: Int, to: Int): String? {
        when (entry) {
            "ac-3", "ec-3" -> return entry
            "Opus" -> return "opus"
            "fLaC" -> return "flac"
        }
        if (entry != "mp4a") return null
        var codec = "mp4a.40.2"
        if (from <= to) children(b, from, to) { t, s, e -> if (t == "esds") esdsCodec(b, s + 4, e)?.let { codec = it } }
        return codec
    }

    /** objectTypeIndication from the DecoderConfigDescriptor, and for AAC the object type under it. */
    private fun esdsCodec(b: ByteArray, from: Int, to: Int): String? {
        var p = from
        while (p < to) {
            val tag = b[p++].toInt() and 0xFF
            var len = 0
            for (i in 0 until 4) {
                if (p >= to) return null
                val v = b[p++].toInt() and 0xFF
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) break
            }
            when (tag) {
                0x03 -> {
                    // ES_Descriptor: ES_ID(2), flags(1), optional fields, then nested descriptors.
                    if (p + 3 > to) return null
                    val flags = b[p + 2].toInt() and 0xFF
                    p += 3
                    if (flags and 0x80 != 0) p += 2
                    if (flags and 0x40 != 0 && p < to) p += 1 + (b[p].toInt() and 0xFF)
                    if (flags and 0x20 != 0) p += 2
                }
                0x04 -> {
                    if (p >= to) return null
                    val oti = b[p].toInt() and 0xFF
                    if (oti != 0x40) return String.format(java.util.Locale.US, "mp4a.%02X", oti)
                    // objectTypeIndication(1) streamType(1) bufferSize(3) maxBitrate(4) avgBitrate(4)
                    p += 13
                }
                0x05 -> {
                    if (len < 1 || p >= to) return "mp4a.40.2"
                    val aot = (b[p].toInt() and 0xFF) shr 3
                    return "mp4a.40.${if (aot == 0) 2 else aot}"
                }
                else -> p += len
            }
        }
        return null
    }

    companion object {
        /** A moov or moof larger than this is not something the remux's writer produces. */
        private const val MAX_HEADER_BOX = 16L * 1024 * 1024

        private val VIDEO_ENTRIES = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09")

        /**
         * [moof] (read from [moofStart] in the file) as it goes out in a segment: `tfhd` relative
         * to the moof, a `tfdt` per track carrying the decode time [baseTimes] gives it (unless the
         * traf already has one), and every `trun.data_offset` moved by however much the moof grew,
         * so each still points at the same byte of the `mdat` that follows it unchanged.
         */
        fun rewriteMoof(moof: ByteArray, moofStart: Long, baseTimes: Map<Int, Long>): ByteArray {
            val out = java.io.ByteArrayOutputStream(moof.size + 64)
            // Position in [out] of each data_offset field, and the absolute file position it named.
            val patches = ArrayList<Pair<Int, Long>>()
            out.write(ByteArray(8)) // moof header, filled in at the end
            children(moof, 8, moof.size) { type, s, e ->
                if (type != "traf") {
                    out.write(moof, s - 8, e - s + 8)
                } else {
                    writeTraf(moof, s, e, moofStart, baseTimes, out, patches)
                }
            }
            val result = out.toByteArray()
            setInt(result, 0, result.size)
            setFourcc(result, 4, "moof")
            val grew = result.size - moof.size
            for ((pos, absolute) in patches) setInt(result, pos, (absolute - moofStart + grew).toInt())
            return result
        }

        private fun writeTraf(
            moof: ByteArray,
            s: Int,
            e: Int,
            moofStart: Long,
            baseTimes: Map<Int, Long>,
            out: java.io.ByteArrayOutputStream,
            patches: MutableList<Pair<Int, Long>>,
        ) {
            val body = java.io.ByteArrayOutputStream(e - s + 32)
            val bodyOffset = out.size() + 8
            val localPatches = ArrayList<Pair<Int, Long>>()
            var base = moofStart
            var hasTfdt = false
            children(moof, s, e) { t, _, _ -> if (t == "tfdt") hasTfdt = true }
            children(moof, s, e) { t, cs, ce ->
                when (t) {
                    "tfhd" -> {
                        val flags = s32(moof, cs) and 0xFFFFFF
                        val trackId = s32(moof, cs + 4)
                        var p = cs + 8
                        if (flags and 0x1 != 0) {
                            base = u64(moof, p)
                            p += 8
                        }
                        // Same box minus base_data_offset, with default-base-is-moof set.
                        val newFlags = (flags and 0x1.inv()) or 0x020000
                        val tfhdBody = int32(newFlags) + int32(trackId) + moof.copyOfRange(p, ce)
                        body.write(int32(tfhdBody.size + 8))
                        body.write("tfhd".toByteArray(Charsets.ISO_8859_1))
                        body.write(tfhdBody)
                        if (!hasTfdt) {
                            body.write(int32(20))
                            body.write("tfdt".toByteArray(Charsets.ISO_8859_1))
                            body.write(int32(0x01000000)) // version 1: a 64-bit decode time
                            body.write(int64(baseTimes[trackId] ?: 0L))
                        }
                    }
                    "trun" -> {
                        val tf = s32(moof, cs) and 0xFFFFFF
                        val boxStart = body.size()
                        body.write(moof, cs - 8, ce - cs + 8)
                        // data_offset sits after the box header, version/flags and sample_count.
                        if (tf and 0x1 != 0) localPatches += (boxStart + 16) to s32(moof, cs + 8).toLong()
                    }
                    else -> body.write(moof, cs - 8, ce - cs + 8)
                }
            }
            out.write(int32(body.size() + 8))
            out.write("traf".toByteArray(Charsets.ISO_8859_1))
            body.writeTo(out)
            // The base is only known once the tfhd was read, which comes before any trun.
            localPatches.forEach { (pos, relative) -> patches += (bodyOffset + pos) to (base + relative) }
        }

        /** Calls [block] with (type, contentStart, end) for each box laid end to end in [from, to). */
        internal inline fun children(b: ByteArray, from: Int, to: Int, block: (String, Int, Int) -> Unit) {
            var p = from
            while (p + 8 <= to) {
                var size = u32(b, p)
                val type = fourcc(b, p + 4)
                var header = 8
                if (size == 1L) {
                    if (p + 16 > to) return
                    size = u64(b, p + 8)
                    header = 16
                }
                if (size == 0L) size = (to - p).toLong()
                if (size < header || p + size > to) return
                block(type, p + header, (p + size).toInt())
                p += size.toInt()
            }
        }

        internal fun u32(b: ByteArray, p: Int): Long =
            ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

        internal fun s32(b: ByteArray, p: Int): Int = u32(b, p).toInt()

        internal fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

        internal fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

        internal fun fourcc(b: ByteArray, p: Int): String = String(b, p, 4, Charsets.ISO_8859_1)

        internal fun int32(v: Int): ByteArray =
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        private fun int64(v: Long): ByteArray = int32((v ushr 32).toInt()) + int32(v.toInt())

        private fun setInt(b: ByteArray, p: Int, v: Int) {
            int32(v).copyInto(b, p)
        }

        private fun setFourcc(b: ByteArray, p: Int, type: String) {
            type.toByteArray(Charsets.ISO_8859_1).copyInto(b, p)
        }
    }
}
