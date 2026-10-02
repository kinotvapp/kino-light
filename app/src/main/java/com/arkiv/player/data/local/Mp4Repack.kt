package com.arkiv.player.data.local

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Rewrites a downloaded video (MPEG-TS, Matroska/WebM, AVI…) as a faststart MP4 WITHOUT re-encoding:
 * the container changes, every compressed sample is copied as it is (HEVC stays HEVC), like
 * `ffmpeg -c copy -movflags +faststart`.
 *
 * Why not media3's Transformer, which `TsRemuxer` already uses for the cast remux: Transformer
 * writes exactly ONE audio track, and a download has to keep all of them (spa/spa/eng/ger…). So
 * this is a direct demux→mux: media3's own extractor ([DefaultExtractorsFactory], the same parsers
 * the player reads the file with) feeds media3's [Mp4Muxer] with every video and audio track, in
 * the order the container lists them, each with its language. Text and metadata tracks are left out
 * (the muxer has no text tracks; subtitles become SRT sidecars, see [Mp4SubtitleSidecars]).
 *
 * What [Mp4Muxer] cannot carry (AC-3, E-AC-3, MP2/MP3 audio, MPEG-2 video…) makes the whole file
 * [Result.Unsupported]: dropping one audio track would lose a language, so the original is kept.
 *
 * Timing: every track's timestamps are moved by the SAME constant (the earliest sample buffered
 * before the muxer starts), so the offsets between tracks -- audio starting 21 ms before the video,
 * a second audio 500 ms later -- are what the muxer's edit lists then state.
 *
 * Faststart without a second copy: the muxer is asked for a `free` box right after `ftyp`, sized
 * for the `moov` the title will need ([Mp4FastStart.moovBudget]); when it is done the `moov` (written
 * at the end) moves into that hole and the file is truncated. The chunk offsets never change, since
 * `mdat` never moves. A budget that turns out short leaves the `moov` at the end: still a valid MP4.
 *
 * Writes `<output>.part` and renames on success; cancellation (the coroutine) and every failure
 * delete the partial.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Mp4Repackager(
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val checkEveryBytes: Long = FreeSpacePolicy.CHECK_EVERY_BYTES,
) {

    sealed interface Result {
        data class Done(
            val file: File,
            val videoTracks: Int,
            val audioTracks: Int,
            /** Whether the `moov` ended up in front of the `mdat`. */
            val fastStart: Boolean,
            val elapsedMs: Long,
        ) : Result

        /** Something in it an MP4 cannot carry as-is ([reason] names it). The original stays. */
        data class Unsupported(val reason: String) : Result

        /** The disk filled up while writing (see [FreeSpacePolicy]). */
        data class NoSpace(val availableBytes: Long) : Result

        data class Failed(val reason: String, val cause: Throwable? = null) : Result
    }

    /**
     * [input] as a faststart MP4 at [output]. [onlyAudio]: keep just the audio track at that ordinal
     * (among the container's audio tracks, in its order), for a Chromecast that plays one audio
     * only; null keeps all. [onProgress] gets 0..100 as the input is read.
     */
    suspend fun repack(
        input: File,
        output: File,
        onlyAudio: Int? = null,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val partial = File(output.parentFile, output.name + ".part")
        val t0 = System.currentTimeMillis()
        try {
            val result = run(input, partial, onlyAudio, onProgress) { coroutineContext.ensureActive() }
            if (result !is Result.Done) {
                partial.delete()
                return@withContext result
            }
            if (output.exists()) output.delete()
            if (!partial.renameTo(output)) {
                partial.delete()
                return@withContext Result.Failed("rename failed")
            }
            result.copy(file = output, elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            partial.delete()
            throw e
        } catch (e: Throwable) {
            partial.delete()
            Result.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun run(input: File, partial: File, onlyAudio: Int?, onProgress: (Int) -> Unit, checkCancelled: () -> Unit): Result {
        val length = input.length()
        RandomAccessFile(input, "r").use { raf ->
            val extractor = pickExtractor(raf, length) ?: return Result.Unsupported("unknown container")
            val writer = Writer(partial, onlyAudio, length)
            val output = Collector(writer)
            extractor.init(output)
            val holder = PositionHolder()
            var position = 0L
            var source = open(raf, position, length)
            var lastPercent = -1
            var reads = 0
            try {
                while (true) {
                    val r = extractor.read(source, holder)
                    if (r == Extractor.RESULT_END_OF_INPUT) break
                    if (r == Extractor.RESULT_SEEK) {
                        position = holder.position
                        source = open(raf, position, length)
                    }
                    writer.failure?.let { return it }
                    if (++reads % 64 == 0) {
                        checkCancelled()
                        val percent = if (length > 0) ((source.position * 100) / length).toInt().coerceIn(0, 99) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                        if (writer.writtenSinceCheck >= checkEveryBytes) {
                            writer.writtenSinceCheck = 0
                            val free = freeSpace(partial.parentFile ?: partial)
                            if (FreeSpacePolicy.isExhausted(free)) {
                                writer.abort()
                                return Result.NoSpace(free)
                            }
                        }
                    }
                }
                writer.failure?.let { return it }
                val done = writer.finish() ?: return writer.failure ?: Result.Failed("nothing to write")
                onProgress(100)
                return done
            } finally {
                writer.abort()
                runCatching { extractor.release() }
            }
        }
    }

    /** The first of media3's extractors that recognizes the bytes. */
    private fun pickExtractor(raf: RandomAccessFile, length: Long): Extractor? {
        val factory = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(false)
        for (candidate in factory.createExtractors()) {
            val ok = runCatching { candidate.sniff(open(raf, 0L, length)) }.getOrDefault(false)
            if (ok) return candidate
            runCatching { candidate.release() }
        }
        return null
    }

    private fun open(raf: RandomAccessFile, position: Long, length: Long): DefaultExtractorInput {
        raf.seek(position)
        val reader = DataReader { buffer, offset, len ->
            if (len == 0) return@DataReader 0
            val n = raf.read(buffer, offset, len)
            if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        return DefaultExtractorInput(reader, position, length)
    }

    /** One sample as the extractor finished it. */
    private class Sample(val sink: Sink, val timeUs: Long, val flags: Int, val data: ByteArray)

    /** The extractor's side: a [Sink] per audio/video track, nothing kept of the rest. */
    private class Collector(private val writer: Writer) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput =
            if (type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO) Sink(type, writer).also { writer.sinks += it }
            else DiscardingTrackOutput()

        override fun endTracks() {
            writer.tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            writer.durationUs = seekMap.durationUs
        }
    }

    /** One track's bytes: they arrive in pieces, and a sample is the last `size` bytes before `offset` when its metadata comes. */
    private class Sink(val type: Int, private val writer: Writer) : TrackOutput {
        var format: Format? = null
        private var bytes = ByteArray(64 * 1024)
        private var size = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            ensure(length)
            val n = input.read(bytes, size, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            size += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensure(length)
            data.readBytes(bytes, size, length)
            size += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = this.size - offset
            val start = end - size
            if (start < 0 || end < 0) return
            val data = bytes.copyOfRange(start, end)
            // What came after this sample (the next one's first bytes) moves to the front.
            System.arraycopy(bytes, end, bytes, 0, this.size - end)
            this.size -= end
            writer.sample(Sample(this, timeUs, flags, data))
        }

        private fun ensure(more: Int) {
            if (size + more <= bytes.size) return
            bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        }
    }

    /**
     * The muxer's side. Samples are held until every track has its format (an MPEG-TS declares its
     * tracks long before each one's first frame tells its codec), then the muxer is built with all
     * of them and the held samples go first.
     */
    private inner class Writer(private val partial: File, private val onlyAudio: Int?, private val inputLength: Long) {
        val sinks = mutableListOf<Sink>()
        var tracksEnded = false
        var durationUs = C.TIME_UNSET
        var failure: Result? = null
        var writtenSinceCheck = 0L

        private val held = ArrayList<Sample>()
        private var heldBytes = 0L
        private var muxer: Mp4Muxer? = null
        private var stream: FileOutputStream? = null
        private val trackIds = HashMap<Sink, Int>()
        private var base = 0L
        private var budget = 0
        private var videoCount = 0
        private var audioCount = 0

        fun sample(s: Sample) {
            if (failure != null) return
            val m = muxer
            if (m != null) {
                write(m, s)
                return
            }
            held += s
            heldBytes += s.data.size
            val allKnown = tracksEnded && sinks.isNotEmpty() && sinks.all { it.format != null }
            if (allKnown || heldBytes > MAX_HELD_BYTES) start()
        }

        /** Builds the muxer once the tracks are known (or at the end, with what is known). */
        private fun start() {
            val known = sinks.filter { it.format != null }
            val video = known.filter { it.type == C.TRACK_TYPE_VIDEO }
            val audioAll = known.filter { it.type == C.TRACK_TYPE_AUDIO }
            val audio = if (onlyAudio != null) listOfNotNull(audioAll.getOrNull(onlyAudio) ?: audioAll.firstOrNull()) else audioAll
            if (video.isEmpty()) {
                failure = Result.Unsupported("no video track")
                return
            }
            video.firstOrNull { it.format!!.sampleMimeType !in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES }?.let {
                failure = Result.Unsupported("video ${it.format!!.sampleMimeType}")
                return
            }
            audio.firstOrNull { it.format!!.sampleMimeType !in Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES }?.let {
                failure = Result.Unsupported("audio ${it.format!!.sampleMimeType}")
                return
            }
            val kept = video + audio
            base = held.filter { it.sink in kept }.minOfOrNull { it.timeUs }?.coerceAtLeast(0L) ?: 0L
            budget = Mp4FastStart.moovBudget(
                durationUs = durationUs.takeIf { it != C.TIME_UNSET && it > 0 } ?: Mp4FastStart.durationGuessUs(inputLength),
                videoRates = video.map { sink -> rateOf(sink) ?: sink.format!!.frameRate.toDouble().takeIf { it > 0 } ?: Mp4FastStart.VIDEO_RATE_GUESS },
                audioRates = audio.map { sink -> rateOf(sink) ?: sink.format!!.sampleRate.takeIf { it > 0 }?.let { it / 1024.0 } ?: Mp4FastStart.AUDIO_RATE_GUESS },
            )
            val out = FileOutputStream(partial)
            stream = out
            // Not the muxer's own "streamable" mode: it reserves a fixed 400 KB for the moov, which a
            // feature film overflows (moov at the end anyway) and a short clip wastes. The `free` box
            // sized for this title is where [Mp4FastStart.relocate] puts the moov instead.
            val m = Mp4Muxer.Builder(out)
                .setAttemptStreamableOutputEnabled(false)
                // Chunks of ~1 s per track instead of one chunk per sample: an 8-byte chunk offset
                // per sample was a third of the moov, and the reads on playback are longer runs.
                .setSampleBatchingEnabled(true)
                .experimentalSetFreeSpaceAfterFileTypeBox(budget)
                .build()
            muxer = m
            kept.forEach { sink -> trackIds[sink] = m.addTrack(sink.format!!) }
            videoCount = video.size
            audioCount = audio.size
            val pending = held.toList()
            held.clear()
            heldBytes = 0
            pending.forEach { write(m, it) }
        }

        /** Samples per second of [sink] over what was held, when there is enough of it to say. */
        private fun rateOf(sink: Sink): Double? {
            val times = held.filter { it.sink == sink }.map { it.timeUs }
            if (times.size < 10) return null
            val span = (times.max() - times.min()) / 1_000_000.0
            return if (span >= 0.2) (times.size - 1) / span else null
        }

        private fun write(m: Mp4Muxer, s: Sample) {
            val id = trackIds[s.sink] ?: return
            val time = (s.timeUs - base).coerceAtLeast(0L)
            val flags = if (s.flags and C.BUFFER_FLAG_KEY_FRAME != 0) C.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(id, ByteBuffer.wrap(s.data), BufferInfo(time, s.data.size, flags))
            writtenSinceCheck += s.data.size
        }

        /** Closes the muxer and moves the `moov` to the front; null when nothing was ever written. */
        fun finish(): Result.Done? {
            if (muxer == null && failure == null) start()
            val m = muxer ?: return null
            if (failure != null) return null
            m.close()
            muxer = null
            runCatching { stream?.close() }
            stream = null
            val fast = Mp4FastStart.relocate(partial)
            return Result.Done(partial, videoCount, audioCount, fast, 0L)
        }

        /** Drops the muxer without finishing it (a failure or a stop): the partial is deleted by the caller. */
        fun abort() {
            runCatching { muxer?.close() }
            muxer = null
            runCatching { stream?.close() }
            stream = null
        }
    }

    private companion object {
        /** How much is held waiting for a track's format before starting with the tracks that have one. */
        const val MAX_HELD_BYTES = 48L * 1024 * 1024
    }
}

/**
 * Faststart for an MP4 whose `moov` was written at the end, in place: into the `free` box the
 * muxer left after `ftyp` (see [Mp4Repackager]). Pure file surgery, no Android.
 */
object Mp4FastStart {

    data class Box(val type: String, val offset: Long, val size: Long)

    /** The top-level boxes of [raf], in order; stops at anything that does not add up. */
    fun topLevelBoxes(raf: RandomAccessFile): List<Box> {
        val boxes = ArrayList<Box>()
        val length = raf.length()
        var at = 0L
        val header = ByteArray(16)
        while (at + 8 <= length) {
            raf.seek(at)
            raf.readFully(header, 0, 8)
            var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (at + 16 > length) break
                raf.readFully(header, 8, 8)
                size = ByteBuffer.wrap(header, 8, 8).long
            } else if (size == 0L) {
                size = length - at
            }
            if (size < 8 || at + size > length) break
            boxes += Box(type, at, size)
            at += size
        }
        return boxes
    }

    /** Whether [file]'s `moov` comes before its `mdat` (or it has no `mdat`). */
    fun isFastStart(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        val boxes = topLevelBoxes(raf)
        val moov = boxes.indexOfFirst { it.type == "moov" }
        val mdat = boxes.indexOfFirst { it.type == "mdat" }
        moov >= 0 && (mdat < 0 || moov < mdat)
    }

    /**
     * Moves the trailing `moov` into the `free` box in front of `mdat` when it fits (what is left of
     * the hole stays a smaller `free` box) and cuts the file where the `moov` was. True when the file
     * is faststart afterwards.
     */
    fun relocate(file: File): Boolean = RandomAccessFile(file, "rw").use { raf ->
        val boxes = topLevelBoxes(raf)
        val moovIndex = boxes.indexOfFirst { it.type == "moov" }
        val mdatIndex = boxes.indexOfFirst { it.type == "mdat" }
        if (moovIndex < 0) return@use false
        if (mdatIndex < 0 || moovIndex < mdatIndex) return@use true
        val moov = boxes[moovIndex]
        // Only the trailing moov: anything after it would be cut off.
        if (moov.offset + moov.size != raf.length()) return@use false
        val hole = boxes.subList(0, mdatIndex).lastOrNull { it.type == "free" } ?: return@use false
        val rest = hole.size - moov.size
        if (rest != 0L && rest < 8) return@use false
        if (moov.size > Int.MAX_VALUE) return@use false
        val bytes = ByteArray(moov.size.toInt())
        raf.seek(moov.offset)
        raf.readFully(bytes)
        raf.seek(hole.offset)
        raf.write(bytes)
        if (rest > 0) {
            val freeHeader = ByteBuffer.allocate(8).putInt(rest.toInt()).put("free".toByteArray(Charsets.ISO_8859_1)).array()
            raf.write(freeHeader)
        }
        raf.setLength(moov.offset)
        true
    }

    /** Frames per second assumed for a video track whose rate nothing tells. */
    const val VIDEO_RATE_GUESS = 60.0

    /** AAC frames per second (48 kHz) assumed for an audio track whose rate nothing tells. */
    const val AUDIO_RATE_GUESS = 47.0

    /**
     * Room for the `moov` of a title [durationUs] long, given each track's samples per second: per
     * video sample its size (stsz) and composition offset (ctts) plus a share of the sync and chunk
     * tables; per audio frame its size and a share of the chunk table (measured on the fixture with
     * ~1 s chunks: 12.1 and 4.1 bytes). Plus 5% and 30 s of slack, a fixed base and a quarter more
     * for safety: ~13 MB for a two-hour film with four audio tracks, under 1% of it (what is left
     * over stays a `free` box).
     */
    fun moovBudget(durationUs: Long, videoRates: List<Double>, audioRates: List<Double>): Int {
        val seconds = durationUs / 1_000_000.0 * 1.05 + 30.0
        val perSecond = videoRates.sumOf { it.coerceIn(1.0, 240.0) * VIDEO_SAMPLE_BYTES } +
            audioRates.sumOf { it.coerceIn(1.0, 400.0) * AUDIO_SAMPLE_BYTES }
        val bytes = seconds * perSecond * 1.25 + 64 * 1024
        return bytes.coerceAtMost(256.0 * 1024 * 1024).toInt()
    }

    private const val VIDEO_SAMPLE_BYTES = 14.0
    private const val AUDIO_SAMPLE_BYTES = 6.0

    /** A duration the input cannot exceed when the container did not say: its size at 300 kbit/s. */
    fun durationGuessUs(bytes: Long): Long = bytes * 8 * 1_000_000 / 300_000
}
