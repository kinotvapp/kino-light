package com.arkiv.player.playback

/**
 * The locator as released in 0.9.45 (a1d5bd15), kept verbatim as the yardstick [TsStartLocateCostTest]
 * measures [TsStartLocator] against: the same keyframe, in fewer round trips.
 */
class LegacyTsStartLocator(
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
