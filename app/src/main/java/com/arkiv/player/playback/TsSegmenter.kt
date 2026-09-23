package com.arkiv.player.playback

/**
 * Cuts an MPEG transport stream into HLS segments addressed by BYTE RANGE.
 *
 * Nothing is copied, converted or re-muxed: every segment is an interval of the very same file,
 * and HLS segments are themselves MPEG-TS. Only the way the stream is ANNOUNCED changes -- which
 * is the whole point, because the Cast receiver refuses a bare transport stream served
 * progressively ("FFmpegDemuxer: open context failed", read off the receiver's own log) while it
 * plays the identical bytes offered as a playlist.
 *
 * Boundaries land on packets that carry a PCR, and each `#EXTINF` is the DIFFERENCE between one
 * boundary's PCR and the next. The spike this replaces prorated durations from the byte size,
 * which is why its seeking was bad: the receiver's idea of "minute 12" drifted from the bytes
 * actually at minute 12, and a variable-bitrate stream drifts a lot.
 *
 * **The file is never scanned end to end.** A 950 MB title is five million packets; reading all of
 * them to build a playlist would cost more than the playback it enables. Each boundary is probed
 * instead: seek to the byte where that second should roughly land, read a small window, take the
 * first PCR in it. The estimate only has to be close enough to land inside the window -- the PCR
 * it finds is what makes the answer exact, not the estimate.
 *
 * Pure: the caller supplies the reader, so this is testable without a file or a device.
 */
object TsSegmenter {

    /**
     * How much is read at each probe. 64 KB is ~348 packets, and the standard puts a PCR at least
     * every 100 ms, so a window this size contains one unless the stream is badly malformed.
     */
    const val PROBE_BYTES = 64 * 1024

    /** Guard against a pathological input producing a playlist with tens of thousands of entries. */
    private const val MAX_SEGMENTS = 4_000

    /**
     * How many windows to walk forward looking for the next random access point before giving up
     * on a boundary. Keyframes are typically 2-10 s apart, so one 64 KB window frequently holds
     * none; a handful covers a long GOP without turning the probe into a scan.
     */
    private const val MAX_WINDOWS_PER_BOUNDARY = 24

    /**
     * One segment: an interval of the source file, with the duration its PCRs actually span.
     * [start] is always a packet boundary.
     */
    data class Segment(val start: Long, val length: Long, val durationSec: Double)

    /**
     * Cuts [totalBytes] of transport stream into segments of about [targetSec] each.
     *
     * [read] takes a byte offset and a size and returns what it could read there (shorter near
     * EOF, empty past it). Returns an empty list when this cannot be anchored -- no PCR, an empty
     * file, an unbelievable duration -- so the caller can fall back rather than serve a playlist
     * built on guesses.
     */
    fun segment(
        totalBytes: Long,
        targetSec: Double,
        read: (offset: Long, size: Int) -> ByteArray,
    ): List<Segment> {
        if (totalBytes <= 0L || targetSec <= 0.0) return emptyList()

        // The anchor is a random access point, and its pid is the clock every later probe
        // must match, so two muxed programs cannot be mixed into one nonsense timeline. If the
        // stream has none, there is nothing here a receiver could start decoding: say so rather
        // than hand back boundaries it will choke on.
        val head = MpegTs.firstRandomAccess(read(0L, PROBE_BYTES)) ?: return emptyList()
        val pid = head.pcr.pid

        val lastPcr = tailPcr(totalBytes, pid, read) ?: return emptyList()
        val totalSec = MpegTs.deltaSeconds(head.pcr.base90k, lastPcr)
        if (totalSec <= 0.0) return emptyList()

        // Shorter than one segment: one segment covering everything. Splitting here would only
        // produce a zero-length tail.
        if (totalSec <= targetSec) return listOf(Segment(0L, totalBytes, totalSec))

        val wanted = Math.ceil(totalSec / targetSec).toInt().coerceAtMost(MAX_SEGMENTS)

        // Cut points, as (byte offset, PCR). The first is byte 0 and not the aligned head offset:
        // whatever precedes the first packet still has to be served by somebody, and the receiver
        // skips to alignment on its own.
        val cuts = ArrayList<Pair<Long, Long>>(wanted + 1)
        cuts.add(0L to head.pcr.base90k)

        // Bitrate used to aim the next probe. It starts as the file average and is then replaced
        // by the rate measured between the last two cuts. Aiming with the GLOBAL average is wrong
        // as soon as the bitrate varies -- on a stream that runs dense then sparse it pulled
        // segments to 6.3 s where 10 s were asked for, because the average describes neither half.
        // The local rate tracks the change instead.
        var bytesPerSec = totalBytes.toDouble() / totalSec

        for (k in 1 until wanted) {
            val (cutOffset, cutPcr) = cuts.last()
            val ceiling = (totalBytes - PROBE_BYTES).coerceAtLeast(0L)
            if (cutOffset >= ceiling) break

            var found = probe(cutOffset, bytesPerSec, targetSec, ceiling, pid, read) ?: break
            var elapsed = MpegTs.deltaSeconds(cutPcr, found.second)

            // One correction. The first probe aims with the rate of the PREVIOUS stretch, so right
            // where the bitrate changes it necessarily misses; now that this stretch's own rate is
            // known, aim again. Only when it is worth it -- a quarter off target -- so the common
            // case stays at one read per boundary.
            if (elapsed > 0.0 && Math.abs(elapsed - targetSec) > targetSec * 0.25) {
                val localRate = (found.first - cutOffset).toDouble() / elapsed
                if (localRate > 0.0) {
                    probe(cutOffset, localRate, targetSec, ceiling, pid, read)?.let { better ->
                        val betterElapsed = MpegTs.deltaSeconds(cutPcr, better.second)
                        if (betterElapsed > 0.0 &&
                            Math.abs(betterElapsed - targetSec) < Math.abs(elapsed - targetSec)
                        ) {
                            found = better
                            elapsed = betterElapsed
                        }
                    }
                }
            }

            // Strictly forward, or the playlist would describe overlapping ranges. A probe that
            // lands behind the previous cut is dropped: the neighbouring segment ends up longer,
            // which is legal HLS.
            if (found.first <= cutOffset || elapsed <= 0.0) break
            cuts.add(found.first to found.second)
            bytesPerSec = (found.first - cutOffset).toDouble() / elapsed
        }

        return cuts.mapIndexed { i, (start, pcr) ->
            val end = if (i + 1 < cuts.size) cuts[i + 1].first else totalBytes
            val nextPcr = if (i + 1 < cuts.size) cuts[i + 1].second else lastPcr
            Segment(start, end - start, MpegTs.deltaSeconds(pcr, nextPcr))
        }.filter { it.length > 0L }
    }

    /**
     * Aims [targetSec] past [from] at [bytesPerSec] and returns the first real PCR packet at or
     * after that point, as (absolute offset, PCR). The aim only has to land within [PROBE_BYTES];
     * what comes back is read from the stream, never estimated.
     */
    private fun probe(
        from: Long,
        bytesPerSec: Double,
        targetSec: Double,
        ceiling: Long,
        pid: Int,
        read: (Long, Int) -> ByteArray,
    ): Pair<Long, Long>? {
        // At least one packet forward, so a degenerate rate cannot aim at the cut we just made.
        val ahead = (bytesPerSec * targetSec).toLong().coerceAtLeast(MpegTs.PACKET.toLong())
        var at = (from + ahead).coerceIn(0L, ceiling)
        // A RANDOM ACCESS POINT, not merely a PCR. Keyframes are seconds apart while PCRs are
        // ~100 ms apart, so one window often holds many PCRs and no keyframe -- hence the walk
        // forward. Cutting on a PCR mid-GOP is what made the receiver loop on a segment it could
        // not begin decoding (measured: 375, 375, 376, 375… with the position frozen 18 s).
        repeat(MAX_WINDOWS_PER_BOUNDARY) {
            val block = read(at, PROBE_BYTES)
            if (block.isEmpty()) return null
            MpegTs.firstRandomAccess(block, pid)?.let { return (at + it.offset) to it.pcr.base90k }
            if (at >= ceiling) return null
            // Overlap by a packet so a boundary straddling two windows is not missed.
            at = (at + block.size - MpegTs.PACKET).coerceAtMost(ceiling)
        }
        return null
    }

    /**
     * Last PCR on [pid] near EOF. Walks backwards a few windows: the tail of a file is often
     * padding or a partial packet, so the final window does not always carry one.
     */
    private fun tailPcr(totalBytes: Long, pid: Int, read: (Long, Int) -> ByteArray): Long? {
        var from = (totalBytes - PROBE_BYTES).coerceAtLeast(0L)
        repeat(4) {
            val block = read(from, PROBE_BYTES)
            MpegTs.located(block).lastOrNull { it.pcr.pid == pid }?.let { return it.pcr.base90k }
            if (from == 0L) return null
            from = (from - PROBE_BYTES).coerceAtLeast(0L)
        }
        return null
    }

    /**
     * Segments of equal byte size, for a stream that CANNOT be probed: [segment] needs a read per
     * boundary, and against the Magis CDN a read costs between 0.2 s and 20 s (measured). A
     * two-hour title at ten-second segments is ~720 boundaries, so probing would take hours before
     * a single frame reached the TV.
     *
     * So here the boundaries are prorated from [totalSec] and the byte size, aligned down to a
     * packet so a segment never begins mid-packet. The cost is that `#EXTINF` is an estimate: on a
     * variable-bitrate title the receiver's idea of a given minute drifts from the bytes actually
     * there, and seeking lands approximately. That is the deliberate trade for a remote stream,
     * and the reason a LOCAL file -- where a read is free -- goes through [segment] instead.
     */
    fun segmentByBitrate(totalBytes: Long, totalSec: Double, targetSec: Double): List<Segment> {
        if (totalBytes <= 0L || totalSec <= 0.0 || targetSec <= 0.0) return emptyList()
        if (totalSec <= targetSec) return listOf(Segment(0L, totalBytes, totalSec))

        val floor = MpegTs.PACKET.toLong() * 100
        val perSegment = ((totalBytes * (targetSec / totalSec)).toLong() / MpegTs.PACKET * MpegTs.PACKET)
            .coerceAtLeast(floor)
        val secPerByte = totalSec / totalBytes
        val out = ArrayList<Segment>()
        var offset = 0L
        while (offset < totalBytes && out.size < MAX_SEGMENTS) {
            var length = minOf(perSegment, totalBytes - offset)
            // Rounding down to a packet leaves a remainder, and served on its own it is a scrap:
            // 100 KB at 10 s segments ended in a 172-byte range -- not even one whole packet, so
            // the receiver would fetch a fragment of a packet and have nothing to demux. Anything
            // left that is smaller than the floor gets absorbed by the segment before it.
            if (totalBytes - (offset + length) < floor) length = totalBytes - offset
            out.add(Segment(offset, length, length * secPerByte))
            offset += length
        }
        return out
    }

    /**
     * Where a prorated cut WOULD fall, so a caller can go and probe those places.
     *
     * Exists because a remote stream cannot be walked boundary by boundary the way [segment] does:
     * each probe is a round trip to a CDN that answers between 0.2 s and 20 s, and [segment] is
     * sequential by construction (each aim uses the rate measured since the previous cut). These
     * estimates depend on nothing but arithmetic, so they can all be probed AT ONCE -- which is
     * what turns ~120 round trips from minutes into seconds.
     *
     * Byte 0 is not included: the first segment always starts there.
     */
    fun estimatedBoundaries(totalBytes: Long, totalSec: Double, targetSec: Double): List<Long> {
        if (totalBytes <= 0L || totalSec <= 0.0 || targetSec <= 0.0) return emptyList()
        val n = Math.ceil(totalSec / targetSec).toInt().coerceAtMost(MAX_SEGMENTS)
        if (n <= 1) return emptyList()
        return (1 until n).map { k ->
            (totalBytes * (targetSec * k / totalSec)).toLong()
                .coerceIn(0L, (totalBytes - 1).coerceAtLeast(0L))
        }
    }

    /**
     * Builds the segment table from boundaries that have already been SNAPPED to random access
     * points, each given as (byte offset, PCR) and sorted ascending.
     *
     * This is the half of [segment] that does not touch the stream, split out so a remote caller
     * can do the probing itself -- in parallel, and with its own retry policy. The guarantees are
     * the same ones that matter: the first segment starts at byte 0, the last ends at EOF,
     * durations come from the PCR difference rather than the byte size, and a boundary that did
     * not move forward is dropped instead of producing an overlapping range.
     */
    fun segmentsFromAnchors(
        totalBytes: Long,
        anchors: List<Pair<Long, Long>>,
        headPcr: Long,
        tailPcr: Long,
    ): List<Segment> {
        if (totalBytes <= 0L) return emptyList()
        val cuts = ArrayList<Pair<Long, Long>>(anchors.size + 1)
        cuts.add(0L to headPcr)
        anchors.sortedBy { it.first }.forEach { if (it.first > cuts.last().first) cuts.add(it) }
        return cuts.mapIndexed { i, (start, pcr) ->
            val end = if (i + 1 < cuts.size) cuts[i + 1].first else totalBytes
            val next = if (i + 1 < cuts.size) cuts[i + 1].second else tailPcr
            Segment(start, end - start, MpegTs.deltaSeconds(pcr, next))
        }.filter { it.length > 0L }
    }

    /**
     * The segments rendered as an HLS media playlist, or "" when [segments] is empty.
     *
     * Each segment gets **its own URI**, built by [uriFor] from the segment's index, instead of one
     * shared URI plus `EXT-X-BYTERANGE`. That is not a style choice: this receiver does not
     * implement `EXT-X-BYTERANGE`. Measured 2026-09-12 against a playlist that used it -- the
     * receiver fetched the segment URI four times with NO `Range` header at all (the proxy logged
     * `range=(all)` each time), pulling the first 63 KB of the movie over and over and then going
     * idle. It is not that it cannot send `Range`: given a raw stream minutes earlier it asked for
     * `bytes=0-` correctly. It simply ignores the byte-range tag and GETs the URI.
     *
     * With one URI per segment the range lives in the URL and the server resolves it, so the
     * receiver only has to do what every HTTP client does: GET a resource and read it whole.
     * `EXT-X-VERSION:3` is enough once `EXT-X-BYTERANGE` is gone.
     */
    fun playlist(segments: List<Segment>, uriFor: (index: Int) -> String): String {
        if (segments.isEmpty()) return ""
        val target = Math.ceil(segments.maxOf { it.durationSec }).toInt().coerceAtLeast(1)
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-TARGETDURATION:$target\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            segments.forEachIndexed { i, s ->
                append(String.format(java.util.Locale.US, "#EXTINF:%.3f,\n", s.durationSec))
                append(uriFor(i)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
    }
}
