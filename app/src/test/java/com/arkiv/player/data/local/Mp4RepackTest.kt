package com.arkiv.player.data.local

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * A download rewritten as MP4 ([Mp4Repackager]) keeps every audio track, their languages and the
 * start offsets between the tracks, and comes out faststart.
 *
 * `mp4-repack-2audio.ts`: 30 s of `remux-sync-fixture.ts`'s HEVC with two AAC tracks, `spa` (21 ms
 * before the video, as in the source) and `eng` (the same audio 500 ms later). Both files are read
 * back with media3's own extractors -- what the player sees -- and compared track by track.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Mp4RepackTest {

    private fun fixture(name: String, dir: File): File =
        File(dir, name).apply { writeBytes(Mp4RepackTest::class.java.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }) }

    private fun tmp(): File = File(System.getProperty("java.io.tmpdir"), "mp4-repack-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun everyAudioTrackLanguageAndOffsetSurvives(): Unit = runBlocking {
        val dir = tmp()
        val input = fixture("mp4-repack-2audio.ts", dir)
        val output = File(dir, "out.mp4")
        val t0 = System.nanoTime()
        val result = Mp4Repackager().repack(input, output)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("done: $result", result is Mp4Repackager.Result.Done)
        result as Mp4Repackager.Result.Done
        println("repack: ${input.length()}B ts → ${output.length()}B mp4 in ${ms}ms (${"%.1f".format(input.length() / 1048576.0 / (ms / 1000.0))} MB/s), $result")
        System.getenv("MP4_REPACK_OUT")?.let { output.copyTo(File(it), overwrite = true) }
        assertEquals(1, result.videoTracks)
        assertEquals(2, result.audioTracks)
        assertTrue("faststart", result.fastStart)
        assertTrue("moov before mdat", Mp4FastStart.isFastStart(output))
        assertTrue("no .part left", dir.listFiles()!!.none { it.name.endsWith(".part") })

        val src = Probe.of(input)
        val out = Probe.of(output)
        assertEquals(listOf("video/hevc", "audio/mp4a-latm", "audio/mp4a-latm"), out.tracks.map { it.mime })
        assertEquals(listOf(null, "es", "en"), out.tracks.map { it.language?.take(2) })
        // Every sample of every track made it.
        src.tracks.zip(out.tracks).forEach { (s, o) -> assertEquals("${s.mime} samples", s.count, o.count) }
        // Offsets between the tracks: the same within one AAC frame (21.3 ms), video against each audio.
        val srcVideo = src.tracks[0].firstUs
        val outVideo = out.tracks[0].firstUs
        for (i in 1..2) {
            val before = srcVideo - src.tracks[i].firstUs
            val after = outVideo - out.tracks[i].firstUs
            println("track $i: video-audio offset ${before / 1000.0} ms → ${after / 1000.0} ms")
            assertTrue("track $i offset $before → $after", abs(before - after) <= 21_400)
        }
        dir.deleteRecursively()
    }

    /** Opt-in speed check on a bigger file: `MP4_REPACK_BENCH=/path/to/file.ts`. */
    @Test
    fun benchmark(): Unit = runBlocking {
        val path = System.getenv("MP4_REPACK_BENCH") ?: return@runBlocking
        val input = File(path)
        val output = File(tmp(), "bench.mp4")
        val t0 = System.nanoTime()
        val r = Mp4Repackager().repack(input, output)
        val s = (System.nanoTime() - t0) / 1e9
        println("bench: ${input.length() / 1048576} MB in ${"%.2f".format(s)} s = ${"%.1f".format(input.length() / 1048576.0 / s)} MB/s · $r")
        assertTrue("$r", r is Mp4Repackager.Result.Done)
        output.parentFile!!.deleteRecursively()
    }

    @Test
    fun onlyOneAudioWhenAsked(): Unit = runBlocking {
        val dir = tmp()
        val input = fixture("mp4-repack-2audio.ts", dir)
        val all = File(dir, "all.mp4")
        assertTrue(Mp4Repackager().repack(input, all) is Mp4Repackager.Result.Done)
        // From the converted MP4, as the Chromecast's single-audio copy is made.
        val eng = File(dir, "eng.mp4")
        val r = Mp4Repackager().repack(all, eng, onlyAudio = 1)
        assertTrue("$r", r is Mp4Repackager.Result.Done)
        val probe = Probe.of(eng)
        assertEquals(listOf("video/hevc", "audio/mp4a-latm"), probe.tracks.map { it.mime })
        assertEquals("en", probe.tracks[1].language?.take(2))
        assertTrue(Mp4FastStart.isFastStart(eng))
        dir.deleteRecursively()
    }

    @Test
    fun ac3AudioIsUnsupportedAndLeavesNothing(): Unit = runBlocking {
        val dir = tmp()
        val input = fixture("mp4-repack-ac3.ts", dir)
        val output = File(dir, "out.mp4")
        val r = Mp4Repackager().repack(input, output)
        assertTrue("$r", r is Mp4Repackager.Result.Unsupported)
        assertTrue((r as Mp4Repackager.Result.Unsupported).reason.contains("audio/ac3"))
        assertTrue(!output.exists())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        dir.deleteRecursively()
    }

    @Test
    fun aFullDiskStopsTheRepack(): Unit = runBlocking {
        val dir = tmp()
        val input = fixture("mp4-repack-2audio.ts", dir)
        val output = File(dir, "out.mp4")
        val r = Mp4Repackager(freeSpace = { 10L }, checkEveryBytes = 1).repack(input, output)
        assertTrue("$r", r is Mp4Repackager.Result.NoSpace)
        assertTrue(!output.exists())
        dir.deleteRecursively()
    }

    @Test
    fun notAVideoIsUnsupported(): Unit = runBlocking {
        val dir = tmp()
        val input = File(dir, "junk.bin").apply { writeBytes(ByteArray(10_000) { (it * 7).toByte() }) }
        val r = Mp4Repackager().repack(input, File(dir, "out.mp4"))
        assertTrue("$r", r is Mp4Repackager.Result.Unsupported)
        dir.deleteRecursively()
    }

    @Test
    fun aShortBudgetKeepsTheMoovAtTheEnd() {
        // ftyp | free(8) | mdat | moov(40): the moov does not fit, the file is left as it is.
        val dir = tmp()
        val f = File(dir, "x.mp4")
        f.writeBytes(box("ftyp", 8) + box("free", 0) + box("mdat", 100) + box("moov", 32))
        val before = f.readBytes()
        assertTrue(!Mp4FastStart.relocate(f))
        assertTrue(before.contentEquals(f.readBytes()))
        // With room: moov moves to the front, the rest of the hole stays a free box, the tail is cut.
        f.writeBytes(box("ftyp", 8) + box("free", 100) + box("mdat", 100) + box("moov", 32))
        assertTrue(Mp4FastStart.relocate(f))
        RandomAccessFile(f, "r").use { raf ->
            val boxes = Mp4FastStart.topLevelBoxes(raf)
            assertEquals(listOf("ftyp", "moov", "free", "mdat"), boxes.map { it.type })
            assertEquals(16L + 108 + 108, raf.length())
        }
        dir.deleteRecursively()
    }

    @Test
    fun moovBudgetCoversAFeatureFilm() {
        // Two hours, 24 fps, four AAC tracks at 48 kHz: ~13 MB, under 1% of a 3 GB file.
        val budget = Mp4FastStart.moovBudget(7_200_000_000L, listOf(24.0), List(4) { 46.875 })
        println("moov budget for 2 h, 24 fps, 4 audio: $budget B")
        assertTrue("$budget", budget in 8_000_000..20_000_000)
        // The fixture's real moov (30 s, 1 video + 2 audio) is 22.7 KB: the budget covers it.
        assertTrue(Mp4FastStart.moovBudget(30_000_000L, listOf(25.0), listOf(46.875, 46.875)) > 22_737)
    }

    private fun box(type: String, payload: Int): ByteArray =
        java.nio.ByteBuffer.allocate(8 + payload).putInt(8 + payload).put(type.toByteArray()).array()

    /** Per track (container order, audio/video only): codec, language, sample count, first presentation time. */
    private class Probe(val tracks: List<Track>) {
        class Track(var mime: String? = null, var language: String? = null, var count: Int = 0, var firstUs: Long = Long.MAX_VALUE)

        companion object {
            fun of(file: File): Probe {
                val tracks = mutableListOf<Track>()
                RandomAccessFile(file, "r").use { raf ->
                    fun open(pos: Long): DefaultExtractorInput {
                        raf.seek(pos)
                        return DefaultExtractorInput(DataReader { b, o, l -> raf.read(b, o, l).let { if (it < 0) C.RESULT_END_OF_INPUT else it } }, pos, raf.length())
                    }
                    val extractor = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(false).createExtractors()
                        .first { e -> runCatching { e.sniff(open(0)) }.getOrDefault(false) }
                    extractor.init(object : ExtractorOutput {
                        override fun track(id: Int, type: Int): TrackOutput {
                            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) return DiscardingTrackOutput()
                            val t = Track().also { tracks += it }
                            return object : TrackOutput {
                                override fun format(format: Format) { t.mime = format.sampleMimeType; t.language = format.language }
                                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                                    val n = input.read(ByteArray(length), 0, length)
                                    return n
                                }
                                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                                    t.count++
                                    t.firstUs = minOf(t.firstUs, timeUs)
                                }
                            }
                        }
                        override fun endTracks() = Unit
                        override fun seekMap(seekMap: SeekMap) = Unit
                    })
                    val holder = PositionHolder()
                    var input = open(0)
                    while (true) {
                        when (extractor.read(input, holder)) {
                            Extractor.RESULT_END_OF_INPUT -> break
                            Extractor.RESULT_SEEK -> input = open(holder.position)
                        }
                    }
                }
                assertNotNull(tracks.firstOrNull())
                return Probe(tracks)
            }
        }
    }
}
