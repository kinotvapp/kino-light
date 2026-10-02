package com.arkiv.player.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * A cast remux that starts mid-file keeps audio and video in step: the go/no-go of starting the
 * remux near the phone's position (`debug.kino.remux_seek_start`).
 *
 * Runs the app's own pipeline -- [TsStartLocator] on the bytes, [TsStartDataSource] feeding
 * media3's Transformer with the in-app fragmented muxer ([RemuxExport]), then every fragment
 * rewritten as [RemuxHlsServer] serves it ([Fmp4Index.rewriteMoof]) -- over
 * `remux-sync-fixture.ts`: 90 s of HEVC (open GOPs, a CRA every 4 s, three B-frames, the shape
 * of a Xuper title) + AAC 48 kHz, a white frame and a 1 kHz beep together every 10 s.
 *
 * Checked straight from the boxes as ISO 14496-12 places them (no edit list: presentation =
 * tfdt + decode durations + composition offset), each output sample matched to its source sample
 * in the TS: video by decode order from the start keyframe, audio by its bytes. Within a track the
 * output time minus the source PTS has to be one constant; the A/V error is video's constant minus
 * audio's. The from-zero remux the app has always cast measures -53 ms on this stream by the same
 * yardstick; a mid-file read WITHOUT dropping the early audio measured -459 ms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemuxMidFileSyncTest {

    private val fixture: ByteArray =
        javaClass.classLoader!!.getResourceAsStream("remux-sync-fixture.ts")!!.use { it.readBytes() }

    @Test
    fun locatorLandsOnTheFirstKeyframeAtOrAfterThePoint() {
        val source = Ts.parse(fixture)
        for (target in listOf(20_000L, 41_000L, 64_000L)) {
            val start = locate(target)
            assertNotNull("start for $target", start)
            start!!
            val k = source.video.first { it.offset == start.byteOffset }
            assertTrue("a keyframe at $target", k.key)
            val ms = (k.pts - source.firstPts) / 90
            assertEquals(ms, start.startMs)
            assertTrue("$ms at or after $target", ms >= target)
            // The FIRST one: no keyframe between the point and it.
            assertTrue(source.video.none { it.key && (it.pts - source.firstPts) / 90 in target until ms })
        }
    }

    @Test
    fun midFileRemuxKeepsAudioAndVideoInStep() {
        // Two start points: the CRA at 44 s opens a GOP with one leading picture fewer than the
        // ones after it, the one at 64 s with as many -- the two cases of the server's summed tfdt.
        for (target in listOf(41_000L, 61_000L)) {
            val start = locate(target)!!
            val sync = measure(remuxAndServe(start), keyIndex(start.byteOffset))
            println("mid-file remux from ${start.startMs}ms: $sync")
            // Every track keeps its own clock across the fragments...
            assertTrue("audio mapping constant: $sync", sync.audioSpreadMs <= 1.0)
            assertTrue("video mapping constant for most samples: $sync", sync.videoOnMedian >= 0.85)
            // ...the audio before the keyframe is gone, to within one AAC frame (21.3 ms)...
            assertTrue("first audio frame at the keyframe: $sync", sync.firstAudioAfterKeyframeMs in 0.0..21.4)
            // ...and the tracks play together, well inside +/-40 ms.
            assertTrue("A/V error ${sync.avErrorMs} ms", abs(sync.avErrorMs) <= 40.0)
            assertTrue("first video sample is a keyframe", sync.firstVideoIsSync)
        }
    }

    /**
     * The yardstick: the from-the-top remux every cast has used, measured the same way. Not the
     * feature under test -- it pins what "in step" has meant on the TV so far.
     */
    @Test
    fun fromTheTopRemuxIsTheYardstick() {
        val sync = measure(remuxAndServe(null), 0)
        println("remux from the top: $sync")
        assertTrue("A/V error ${sync.avErrorMs} ms", abs(sync.avErrorMs) <= 80.0)
    }

    // ---- pipeline ------------------------------------------------------------------------------

    private fun locate(targetMs: Long): TsStart? =
        TsStartLocator(fixture.size.toLong(), { offset, size ->
            if (offset >= fixture.size) null else fixture.copyOfRange(offset.toInt(), minOf(fixture.size.toLong(), offset + size).toInt())
        }).locate(targetMs)

    private fun keyIndex(byteOffset: Long): Int = Ts.parse(fixture).video.indexOfFirst { it.offset == byteOffset }

    private fun remuxAndServe(start: TsStart?): ByteArray {
        val dir = File(System.getProperty("java.io.tmpdir"), "remux-sync-${System.nanoTime()}").apply { mkdirs() }
        val input = File(dir, "in.ts").apply { writeBytes(fixture) }
        val output = File(dir, "out.mp4")
        val context = RuntimeEnvironment.getApplication()
        val plain = DefaultDataSource.Factory(context)
        val read = start?.let { TsStartDataSource.Factory(plain, it) } ?: plain
        var done = false
        var error: ExportException? = null
        val transformer = RemuxExport.transformer(context, RemuxExport.assetLoaderFactory(context, read, null), startsMidFile = start != null)
            .buildUpon()
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done = true }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    error = exportException
                    done = true
                }
            })
            .build()
        transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.path)
        val deadline = System.currentTimeMillis() + 120_000
        while (!done && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        error?.let { throw it }
        assertTrue("export finished", done)
        // Every fragment as the server sends it.
        val index = Fmp4Fixture.indexOf(output)
        val all = output.readBytes()
        val out = java.io.ByteArrayOutputStream()
        out.write(all, 0, index.initEnd.toInt())
        index.fragments.forEach { f ->
            out.write(Fmp4Index.rewriteMoof(all.copyOfRange(f.start.toInt(), f.start.toInt() + f.moofSize), f.start, index.baseTimes(f)))
            out.write(all, (f.start + f.moofSize).toInt(), (f.end - f.start - f.moofSize).toInt())
        }
        System.getenv("REMUX_SYNC_OUT")?.let { File("$it-${start?.startMs ?: 0}.mp4").writeBytes(out.toByteArray()) }
        dir.deleteRecursively()
        return out.toByteArray()
    }

    // ---- measuring -------------------------------------------------------------------------------

    data class Sync(
        val avErrorMs: Double,
        val videoMedianMs: Double,
        val audioMedianMs: Double,
        val audioSpreadMs: Double,
        val videoOnMedian: Double,
        val firstAudioAfterKeyframeMs: Double,
        val firstVideoIsSync: Boolean,
    )

    /** [served] against the source, its video starting on the source's video PES number [k]. */
    private fun measure(served: ByteArray, k: Int): Sync {
        val source = Ts.parse(fixture)
        val out = Mp4.parse(served)
        val video = out.getValue("vide")
        val audio = out.getValue("soun")
        val keyPts = source.video[k].pts
        // Video: decode order from the start keyframe. Leading pictures of the open GOP are never
        // shown when decoding starts on it, so the first GOP is left out.
        val videoOffsets = video.indices.drop(GOP).filter { k + it < source.video.size }.map { i ->
            video[i].ctSec * 1000 - (source.video[k + i].pts - keyPts) / 90.0
        }
        // Audio: the output's frames are found among the source's by their bytes -- a run long
        // enough to hold a beep, since every silent frame looks the same.
        val run = minOf(audio.size, 700)
        val j0 = source.audio.indices.firstOrNull { j ->
            abs(source.audio[j].pts - keyPts) < 90_000L && j + run <= source.audio.size &&
                (0 until run).all { source.audio[j + it].data.contentEquals(audio[it].data) }
        } ?: -1
        assertTrue("first audio sample found in the source", j0 >= 0)
        val audioOffsets = audio.indices.filter { j0 + it < source.audio.size }.map { i ->
            audio[i].ctSec * 1000 - (source.audio[j0 + i].pts - keyPts) / 90.0
        }
        val vMedian = videoOffsets.sorted()[videoOffsets.size / 2]
        val aMedian = audioOffsets.sorted()[audioOffsets.size / 2]
        return Sync(
            avErrorMs = vMedian - aMedian,
            videoMedianMs = vMedian,
            audioMedianMs = aMedian,
            audioSpreadMs = audioOffsets.max() - audioOffsets.min(),
            videoOnMedian = videoOffsets.count { abs(it - vMedian) <= 1.0 }.toDouble() / videoOffsets.size,
            firstAudioAfterKeyframeMs = (source.audio[j0].pts - keyPts) / 90.0,
            firstVideoIsSync = video[0].sync,
        )
    }

    private companion object { const val GOP = 100 }

    /** The fixture's PES, per stream: decode order, PTS and payload. */
    private class Ts(val firstPts: Long, val video: List<Frame>, val audio: List<Frame>) {
        class Frame(val offset: Long, val pts: Long, val key: Boolean, val data: ByteArray)

        companion object {
            fun parse(b: ByteArray): Ts {
                val head = TsStartPoint.head(b)!!
                val pes = HashMap<Int, MutableList<Triple<Long, Boolean, java.io.ByteArrayOutputStream>>>()
                var i = 0
                while (i + 188 <= b.size) {
                    val pid = TsStartPoint.pid(b, i)
                    val afc = (b[i + 3].toInt() shr 4) and 3
                    var p = i + 4
                    if (afc == 2 || afc == 3) p += 1 + (b[i + 4].toInt() and 0xFF)
                    if ((afc == 1 || afc == 3) && pid in head.mediaPids && p < i + 188) {
                        val list = pes.getOrPut(pid) { ArrayList() }
                        if (TsStartPoint.pusi(b, i)) list += Triple(i.toLong(), MpegTs.isRandomAccess(b, i), java.io.ByteArrayOutputStream())
                        list.lastOrNull()?.third?.write(b, p, i + 188 - p)
                    }
                    i += 188
                }
                fun frames(pid: Int) = pes.getValue(pid).map { (offset, key, bytes) ->
                    val d = bytes.toByteArray()
                    val pts = (((d[9].toLong() shr 1) and 7) shl 30) or ((d[10].toLong() and 0xFF) shl 22) or
                        (((d[11].toLong() and 0xFF) shr 1) shl 15) or ((d[12].toLong() and 0xFF) shl 7) or ((d[13].toLong() and 0xFF) shr 1)
                    Frame(offset, pts, key, d.copyOfRange(9 + (d[8].toInt() and 0xFF), d.size))
                }
                val audioPid = head.mediaPids.first { it != head.videoPid }
                // ADTS frames out of each audio PES, 1024 samples at 48 kHz apart.
                val audio = frames(audioPid).flatMap { f ->
                    val out = ArrayList<Frame>()
                    var q = 0
                    var n = 0
                    while (q + 7 <= f.data.size) {
                        val len = ((f.data[q + 3].toInt() and 3) shl 11) or ((f.data[q + 4].toInt() and 0xFF) shl 3) or ((f.data[q + 5].toInt() and 0xFF) shr 5)
                        val header = if (f.data[q + 1].toInt() and 1 == 0) 9 else 7
                        out += Frame(f.offset, f.pts + n * 1024L * 90_000 / 48_000, false, f.data.copyOfRange(q + header, q + len))
                        q += len
                        n++
                    }
                    out
                }
                return Ts(head.firstPts, frames(head.videoPid), audio)
            }
        }
    }

    /** Samples of a fragmented MP4 as a player places them, per handler (`vide`, `soun`). */
    private object Mp4 {
        class Sample(val ctSec: Double, val sync: Boolean, val data: ByteArray)

        fun parse(b: ByteArray): Map<String, List<Sample>> {
            val handler = HashMap<Int, Pair<String, Long>>()
            val out = HashMap<String, MutableList<Sample>>()
            val running = HashMap<Int, Long>()
            Fmp4Index.children(b, 0, b.size) { type, s, e ->
                when (type) {
                    "moov" -> Fmp4Index().parseMoov(b.copyOfRange(s - 8, e)).forEach { handler[it.id] = it.handler to it.timescale }
                    "moof" -> Fmp4Index.children(b, s, e) { t, ts, te -> if (t == "traf") traf(b, ts, te, (s - 8).toLong(), handler, running, out) }
                }
            }
            return out
        }

        private fun traf(
            b: ByteArray, from: Int, to: Int, moofStart: Long,
            handler: Map<Int, Pair<String, Long>>, running: MutableMap<Int, Long>, out: MutableMap<String, MutableList<Sample>>,
        ) {
            var track = 0
            var base = moofStart
            var decode: Long? = null
            Fmp4Index.children(b, from, to) { t, s, _ ->
                when (t) {
                    "tfhd" -> {
                        val flags = Fmp4Index.s32(b, s) and 0xFFFFFF
                        track = Fmp4Index.s32(b, s + 4)
                        if (flags and 1 != 0) base = Fmp4Index.u64(b, s + 8)
                    }
                    "tfdt" -> decode = if ((b[s].toInt() and 0xFF) == 1) Fmp4Index.u64(b, s + 4) else Fmp4Index.u32(b, s + 4)
                    "trun" -> {
                        val version = b[s].toInt() and 0xFF
                        val flags = Fmp4Index.s32(b, s) and 0xFFFFFF
                        val count = Fmp4Index.s32(b, s + 4)
                        var p = s + 8
                        var dataOffset = 0L
                        if (flags and 1 != 0) { dataOffset = Fmp4Index.s32(b, p).toLong(); p += 4 }
                        if (flags and 4 != 0) p += 4
                        val (name, scale) = handler.getValue(track)
                        var time = decode ?: running[track] ?: 0L
                        var pos = base + dataOffset
                        repeat(count) {
                            val duration = Fmp4Index.u32(b, p); p += 4
                            val size = Fmp4Index.u32(b, p); p += 4
                            val sampleFlags = Fmp4Index.s32(b, p); p += 4
                            var cto = 0L
                            if (flags and 0x800 != 0) {
                                cto = if (version == 1) Fmp4Index.s32(b, p).toLong() else Fmp4Index.u32(b, p); p += 4
                            }
                            val sync = sampleFlags and 0x10000 == 0
                            out.getOrPut(name) { ArrayList() } +=
                                Sample((time + cto).toDouble() / scale, sync, b.copyOfRange(pos.toInt(), (pos + size).toInt()))
                            time += duration
                            pos += size
                        }
                        running[track] = time
                        decode = time
                    }
                }
            }
        }
    }
}
