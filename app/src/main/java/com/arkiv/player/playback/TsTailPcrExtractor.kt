package com.arkiv.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor

/**
 * Gives a progressive MPEG-TS whose video ends well before the file does its duration back, and with
 * it ExoPlayer's own seeking.
 *
 * ExoPlayer reads a TS's duration by looking for the last PCR in the final `timestampSearchBytes`
 * of the file ([com.arkiv.player.ui.player.STREAM_TS_SEARCH_BYTES], 256 KB). The PCR rides on the
 * video, and some titles keep muxing audio long after their last video packet: measured on a Xuper
 * movie (1.18 GB, 2 h 07 min) the last PCR sits 652 548 bytes before the end. The search then finds
 * none and the extractor gives up without a word -- `dur=TIME_UNSET`, an unseekable seek map, a bar
 * with nothing to draw, every seek ignored, and no progress saved (`saveProgress` needs a duration).
 *
 * Widening the window for every title is not the answer: the same window is what each step of
 * ExoPlayer's binary-search seek reads, so a wider one makes EVERY seek of EVERY title slower against
 * a CDN that takes 0.2 s to 20 s per range. Instead this wraps the [TsExtractor] and only acts when it
 * fails:
 *  1. The TsExtractor runs exactly as before. If it gets a duration, nothing else happens -- not one
 *     extra byte is requested.
 *  2. If it emits an unseekable map with no duration while the file's length is known, that map is
 *     held back (and the abandoned extractor's output dropped), and this searches backwards from
 *     the end of the file, through the SAME [ExtractorInput] -- so the same data source, the same
 *     plugin host gate and headers as playback -- for the last PCR on the stream's PCR pid.
 *  3. A fresh TsExtractor is then run from byte 0 over an input whose length ends right after that
 *     PCR. Its duration read now finds it, and it builds its normal binary-search seek map over the
 *     bytes that have video. The bytes after it still play: only the length the extractor is TOLD
 *     about changes, never what is read.
 *  4. Nothing found within [searchSteps] or [budgetMs]: a fresh extractor told the length is
 *     unknown, which is exactly the unseekable outcome there was before, without another tail read.
 *
 * Only for VOD: a live channel never gets this ([streamExtractorsFactory]), and a stream with no
 * known length (a live `.ts` has none) is left alone even if it did ([shouldSearchTail]).
 */
@OptIn(UnstableApi::class)
internal class TsTailPcrExtractor(
    /** The TsExtractor media3 built: it runs first, untouched. */
    first: Extractor,
    /** A brand-new TsExtractor configured like the first one (same search window, same subtitle parser). */
    private val newDelegate: () -> Extractor,
    /** The delegate's own tail window: the stretch already known to hold no PCR when it fails. */
    private val windowBytes: Int,
    /** How far back from the end each search region reaches, nearest first. */
    private val searchSteps: LongArray = DEFAULT_SEARCH_STEPS,
    private val budgetMs: Long = TsDurationProbe.BUDGET_MS,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : Extractor {

    private enum class Phase { WATCH, PASS, SEARCH, TRIMMED }

    private var phase = Phase.WATCH
    private lateinit var output: ExtractorOutput
    private var delegate: Extractor = first
    private var gate: GateOutput? = null

    /** The length the current delegate is told: the real one in WATCH, the trimmed one (or unset) after. */
    private var reportedLength: Long = C.LENGTH_UNSET.toLong()
    private val view = LengthView()

    /** The first bytes of the file as the first delegate read them: where the PCR pid is learned. */
    private val head = ByteArray(HEAD_BYTES)
    private var headFilled = 0

    // SEARCH state.
    private var inputLength = C.LENGTH_UNSET.toLong()
    private var pcrPid: Int? = null
    private var step = 0
    private var regionStart = 0L
    private var regionEnd = 0L
    private var cursor = 0L
    private var searchStartedAt = 0L
    private var lastPcrEnd = -1L
    private val block = ByteArray(OVERLAP + BLOCK_BYTES)
    private var carry = 0

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        this.output = output
        gate = GateOutput(output, intercept = true).also { delegate.init(it) }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = when (phase) {
        Phase.WATCH -> watch(input, seekPosition)
        Phase.PASS -> delegate.read(input, seekPosition)
        Phase.SEARCH -> search(input, seekPosition)
        Phase.TRIMMED -> delegate.read(view.over(input, reportedLength), seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        // A seek is only possible once a seekable map went out, i.e. never during SEARCH.
        if (phase != Phase.SEARCH) delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun watch(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val g = gate!!
        // Asked on every read because the length is the input's: a held map with no length to
        // search would leave the player waiting for a map that never comes.
        g.intercept = shouldSearchTail(live = false, inputLength = input.length, windowBytes = windowBytes)
        val result = delegate.read(view.recording(input), seekPosition)
        if (g.failed) return startSearch(input, seekPosition)
        if (g.sentSeekMap) {
            // The usual case, done: from here on the delegate reads the real input, unwrapped.
            phase = Phase.PASS
        }
        return result
    }

    private fun startSearch(input: ExtractorInput, seekPosition: PositionHolder): Int {
        phase = Phase.SEARCH
        inputLength = input.length
        pcrPid = MpegTs.firstPcr(head.copyOf(headFilled))?.pcr?.pid
        searchStartedAt = clockMs()
        step = 0
        // The delegate already read [length - window, length) and found nothing there. The overlap
        // lets a PCR packet that straddles the window's edge be seen whole.
        regionEnd = (inputLength - windowBytes + OVERLAP).coerceAtMost(inputLength)
        android.util.Log.w(TAG, "no PCR in the last $windowBytes B of $inputLength B (pid=$pcrPid): searching further back")
        return nextRegion(seekPosition)
    }

    /** Starts the next region back from [regionEnd], or gives up when out of steps or time. */
    private fun nextRegion(seekPosition: PositionHolder): Int {
        if (step >= searchSteps.size || clockMs() - searchStartedAt > budgetMs) return giveUp(seekPosition)
        regionStart = (inputLength - searchSteps[step]).coerceAtLeast(0L)
        step++
        if (regionStart >= regionEnd) return nextRegion(seekPosition)
        cursor = regionStart
        carry = 0
        seekPosition.position = cursor
        return Extractor.RESULT_SEEK
    }

    /** Reads one block of the current region per call, so the loader can still be cancelled between them. */
    private fun search(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (input.position != cursor) {
            seekPosition.position = cursor
            return Extractor.RESULT_SEEK
        }
        val n = minOf(BLOCK_BYTES.toLong(), regionEnd - cursor).toInt()
        val complete = n > 0 && input.readFully(block, carry, n, /* allowEndOfInput = */ true)
        if (complete) {
            val blockStart = cursor - carry
            val found = MpegTs.located(block.copyOf(carry + n)).lastOrNull { pcrPid == null || it.pcr.pid == pcrPid }
            if (found != null) lastPcrEnd = maxOf(lastPcrEnd, blockStart + found.offset + MpegTs.PACKET)
            val keep = minOf(OVERLAP, carry + n)
            System.arraycopy(block, carry + n - keep, block, 0, keep)
            carry = keep
            cursor += n
        }
        if (complete && cursor < regionEnd) {
            // The film is waiting on this: past the budget it starts with the best PCR seen so far
            // (a duration a little short beats none), or without a duration as before.
            if (clockMs() - searchStartedAt <= budgetMs) return Extractor.RESULT_CONTINUE
            return if (lastPcrEnd > 0) trimTo(lastPcrEnd, seekPosition) else giveUp(seekPosition)
        }
        // Region done. The nearest region is searched first, so whatever it found is the file's last PCR.
        if (lastPcrEnd > 0) return trimTo(lastPcrEnd, seekPosition)
        regionEnd = (regionStart + OVERLAP).coerceAtMost(inputLength)
        return nextRegion(seekPosition)
    }

    private fun trimTo(end: Long, seekPosition: PositionHolder): Int {
        android.util.Log.w(
            TAG,
            "last PCR ends ${inputLength - end} B before the end of the file (${clockMs() - searchStartedAt} ms): " +
                "the extractor is told length=$end so it can read the duration and seek",
        )
        return restart(end, seekPosition)
    }

    private fun giveUp(seekPosition: PositionHolder): Int {
        android.util.Log.w(TAG, "no PCR found within ${searchSteps.lastOrNull()} B of the end: no duration, as before")
        return restart(C.LENGTH_UNSET.toLong(), seekPosition)
    }

    /** A fresh delegate from byte 0, told [length]; its seek map goes straight out, whatever it is. */
    private fun restart(length: Long, seekPosition: PositionHolder): Int {
        delegate.release()
        delegate = newDelegate()
        gate = GateOutput(output, intercept = false).also { delegate.init(it) }
        reportedLength = length
        phase = Phase.TRIMMED
        seekPosition.position = 0
        return Extractor.RESULT_SEEK
    }

    /**
     * The delegate's output. With [intercept] on, a "no duration, not seekable" map is held back
     * instead of sent, and from then on everything this delegate writes is dropped: it is about to
     * be replaced, and the replacement writes its tracks (same ids, same queues) and samples anew.
     */
    private class GateOutput(private val real: ExtractorOutput, var intercept: Boolean) : ExtractorOutput {
        var failed = false
            private set
        var sentSeekMap = false
            private set

        override fun track(id: Int, type: Int): TrackOutput = GateTrack(real.track(id, type)) { failed }
        override fun endTracks() = real.endTracks()
        override fun seekMap(seekMap: SeekMap) {
            if (intercept && !seekMap.isSeekable && seekMap.durationUs == C.TIME_UNSET) {
                failed = true
                return
            }
            if (failed) return
            sentSeekMap = true
            real.seekMap(seekMap)
        }
    }

    private class GateTrack(private val real: TrackOutput, private val dropped: () -> Boolean) : TrackOutput {
        private val scratch by lazy { ByteArray(4096) }
        override fun format(format: Format) = real.format(format)
        override fun durationUs(durationUs: Long) = real.durationUs(durationUs)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            if (dropped()) {
                val read = input.read(scratch, 0, minOf(length, scratch.size))
                if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
                read
            } else {
                real.sampleData(input, length, allowEndOfInput, sampleDataPart)
            }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (dropped()) data.skipBytes(length) else real.sampleData(data, length, sampleDataPart)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!dropped()) real.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    /**
     * The input as the delegate sees it: the same bytes, with [getLength] replaced in TRIMMED, and the
     * first [HEAD_BYTES] copied aside in WATCH. One instance, re-pointed on every read (a read is
     * one TS packet: a new wrapper each time would be millions of allocations per film).
     */
    private inner class LengthView : ExtractorInput {
        private lateinit var inner: ExtractorInput
        private var length = C.LENGTH_UNSET.toLong()
        private var record = false

        fun over(input: ExtractorInput, length: Long): ExtractorInput {
            inner = input; this.length = length; record = false
            return this
        }

        fun recording(input: ExtractorInput): ExtractorInput {
            inner = input; length = input.length; record = true
            return this
        }

        private fun keep(buffer: ByteArray, offset: Int, from: Long, count: Int) {
            if (!record || count <= 0 || from >= HEAD_BYTES) return
            val n = minOf(count.toLong(), HEAD_BYTES - from).toInt()
            if (from > headFilled) return // a gap: only a contiguous head is useful
            System.arraycopy(buffer, offset, head, from.toInt(), n)
            headFilled = maxOf(headFilled, (from + n).toInt())
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val at = inner.position
            return inner.read(buffer, offset, length).also { keep(buffer, offset, at, it) }
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = inner.position
            return inner.readFully(target, offset, length, allowEndOfInput).also { if (it) keep(target, offset, at, length) }
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = inner.position
            inner.readFully(target, offset, length)
            keep(target, offset, at, length)
        }
        override fun skip(length: Int): Int = inner.skip(length)
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = inner.skipFully(length, allowEndOfInput)
        override fun skipFully(length: Int) = inner.skipFully(length)
        override fun peek(target: ByteArray, offset: Int, length: Int): Int = inner.peek(target, offset, length)
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            inner.peekFully(target, offset, length, allowEndOfInput)
        override fun peekFully(target: ByteArray, offset: Int, length: Int) = inner.peekFully(target, offset, length)
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean = inner.advancePeekPosition(length, allowEndOfInput)
        override fun advancePeekPosition(length: Int) = inner.advancePeekPosition(length)
        override fun resetPeekPosition() = inner.resetPeekPosition()
        override fun getPeekPosition(): Long = inner.peekPosition
        override fun getPosition(): Long = inner.position
        override fun getLength(): Long = length
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) = inner.setRetryPosition(position, e)
    }

    companion object {
        private const val TAG = "ArkivTsDur"
        /** Enough to hold the first PCR: TsExtractor fills 50 packets (9 400 B) at a time. */
        private const val HEAD_BYTES = 64 * 1024
        private const val BLOCK_BYTES = 64 * 1024
        /** One packet minus a byte: a packet cut by a block edge is whole in the next block. */
        private const val OVERLAP = MpegTs.PACKET - 1
        /**
         * 1 MiB, then 4 MiB, from the end. The measured worst title needs 652 KB; each region is one
         * ranged request, so two of them keep a failure to a couple of extra requests.
         */
        val DEFAULT_SEARCH_STEPS = longArrayOf(1L shl 20, 4L shl 20)
    }
}

/**
 * Whether a TS whose own duration read failed is worth searching further back for its last PCR:
 * VOD with a known length longer than the window the extractor already searched. A live stream
 * never (it has no end to search, and a length a live server reports is not one); an unknown
 * length has nowhere to seek to; a file no longer than the window was already searched whole.
 */
internal fun shouldSearchTail(live: Boolean, inputLength: Long, windowBytes: Int): Boolean =
    !live && inputLength != C.LENGTH_UNSET.toLong() && inputLength > windowBytes

/**
 * The extractors a [com.arkiv.player.ui.player.StreamExoPlayer] stream is read with: media3's
 * defaults with the TS timestamp window set to [tsSearchBytes] and, for VOD only, the TsExtractor
 * wrapped in [TsTailPcrExtractor]. A live channel gets exactly the plain factory it had before.
 */
@OptIn(UnstableApi::class)
internal fun streamExtractorsFactory(live: Boolean, tsSearchBytes: Int): ExtractorsFactory {
    val inner = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(tsSearchBytes)
    return if (live) inner else TsTailExtractorsFactory(inner, tsSearchBytes)
}

/** [inner]'s extractors with the TsExtractor wrapped; every setter media3 calls on it reaches [inner]. */
@OptIn(UnstableApi::class)
private class TsTailExtractorsFactory(
    private val inner: DefaultExtractorsFactory,
    private val windowBytes: Int,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors()) { inner.createExtractors() }

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(inner.createExtractors(uri, responseHeaders)) { inner.createExtractors(uri, responseHeaders) }

    private fun wrap(extractors: Array<Extractor>, again: () -> Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e.underlyingImplementation !is TsExtractor) e
            else TsTailPcrExtractor(e, newDelegate = { again().first { it.underlyingImplementation is TsExtractor } }, windowBytes = windowBytes)
        }

    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory =
        apply { inner.experimentalSetTextTrackTranscodingEnabled(enabled) }
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory =
        apply { inner.setSubtitleParserFactory(factory) }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int): ExtractorsFactory =
        apply { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags) }
    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory =
        apply { inner.setParseHagcMetadata(parseHagcMetadata) }
}
