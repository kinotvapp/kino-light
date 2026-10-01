package com.arkiv.player.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TsTailPcrExtractor] against a fake TsExtractor that reads its duration the way media3's does
 * (last PCR in the final [WINDOW] bytes, first PCR at the head) over a synthetic TS whose PCR-carrying
 * video can stop well before the end of the file -- the shape of the Xuper movie that played with no
 * duration (last PCR 652 548 B before the end against a 256 KB window).
 */
class TsTailPcrExtractorTest {

    private val WINDOW = 4 * 1024
    private val STEPS = longArrayOf(16L * 1024, 64L * 1024)
    private val VIDEO = 0x100
    private val AUDIO = 0x101

    // ---- the decision ---------------------------------------------------------------------------

    @Test fun a_live_stream_is_never_searched() {
        assertFalse(shouldSearchTail(live = true, inputLength = 1_000_000_000L, windowBytes = WINDOW))
    }

    @Test fun an_unknown_length_is_never_searched() {
        assertFalse(shouldSearchTail(live = false, inputLength = C.LENGTH_UNSET.toLong(), windowBytes = WINDOW))
    }

    @Test fun a_file_no_longer_than_the_window_was_already_searched_whole() {
        assertFalse(shouldSearchTail(live = false, inputLength = WINDOW.toLong(), windowBytes = WINDOW))
    }

    @Test fun a_vod_file_longer_than_the_window_is_searched() {
        assertTrue(shouldSearchTail(live = false, inputLength = WINDOW + 1L, windowBytes = WINDOW))
    }

    // ---- applying the duration ------------------------------------------------------------------

    @Test fun a_title_whose_last_pcr_is_inside_the_window_costs_nothing_extra() {
        val ts = file(videoPackets = 200, trailingAudioBytes = 1_000)
        val run = run(ts)
        assertEquals("only the first extractor ever ran", 1, run.created)
        assertEquals("the fake's own two seeks (tail, back to 0), none of ours", 2, run.seeks)
        val map = run.output.seekMaps.single()
        assertTrue(map.isSeekable)
        assertEquals(199_000_000L, map.durationUs)
        assertEquals(listOf(ts.data.size.toLong()), run.lengthsSeen.distinct())
    }

    @Test fun a_title_whose_video_stops_long_before_the_end_gets_its_duration_and_seek_map() {
        val ts = file(videoPackets = 200, trailingAudioBytes = 10_000)
        val run = run(ts)
        assertEquals("a second extractor, told the trimmed length", 2, run.created)
        val map = run.output.seekMaps.single()
        assertTrue("seekable", map.isSeekable)
        assertEquals("first PCR 0 s, last 199 s", 199_000_000L, map.durationUs)
        assertEquals(ts.lastPcrEnd, run.delegates[1].lengthsSeen.last())
        // The abandoned extractor's sample was dropped: the queue only got the replacement's.
        assertEquals(1, run.output.track.samples)
    }

    @Test fun a_pcr_further_back_than_the_first_region_is_found_in_the_next() {
        val ts = file(videoPackets = 200, trailingAudioBytes = 30_000)
        val run = run(ts)
        assertEquals(199_000_000L, run.output.seekMaps.single().durationUs)
        assertEquals(ts.lastPcrEnd, run.delegates[1].lengthsSeen.last())
    }

    @Test fun no_pcr_within_the_search_leaves_it_unseekable_as_before() {
        val ts = file(videoPackets = 200, trailingAudioBytes = 100_000)
        val run = run(ts)
        val map = run.output.seekMaps.single()
        assertFalse(map.isSeekable)
        assertEquals(C.TIME_UNSET, map.durationUs)
        assertEquals(C.LENGTH_UNSET.toLong(), run.delegates[1].lengthsSeen.last())
        assertEquals(1, run.output.track.samples)
    }

    @Test fun a_search_past_its_budget_gives_up_instead_of_holding_the_film() {
        // Every read of the clock is 10 s later: the first region's second block is already late.
        var now = 0L
        val ts = file(videoPackets = 200, trailingAudioBytes = 100_000)
        val run = run(ts, clockMs = { now.also { now += 10_000L } })
        assertFalse(run.output.seekMaps.single().isSeekable)
        assertEquals(C.LENGTH_UNSET.toLong(), run.delegates[1].lengthsSeen.last())
    }

    @Test fun a_pcr_on_another_pid_is_not_taken_for_the_video_s() {
        // A stray PCR on another pid near the end: trimming there would hand the extractor a window
        // with no PCR on its own pid, and fail again.
        val ts = file(videoPackets = 200, trailingAudioBytes = 10_000, strayPcrPid = 0x1FF)
        val run = run(ts)
        assertEquals(199_000_000L, run.output.seekMaps.single().durationUs)
        assertEquals(ts.lastPcrEnd, run.delegates[1].lengthsSeen.last())
    }

    @Test fun an_unknown_length_is_passed_through_untouched() {
        val ts = file(videoPackets = 200, trailingAudioBytes = 10_000)
        val run = run(ts, lengthKnown = false)
        assertEquals(1, run.created)
        val map = run.output.seekMaps.single()
        assertFalse(map.isSeekable)
        assertNull(run.delegates[0].lengthsSeen.firstOrNull { it != C.LENGTH_UNSET.toLong() })
    }

    // ---- synthetic TS ---------------------------------------------------------------------------

    private class Ts(val data: ByteArray, val lastPcrEnd: Long)

    /** [videoPackets] PCR packets (one a second) then [trailingAudioBytes] of audio, packet-rounded. */
    private fun file(videoPackets: Int, trailingAudioBytes: Int, strayPcrPid: Int? = null): Ts {
        val out = java.io.ByteArrayOutputStream()
        repeat(videoPackets) { i -> out.write(pcrPacket(VIDEO, i * 90_000L)); out.write(plain(AUDIO)) }
        val lastPcrEnd = out.size().toLong() - MpegTs.PACKET // the last PCR packet, before its audio partner
        val audio = (trailingAudioBytes + MpegTs.PACKET - 1) / MpegTs.PACKET
        repeat(audio) { i ->
            out.write(if (strayPcrPid != null && i == audio - 30) pcrPacket(strayPcrPid, 1L) else plain(AUDIO))
        }
        return Ts(out.toByteArray(), lastPcrEnd)
    }

    private fun pcrPacket(pid: Int, base90k: Long): ByteArray {
        val p = ByteArray(MpegTs.PACKET) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = ((pid shr 8) and 0x1F).toByte(); p[2] = (pid and 0xFF).toByte()
        p[3] = 0x20; p[4] = 183.toByte(); p[5] = 0x10
        p[6] = ((base90k shr 25) and 0xFF).toByte(); p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte(); p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = ((base90k and 1L) shl 7).toByte(); p[11] = 0
        return p
    }

    private fun plain(pid: Int): ByteArray {
        val p = ByteArray(MpegTs.PACKET) { 0 }
        p[0] = 0x47; p[1] = ((pid shr 8) and 0x1F).toByte(); p[2] = (pid and 0xFF).toByte(); p[3] = 0x10
        return p
    }

    // ---- driver ---------------------------------------------------------------------------------

    private class Run(val output: FakeOutput, val delegates: List<FakeTs>, val seeks: Int) {
        val created get() = delegates.size
        val lengthsSeen get() = delegates.flatMap { it.lengthsSeen }
    }

    private fun run(ts: Ts, lengthKnown: Boolean = true, clockMs: () -> Long = { 0L }): Run {
        val delegates = mutableListOf<FakeTs>()
        val newTs = { FakeTs(WINDOW, VIDEO).also { delegates += it } }
        val extractor = TsTailPcrExtractor(newTs(), newTs, WINDOW, STEPS, budgetMs = 13_000L, clockMs = clockMs)
        val output = FakeOutput()
        extractor.init(output)
        var input = ArrayInput(ts.data, 0, lengthKnown)
        val holder = PositionHolder()
        var seeks = 0
        repeat(100_000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_SEEK -> { seeks++; input = ArrayInput(ts.data, holder.position, lengthKnown) }
                Extractor.RESULT_END_OF_INPUT -> return Run(output, delegates, seeks)
            }
        }
        error("never ended")
    }

    /** Reads its duration like media3's TsExtractor/TsDurationReader, then writes one sample. */
    private class FakeTs(private val window: Int, private val pid: Int) : Extractor {
        val lengthsSeen = mutableListOf<Long>()
        private lateinit var output: ExtractorOutput
        private lateinit var track: TrackOutput
        private var state = 0
        private var lastPcr = -1L
        private var durationUs = C.TIME_UNSET

        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            val length = input.length
            lengthsSeen += length
            when (state) {
                0 -> { // PAT/PMT: the first packets, read like TsExtractor does
                    input.readFully(ByteArray(MpegTs.PACKET * 2), 0, MpegTs.PACKET * 2)
                    track = output.track(1, C.TRACK_TYPE_VIDEO); track.format(Format.Builder().build()); output.endTracks()
                    state = if (length == C.LENGTH_UNSET.toLong()) 3 else 1
                }
                1 -> { // the tail window
                    val start = length - minOf(window.toLong(), length)
                    if (input.position != start) { seekPosition.position = start; return Extractor.RESULT_SEEK }
                    val buf = ByteArray((length - start).toInt()); input.readFully(buf, 0, buf.size)
                    lastPcr = MpegTs.located(buf).lastOrNull { it.pcr.pid == pid }?.pcr?.base90k ?: -1L
                    state = 2; seekPosition.position = 0; return Extractor.RESULT_SEEK
                }
                2 -> { // the head
                    if (lastPcr >= 0) {
                        val buf = ByteArray(minOf(window.toLong(), length).toInt()); input.peekFully(buf, 0, buf.size); input.resetPeekPosition()
                        val first = MpegTs.located(buf).first { it.pcr.pid == pid }.pcr.base90k
                        durationUs = (lastPcr - first) * 1_000_000 / MpegTs.PCR_HZ
                    }
                    state = 3
                }
                3 -> {
                    output.seekMap(if (durationUs == C.TIME_UNSET) SeekMap.Unseekable(C.TIME_UNSET) else Seekable(durationUs))
                    track.sampleData(ParsableByteArray(10), 10)
                    track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 10, 0, null)
                    state = 4
                }
                else -> return if (input.skip(64 * 1024) == C.RESULT_END_OF_INPUT) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
            }
            return Extractor.RESULT_CONTINUE
        }
    }

    private class Seekable(private val durationUs: Long) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(0, 0))
    }

    private class FakeOutput : ExtractorOutput {
        val seekMaps = mutableListOf<SeekMap>()
        val track = FakeTrack()
        override fun track(id: Int, type: Int): TrackOutput = track // same id → same queue, like ProgressiveMediaPeriod
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { seekMaps += seekMap }
    }

    private class FakeTrack : TrackOutput {
        var samples = 0
        var bytes = 0
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(ByteArray(length), 0, length); if (n > 0) bytes += n; return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length); bytes += length }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
    }

    /** A byte array opened at [start], like a data source reopened by a RESULT_SEEK. */
    private class ArrayInput(private val data: ByteArray, start: Long, private val lengthKnown: Boolean) : ExtractorInput {
        private var pos = start
        private var peek = start
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buffer, offset, n); pos += n; peek = pos
            return n
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) { if (allowEndOfInput && pos >= data.size) return false; throw java.io.EOFException() }
            System.arraycopy(data, pos.toInt(), target, offset, length); pos += length; peek = pos
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) { readFully(target, offset, length, false) }
        override fun skip(length: Int): Int = read(ByteArray(length), 0, length)
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = readFully(ByteArray(length), 0, length, allowEndOfInput)
        override fun skipFully(length: Int) { skipFully(length, false) }
        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peek >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - peek).toInt()
            System.arraycopy(data, peek.toInt(), target, offset, n); peek += n
            return n
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) { if (allowEndOfInput) return false; throw java.io.EOFException() }
            System.arraycopy(data, peek.toInt(), target, offset, length); peek += length
            return true
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) { peekFully(target, offset, length, false) }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean { peek += length; return true }
        override fun advancePeekPosition(length: Int) { peek += length }
        override fun resetPeekPosition() { peek = pos }
        override fun getPeekPosition() = peek
        override fun getPosition() = pos
        override fun getLength() = if (lengthKnown) data.size.toLong() else C.LENGTH_UNSET.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) = throw e
    }
}
