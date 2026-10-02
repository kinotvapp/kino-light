package com.arkiv.player.playback

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL

/**
 * An HLS stream of MPEG-TS segments as ONE continuous MPEG-TS body: what a DLNA renderer that lists
 * an MPEG-TS type and no HLS type can still play. A Philips "NMR" listing `video/mpeg` and
 * `video/vnd.dlna.mpeg-tts` answered a live channel's playlist with UPnP 716 (ERRORES-AMF, 0.9.45).
 *
 * Only for those renderers. An LG answered this body 501 and a Samsung stalled on it
 * (`experiment/dlna-live-ts`), but both list HLS and keep getting the playlist.
 *
 * TS segments are self-contained transport packets, so writing them back to back, in media
 * sequence order, IS a valid TS: no remux, no parsing of the media. What this cannot carry is said
 * by [parse] before anything is written: fMP4 segments (`#EXT-X-MAP`), encryption, byte ranges, an
 * audio rendition outside the video's segments.
 *
 * The body has no length and no ranges ([responseHead]): it ends when the renderer hangs up, the
 * cast stops ([ContinuousTsStreams.stopAll]), the proxy's session goes away, the playlist brings
 * nothing new for a while, or a finite (VOD) playlist has been written out.
 */
object ContinuousTs {

    /**
     * The TS types a renderer may list, in the order one is picked: the 188-byte TS types first,
     * then `video/vnd.dlna.mpeg-tts`, which DLNA defines for the 192-byte timestamped variant but
     * which TVs that list it (the Philips) take plain TS under too. Lower case, as
     * `DlnaXml.sinkMimes` hands them over.
     */
    val MIMES = listOf("video/mp2t", "video/mpeg", "video/x-mpeg", "video/mpeg2", "video/x-mpeg2", "video/vnd.dlna.mpeg-tts")

    /** The type a renderer that lists [sinkMimes] is told the body is, or null when it lists none of [MIMES]. */
    fun mimeFor(sinkMimes: List<String>): String? = MIMES.firstOrNull { m -> sinkMimes.any { it.equals(m, ignoreCase = true) } }

    /** [mime]'s index in [MIMES], for a URL (the URL never carries a free-form type). */
    fun indexOf(mime: String): Int = MIMES.indexOf(mime.lowercase()).let { if (it < 0) DEFAULT_INDEX else it }

    /** The type at [index] in [MIMES]; `video/mpeg` for anything else. */
    fun mimeAt(index: Int?): String = MIMES.getOrNull(index ?: DEFAULT_INDEX) ?: MIMES[DEFAULT_INDEX]

    private const val DEFAULT_INDEX = 1

    /**
     * The DLNA 4th field, for the response header and the DIDL `res` alike: OP=00 (no time or byte
     * seek: the body is the live edge onwards), CI=0 (not converted), and FLAGS = S0 increasing
     * (the start moves) + SN increasing (the end grows: live) + streaming + background transfer +
     * HTTP stalling (the server may pause between segments) + DLNA 1.5.
     */
    const val CONTENT_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=0D700000000000000000000000000000"

    /** The response header for a body of [mime]: no Content-Length (it never ends) and no ranges. */
    fun responseHead(mime: String): String =
        "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nConnection: close\r\nAccept-Ranges: none\r\nCache-Control: no-cache\r\n" +
            "transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: $CONTENT_FEATURES\r\n\r\n"

    /** What a playlist is, for the continuous stream. */
    sealed interface Playlist {
        /** A media playlist of TS segments: [firstSequence] is the media sequence of the first of [segments]. */
        data class Media(val firstSequence: Long, val segments: List<Segment>, val ended: Boolean, val targetMs: Long) : Playlist

        /** A master playlist: [variantUrl] is the one variant the stream follows. */
        data class Master(val variantUrl: String) : Playlist

        /** Something one TS body cannot carry, and why (for the log and the report). */
        data class Unusable(val reason: String) : Playlist
    }

    data class Segment(val url: String, val durationMs: Long)

    /** Segment extensions that are not MPEG-TS. */
    private val NOT_TS = setOf("m4s", "mp4", "m4v", "m4a", "aac", "ac3", "ec3", "cmfv", "cmfa", "vtt", "webvtt")

    /** The tallest variant a TV gets when the master offers a choice: 1080p, the most any DLNA TV is sure to decode. */
    private const val MAX_HEIGHT = 1080

    /** [text] (fetched from [baseUrl]) as the continuous stream sees it. Pure. */
    fun parse(text: String, baseUrl: String): Playlist {
        val lines = text.trimStart('﻿').lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return Playlist.Unusable("not a playlist")
        val base = runCatching { URL(baseUrl) }.getOrNull() ?: return Playlist.Unusable("no base url")
        fun resolve(ref: String): String? = runCatching { URL(base, ref).toString() }.getOrNull()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return master(lines, ::resolve)
        var sequence = 0L
        var targetMs = 6_000L
        var ended = false
        var pendingMs = 0L
        val segments = ArrayList<Segment>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP") -> return Playlist.Unusable("fMP4 segments (EXT-X-MAP)")
                line.startsWith("#EXT-X-BYTERANGE") -> return Playlist.Unusable("byte-range segments")
                line.startsWith("#EXT-X-KEY") && !attribute(line, "METHOD").equals("NONE", ignoreCase = true) ->
                    return Playlist.Unusable("encrypted segments")
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetMs = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0) * 1000).toLong()
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF:") ->
                    pendingMs = ((line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(line) ?: continue
                    val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
                    if (ext in NOT_TS) return Playlist.Unusable("segments are not MPEG-TS (.$ext)")
                    segments += Segment(url, pendingMs)
                    pendingMs = 0L
                }
            }
        }
        return Playlist.Media(sequence, segments, ended, targetMs.coerceAtLeast(1_000L))
    }

    private fun master(lines: List<String>, resolve: (String) -> String?): Playlist {
        // An audio rendition with its own URI is a second stream next to the video's segments:
        // one TS body cannot interleave the two.
        if (lines.any { it.startsWith("#EXT-X-MEDIA:") && attribute(it, "TYPE") == "AUDIO" && attribute(it, "URI") != null }) {
            return Playlist.Unusable("audio in a separate rendition")
        }
        class Variant(val url: String, val bandwidth: Long, val height: Int?)
        val variants = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
            val url = resolve(uri) ?: continue
            val height = attribute(line, "RESOLUTION")?.substringAfter('x', "")?.toIntOrNull()
            variants += Variant(url, attribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0L, height)
        }
        val fits = variants.filter { (it.height ?: 0) <= MAX_HEIGHT }
        val pick = fits.maxByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth }
            ?: return Playlist.Unusable("a master playlist with no variant")
        return Playlist.Master(pick.url)
    }

    /** The value of [name] in an attribute list (`NAME=value` or `NAME="value"`), or null. */
    private fun attribute(line: String, name: String): String? =
        Regex("""(?:^|[:,])$name=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim('"')
}

/**
 * Writes one continuous TS body out of a playlist that is asked for again and again ([window]):
 * each segment once, in media sequence order, right after the last one written.
 *
 * Where it starts: a live playlist (no `#EXT-X-ENDLIST`) [START_SEGMENTS] segments before its edge,
 * a short burst for the TV's buffer (a TV that answers `Play` only with a cushion of media gets it
 * at once); a finite one at the segment holding [fromMs] (the person's position).
 *
 * Segments are dedupled by media sequence and, against a playlist that restarts its numbering, by
 * URL (the last [RECENT_URLS]). A window that moved past what was not written yet (a long stall)
 * jumps to its oldest segment; one that restarted its numbering goes back to its edge. A segment
 * that never arrives is skipped, not the end: a TV reading one body cannot ask again.
 *
 * Memory is bounded: one copy buffer, the last window and [RECENT_URLS] urls. The pace is the TV's:
 * a write blocks while its buffer is full.
 */
class ContinuousTsAssembler(
    /** The current playlist, or null when it was not served this time. */
    private val window: () -> ContinuousTs.Playlist.Media?,
    /** The segment's bytes (closed here), or null when it is nowhere to be found. */
    private val openSegment: (String) -> InputStream?,
    /** Where a finite playlist starts, on its own clock. Ignored for a live one. */
    private val fromMs: Long = 0L,
    /** Ends the body when nothing new was written for this long (the channel is off the air). */
    private val idleMs: Long = IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    enum class End { NO_PLAYLIST, STOPPED, CLIENT_GONE, IDLE, ENDED }

    class Outcome(val end: End, val written: Int, val skipped: Int, val bytes: Long)

    private var written = 0
    private var skipped = 0
    private var bytes = 0L

    /**
     * Writes the body to [out]: [writeHead] once the first playlist is in hand (none: [End.NO_PLAYLIST]
     * with nothing written, for the caller's error answer), then segments until [active] says stop or
     * one of the other ends comes.
     */
    fun run(out: OutputStream, writeHead: () -> Unit, active: () -> Boolean): Outcome {
        var w = window() ?: return outcome(End.NO_PLAYLIST)
        try {
            writeHead()
        } catch (_: IOException) {
            return outcome(End.CLIENT_GONE)
        }
        var next = startOf(w)
        val recent = object : LinkedHashMap<String, Unit>(RECENT_URLS * 2, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > RECENT_URLS
        }
        var lastNewAt = clock()
        while (active()) {
            val last = w.firstSequence + w.segments.size - 1
            if (w.segments.isNotEmpty() && last < next - 1 && w.segments.none { it.url in recent }) {
                log("continuous: the playlist restarted its numbering (${w.firstSequence}..$last, next was $next) → its edge")
                next = liveStart(w)
            }
            if (next < w.firstSequence) {
                log("continuous: the window moved past segment $next → jumping to ${w.firstSequence}")
                next = w.firstSequence
            }
            while (next <= last) {
                if (!active()) return outcome(End.STOPPED)
                val segment = w.segments[(next - w.firstSequence).toInt()]
                next++
                if (recent.put(segment.url, Unit) != null) continue
                val input = openSegment(segment.url)
                if (input == null) {
                    skipped++
                    log("continuous: segment ${next - 1} never arrived → skipped ($skipped so far)")
                    continue
                }
                when (copy(input, out, active)) {
                    Copy.GONE -> return outcome(End.CLIENT_GONE)
                    Copy.STOPPED -> return outcome(End.STOPPED)
                    Copy.DONE -> {
                        written++
                        lastNewAt = clock()
                    }
                }
            }
            if (w.ended) return outcome(End.ENDED)
            if (clock() - lastNewAt > idleMs) {
                log("continuous: nothing new for ${idleMs}ms → ending ($written written, $skipped skipped)")
                return outcome(End.IDLE)
            }
            sleep((w.targetMs / 2).coerceIn(MIN_POLL_MS, MAX_POLL_MS))
            if (!active()) break
            w = window() ?: w
        }
        return outcome(End.STOPPED)
    }

    private fun outcome(end: End) = Outcome(end, written, skipped, bytes)

    private fun startOf(w: ContinuousTs.Playlist.Media): Long {
        if (!w.ended) return liveStart(w)
        var at = 0L
        w.segments.forEachIndexed { i, s ->
            if (at + s.durationMs > fromMs) return w.firstSequence + i
            at += s.durationMs
        }
        return w.firstSequence + (w.segments.size - 1).coerceAtLeast(0)
    }

    private fun liveStart(w: ContinuousTs.Playlist.Media): Long =
        (w.firstSequence + w.segments.size - START_SEGMENTS).coerceAtLeast(w.firstSequence)

    private enum class Copy { DONE, GONE, STOPPED }

    /** One segment to [out]. A read that fails mid-way is the source cutting it: what came goes, the next follows. */
    private fun copy(input: InputStream, out: OutputStream, active: () -> Boolean): Copy {
        input.use {
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                if (!active()) return Copy.STOPPED
                val n = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (n < 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (_: IOException) {
                    return Copy.GONE
                }
                bytes += n
            }
        }
        return try {
            out.flush()
            Copy.DONE
        } catch (_: IOException) {
            Copy.GONE
        }
    }

    companion object {
        /** A new live body starts this many segments before the edge. */
        const val START_SEGMENTS = 3
        const val IDLE_MS = 30_000L
        const val RECENT_URLS = 64
        const val MIN_POLL_MS = 500L
        const val MAX_POLL_MS = 3_000L
        private const val COPY_BUFFER = 64 * 1024
    }
}

/**
 * The continuous bodies being written right now, so a cast that stops ends them at once (the
 * renderer may never hang up on its own) and a renderer that reconnects over and over cannot pile
 * them up: past [MAX_OPEN] the oldest is closed.
 */
object ContinuousTsStreams {
    const val MAX_OPEN = 2

    private val open = LinkedHashMap<Long, Closeable>()
    private var nextId = 0L

    /** Registers a body whose connection is [connection]; the id answers [isOpen] until closed. */
    fun open(connection: Closeable): Long {
        val evicted = ArrayList<Closeable>()
        val id = synchronized(this) {
            val id = ++nextId
            open[id] = connection
            while (open.size > MAX_OPEN) {
                val oldest = open.entries.first()
                open.remove(oldest.key)
                evicted += oldest.value
            }
            id
        }
        evicted.forEach { runCatching { it.close() } }
        return id
    }

    @Synchronized
    fun isOpen(id: Long): Boolean = id in open

    /** The body ended on its own: forgotten, its connection left to its owner. */
    @Synchronized
    fun done(id: Long) {
        open.remove(id)
    }

    /** Every body ends now (the cast stopped): their connections are closed. */
    fun stopAll() {
        val all = synchronized(this) { open.values.toList().also { open.clear() } }
        all.forEach { runCatching { it.close() } }
    }

    val size: Int @Synchronized get() = open.size

    /**
     * Writes [assembler]'s body to [out] as a body of [mime], registered under [connection] while it
     * runs. [active] is the server's own say (its session still the one this body is for).
     */
    fun serve(connection: Closeable, out: OutputStream, mime: String, assembler: ContinuousTsAssembler, active: () -> Boolean): ContinuousTsAssembler.Outcome {
        val id = open(connection)
        try {
            return assembler.run(
                out,
                writeHead = {
                    out.write(ContinuousTs.responseHead(mime).toByteArray(Charsets.US_ASCII))
                    out.flush()
                },
            ) { active() && isOpen(id) }
        } finally {
            done(id)
        }
    }
}
