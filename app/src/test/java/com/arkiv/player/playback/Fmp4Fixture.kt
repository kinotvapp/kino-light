package com.arkiv.player.playback

import java.io.File

/**
 * `remux-fixture.mp4`: 8 s of H.264 (keyframe every second) + AAC, written by media3's own
 * `FragmentedMp4Muxer` -- the muxer behind the Chromecast remux (`InAppFragmentedMp4Muxer`) -- so
 * it has exactly that layout: `tfhd` with an absolute base_data_offset, `trun` with per-sample
 * durations, and no `tfdt`. Each sample's payload starts with its track and index, so a sample
 * read from the wrong bytes cannot go unnoticed. (The muxer itself cannot run in these tests: it
 * needs a real `android.util.SparseArray`.)
 *
 * Plus [readSamples], a reader with a segment player's semantics -- fresh state, `tfdt` when
 * there is one and zero when not, data offsets resolved against the bytes it is handed -- written
 * straight from ISO/IEC 14496-12, independently of the rewrite it checks.
 */
object Fmp4Fixture {

    fun copyTo(dir: File): File {
        val out = File(dir, "remux.mp4")
        Fmp4Fixture::class.java.classLoader!!.getResourceAsStream("remux-fixture.mp4")!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    fun indexOf(file: File, length: Long = file.length()): Fmp4Index {
        val index = Fmp4Index()
        java.io.RandomAccessFile(file, "r").use { raf ->
            index.update(length) { offset, size ->
                val n = minOf(size.toLong(), length - offset).toInt().coerceAtLeast(0)
                ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
            }
        }
        return index
    }

    /** The init segment and segment [segment] exactly as the server sends them. */
    fun segmentBytes(file: File, index: Fmp4Index, segment: RemuxHls.Segment): Pair<ByteArray, ByteArray> {
        val all = file.readBytes()
        val init = all.copyOfRange(0, index.initEnd.toInt())
        val out = java.io.ByteArrayOutputStream()
        index.fragments.subList(segment.first, segment.last + 1).forEach { f ->
            val moof = all.copyOfRange(f.start.toInt(), f.start.toInt() + f.moofSize)
            out.write(Fmp4Index.rewriteMoof(moof, f.start, index.baseTimes(f)))
            out.write(all, (f.start + f.moofSize).toInt(), (f.end - f.start - f.moofSize).toInt())
        }
        return init to out.toByteArray()
    }

    data class Sample(val trackId: Int, val decodeTime: Long, val data: List<Byte>)

    fun readSamples(bytes: ByteArray): List<Sample> {
        val out = ArrayList<Sample>()
        val running = HashMap<Int, Long>()
        var trexDefaults = emptyMap<Int, Long>()
        Fmp4Index.children(bytes, 0, bytes.size) { type, s, e ->
            when (type) {
                "moov" -> trexDefaults = Fmp4Index().parseMoov(bytes.copyOfRange(s - 8, e)).associate { it.id to it.defaultSampleDuration }
                "moof" -> {
                    val moofStart = (s - 8).toLong()
                    Fmp4Index.children(bytes, s, e) { t, ts, te ->
                        if (t == "traf") readTraf(bytes, ts, te, moofStart, running, trexDefaults, out)
                    }
                }
            }
        }
        return out
    }

    private fun readTraf(
        b: ByteArray, from: Int, to: Int, moofStart: Long,
        running: MutableMap<Int, Long>, trex: Map<Int, Long>, out: MutableList<Sample>,
    ) {
        var trackId = 0
        var base = moofStart
        var defaultDuration: Long? = null
        var defaultSize: Long? = null
        var decode: Long? = null
        Fmp4Index.children(b, from, to) { t, s, e ->
            when (t) {
                "tfhd" -> {
                    val flags = Fmp4Index.s32(b, s) and 0xFFFFFF
                    trackId = Fmp4Index.s32(b, s + 4)
                    var p = s + 8
                    if (flags and 0x1 != 0) { base = Fmp4Index.u64(b, p); p += 8 }
                    if (flags and 0x2 != 0) p += 4
                    if (flags and 0x8 != 0) { defaultDuration = Fmp4Index.u32(b, p); p += 4 }
                    if (flags and 0x10 != 0) { defaultSize = Fmp4Index.u32(b, p); p += 4 }
                }
                "tfdt" -> decode = if ((b[s].toInt() and 0xFF) == 1) Fmp4Index.u64(b, s + 4) else Fmp4Index.u32(b, s + 4)
                "trun" -> {
                    val flags = Fmp4Index.s32(b, s) and 0xFFFFFF
                    val count = Fmp4Index.s32(b, s + 4)
                    var p = s + 8
                    var dataOffset = 0L
                    if (flags and 0x1 != 0) { dataOffset = Fmp4Index.s32(b, p).toLong(); p += 4 }
                    if (flags and 0x4 != 0) p += 4
                    var time = decode ?: running[trackId] ?: 0L
                    var pos = base + dataOffset
                    repeat(count) {
                        var duration = defaultDuration ?: trex[trackId] ?: 0L
                        var size = defaultSize ?: 0L
                        if (flags and 0x100 != 0) { duration = Fmp4Index.u32(b, p); p += 4 }
                        if (flags and 0x200 != 0) { size = Fmp4Index.u32(b, p); p += 4 }
                        if (flags and 0x400 != 0) p += 4
                        if (flags and 0x800 != 0) p += 4
                        out += Sample(trackId, time, b.copyOfRange(pos.toInt(), (pos + size).toInt()).toList())
                        time += duration
                        pos += size
                    }
                    running[trackId] = time
                    decode = time
                }
            }
        }
    }
}
