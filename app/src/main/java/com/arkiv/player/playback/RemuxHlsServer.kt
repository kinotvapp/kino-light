package com.arkiv.player.playback

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the Chromecast remux (a fragmented MP4, usually still being written) as an HLS playlist
 * of fMP4 segments: an EVENT playlist that grows with the file and gets `#EXT-X-ENDLIST` once the
 * remux is finished.
 *
 * Replaces casting the remux as ONE chunked, growing mp4 announced as LIVE. Measured 2026-10-01
 * on the KALLEY (Xuper, a 2 h title): the remux ran ~12x faster than playback and the Wi-Fi was
 * idle, yet the receiver played ~5 s, buffered 5-12 s, played ~5 s, for an average of 0.57x
 * realtime, and needed 54 s for its first frame. A progressive response with no length and no
 * ranges gives the receiver nothing to buffer ahead with or seek along, and being "live" made the
 * end of the remux a full reload that went out from 0:00. Segments fix all three: each is a
 * finished resource with a Content-Length the receiver fetches at network speed, a seek is a
 * different segment, and finishing the remux only adds `#EXT-X-ENDLIST` -- nothing is reloaded.
 *
 * The segments are cut on the fragments the remux already wrote (one fragment = one GOP-aligned
 * run of samples), grouped to about [TARGET_SEGMENT_SEC], and each fragment's moof is rewritten
 * on the way out so it stands on its own (see [Fmp4Index]). Nothing is buffered beyond the
 * fragments of the one segment being served.
 *
 * One title at a time; the URL carries a random token, so only whoever was handed it can read it.
 * The socket, and with it the port, outlives title changes: the receiver re-fetches the playlist
 * every few seconds and must not find the port gone because the file was renamed underneath.
 */
class RemuxHlsServer(
    private val lanIp: () -> String?,
    private val log: (String) -> Unit = {},
    /**
     * The remux of [key] an earlier cast stopped and left on disk, if any (see
     * `TsRemuxer.leftover`). Served at once, under the same token as the rest, while the new remux
     * catches up with it -- see [RemuxHls.Splice].
     */
    private val leftoverOf: (key: String) -> File? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A finished-for-good prefix of the remux, from an earlier cast. Indexed once: it never grows. */
    private class Head(val file: File, val index: Fmp4Index) {
        /** Where the head ends in the title: it never grows. */
        val endSec: Double = index.fragments.sumOf { it.durationSec }
    }

    private class Source(
        val key: String,
        val token: String,
        /** The remux as it stands: the file (`.part` while written) and whether it is complete. */
        val locate: () -> Pair<File, Boolean>?,
        val head: Head?,
    ) {
        val index = Fmp4Index()
        @Volatile var complete = false
        @Volatile var splice: RemuxHls.Splice = if (head == null) RemuxHls.Splice.Joined(0, 0) else RemuxHls.Splice.Waiting

        /**
         * Whether the head may be served: null until the new run has written its own header,
         * then true (a head whose tracks differ is dropped instead, see [trustHead]).
         */
        @Volatile var headTrusted: Boolean? = if (head == null) true else null

        /** Where the cast was told to start, in seconds of the title; null until it is. */
        @Volatile var plannedStartSec: Double? = null

        /** Start of the last segment the TV asked for, in seconds of the title. */
        @Volatile var lastRequestedSec: Double? = null

        /** Seconds this run of the remux has written (it always starts at 0:00). */
        @Volatile var mainSec: Double = 0.0

        @Volatile var pacingPaused = false
        @Volatile var lastPaceCheckAt = Long.MIN_VALUE / 2

        /** The first playlist the TV asks for after a (re)load is logged, not every refresh of it. */
        @Volatile var playlistLogged = false

        /** What is served right now, fragment by fragment. Nothing while an unchecked head waits. */
        fun pieces(): List<RemuxHls.Piece> =
            if (headTrusted == null) emptyList()
            else RemuxHls.timeline(head?.index?.fragments.orEmpty(), index.fragments, splice)

        /** Finished: the new remux is done AND it is the one being served past the head. */
        fun timelineComplete(): Boolean = complete && splice != RemuxHls.Splice.Waiting

        /** Where the init segment and the track list come from: the head's while it is served. */
        fun initIndex(): Fmp4Index = if (head != null && splice != RemuxHls.Splice.Impossible) head.index else index

        fun segments(): List<RemuxHls.Segment> = RemuxHls.segments(pieces().map { it.fragment }, timelineComplete())
    }

    @Volatile private var source: Source? = null
    @Volatile private var server: ServerSocket? = null

    /**
     * Starts serving the remux filed under [key] (or keeps serving it) and returns the URL of its
     * master playlist on the LAN, or null without a LAN address.
     */
    @Synchronized
    fun serve(key: String, locate: () -> Pair<File, Boolean>?): String? {
        val current = source?.takeIf { it.key == key }
            ?: Source(key, newToken(), locate, headOf(key)).also {
                source = it
                log("serving a new remux as HLS (key #${Integer.toHexString(key.hashCode())})")
            }
        val socket = server ?: start()
        val ip = lanIp() ?: return null
        return "http://$ip:${socket.localPort}/r/${current.token}/master.m3u8"
    }

    /**
     * Is [url] one of this server's remux URLs that no longer answers (its socket closed, or its
     * token handed to another title)? False for anything that is not a remux URL: not ours to judge.
     * A session reconnect uses it never to hand the receiver a dead URL again.
     */
    fun revoked(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val route = RemuxHls.route(uri.path ?: return false) ?: return false
        if (!RemuxHls.isToken(route.token)) return false
        val socket = server
        return socket == null || socket.isClosed || uri.port != socket.localPort || source?.token != route.token
    }

    /** Is [key] the remux this server hands out? */
    fun isServing(key: String): Boolean = source?.key == key

    /** Seconds of [key]'s remux that can be played right now, or 0 if it is not the one served. */
    fun availableSec(key: String): Double {
        val s = source?.takeIf { it.key == key } ?: return 0.0
        refresh(s)
        return s.segments().sumOf { it.durationSec }
    }

    /**
     * The cast of [key] is about to be loaded from [startMs]: the TV will ask from there (see
     * [RemuxHls.mediaPlaylist]'s start), and the remux is paced against it until the TV asks for a
     * segment of its own (see [remuxShouldWait]).
     */
    fun planStart(key: String, startMs: Long) {
        val s = source?.takeIf { it.key == key } ?: return
        s.plannedStartSec = startMs.coerceAtLeast(0L) / 1000.0
        s.lastRequestedSec = null
        s.playlistLogged = false
    }

    /**
     * Should the remux of [key] hold off for now? Asked by its input ([PacedDataSource]) while it
     * reads: yes once it is [RemuxPacing.MAX_LEAD_SEC] ahead of the TV. Never for a remux this
     * server is not casting. Re-indexes at most once a second.
     */
    fun remuxShouldWait(key: String): Boolean {
        val s = source?.takeIf { it.key == key } ?: return false
        val now = clock()
        if (now - s.lastPaceCheckAt < PACE_CHECK_MS) return s.pacingPaused
        s.lastPaceCheckAt = now
        refresh(s)
        val anchor = s.lastRequestedSec ?: s.plannedStartSec
        val playable = s.segments().sumOf { it.durationSec }
        val headEnd = s.head?.takeIf { s.splice == RemuxHls.Splice.Waiting }?.endSec ?: 0.0
        val pause = RemuxPacing.shouldPause(anchor, s.mainSec, playable, headEnd, s.pacingPaused)
        if (pause != s.pacingPaused) {
            s.pacingPaused = pause
            com.arkiv.player.cast.CastDiag.i(
                "remux ${if (pause) "PAUSED" else "resumed"} · TV at ${fmt(anchor)}s, remux at ${fmt(s.mainSec)}s, " +
                    "playable to ${fmt(playable)}s" + (if (headEnd > 0) ", earlier remux to ${fmt(headEnd)}s" else ""),
            )
        }
        return pause
    }

    private fun fmt(sec: Double?): String = sec?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "?"

    /** Has [key]'s remux finished, as of the last look? */
    fun isComplete(key: String): Boolean = source?.takeIf { it.key == key }?.timelineComplete() == true

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        source = null
    }

    private fun start(): ServerSocket {
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                Thread {
                    runCatching { socket.use { handle(it) } }.onFailure { log("handle: $it") }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "RemuxHlsServer" }.start()
        return s
    }

    /** Catches the index up with whatever the remux appended since the last request. */
    private fun refresh(s: Source) {
        val (file, complete) = s.locate() ?: return
        val length = file.length()
        if (length < s.index.scannedTo) {
            // The remux started over (a cancelled run restarts from an empty file).
            log("the remux shrank (${length}B < ${s.index.scannedTo}B): re-indexing")
            s.index.reset()
            if (s.head != null) s.splice = RemuxHls.Splice.Waiting
        }
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                s.index.update(length) { offset, size -> readAt(raf, offset, size) }
            }
        }.onFailure { log("could not index ${file.name}: $it") }
        s.complete = complete
        s.mainSec = s.index.fragments.sumOf { it.durationSec }
        trustHead(s)
        joinHead(s)
    }

    /**
     * Decides, once the new run has written its header, whether the head may be served with it.
     *
     * A reconnect that reused an earlier remux showed NO picture and the receiver gave up at 63 s
     * (2026-10-01, cause not isolated). One thing that must hold for the head and the new run to
     * be one stream is the init segment: the head's is served for both, so the new run's tracks
     * have to match it, codec configuration (the whole sample entry) included. Until the new run's
     * header is on disk nothing is served (a second or two); a mismatch drops the head.
     */
    private fun trustHead(s: Source) {
        val head = s.head ?: return
        if (s.headTrusted != null || s.index.tracks.isEmpty()) return
        val same = s.index.tracks == head.index.tracks
        s.headTrusted = true
        if (!same) {
            s.splice = RemuxHls.Splice.Impossible
            log("the earlier remux's tracks differ from the new one's: serving the new one alone")
        }
    }

    /** Once the new remux reaches the end of the head, decides once where it takes over. */
    private fun joinHead(s: Source) = synchronized(s) {
        val head = s.head ?: return
        if (s.splice != RemuxHls.Splice.Waiting) return
        val tracks = s.index.tracks
        val decided = if (tracks.isNotEmpty() && tracks != head.index.tracks) {
            RemuxHls.Splice.Impossible
        } else {
            RemuxHls.splice(head.index.fragments, s.index.fragments, videoTrackOf(head.index))
        }
        if (decided == RemuxHls.Splice.Waiting) return
        s.splice = decided
        log(
            if (decided is RemuxHls.Splice.Joined) {
                "the new remux caught up with the earlier one: handing over after ${decided.headCount} of its fragments"
            } else {
                "the earlier remux cannot be continued by the new one (no shared cut): serving the new one alone"
            },
        )
    }

    private fun videoTrackOf(index: Fmp4Index): Int =
        (index.tracks.firstOrNull { it.handler == "vide" } ?: index.tracks.firstOrNull())?.id ?: 0

    /**
     * The leftover of [key], indexed, when it is a usable prefix: a header and at least one whole
     * fragment. Anything else is ignored and the cast simply waits for the new remux, as before.
     */
    private fun headOf(key: String): Head? {
        val file = runCatching { leftoverOf(key) }.getOrNull() ?: return null
        val index = Fmp4Index()
        runCatching {
            RandomAccessFile(file, "r").use { raf -> index.update(raf.length()) { offset, size -> readAt(raf, offset, size) } }
        }.onFailure { log("could not index the earlier remux: $it") }
        if (index.initEnd <= 0 || index.tracks.isEmpty() || index.fragments.isEmpty()) return null
        log(
            "reusing ${index.fragments.sumOf { it.durationSec }.toInt()}s an earlier cast already remuxed " +
                "(${index.fragments.size} fragments)",
        )
        return Head(file, index)
    }

    private fun handle(sock: Socket) {
        val input = sock.getInputStream()
        val header = StringBuilder()
        val one = ByteArray(1)
        while (input.read(one) == 1) {
            header.append(one[0].toInt().toChar())
            if (header.endsWith("\r\n\r\n") || header.length > 8192) break
        }
        val requestLine = header.lineSequence().firstOrNull().orEmpty()
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
        val out = sock.getOutputStream()
        if (method == "OPTIONS") {
            out.write(response("204 No Content", "text/plain", 0, extra = "Access-Control-Allow-Methods: GET, HEAD\r\nAccess-Control-Allow-Headers: *\r\n"))
            out.flush()
            return
        }
        val route = RemuxHls.route(path)
        val s = source
        if (route == null || s == null || route.token != s.token) {
            log("-> 404 $path")
            out.write(response("404 Not Found", "text/plain", 0))
            out.flush()
            return
        }
        refresh(s)
        val head = method == "HEAD"
        val t0 = clock()
        when (val name = route.name) {
            "master.m3u8" -> sendText(out, RemuxHls.masterPlaylist(s.initIndex().tracks, s.pieces().map { it.fragment }), head)
            "media.m3u8" -> {
                val segments = s.segments()
                if (s.initIndex().initEnd < 0 || segments.isEmpty()) {
                    out.write(response("404 Not Found", "text/plain", 0))
                } else {
                    if (!s.playlistLogged) {
                        s.playlistLogged = true
                        com.arkiv.player.cast.CastDiag.i(
                            "TV read the playlist · ${segments.size} segments, ${fmt(segments.sumOf { it.durationSec })}s, " +
                                "start ${fmt(s.plannedStartSec)}s, ended=${s.timelineComplete()}",
                        )
                    }
                    sendText(out, RemuxHls.mediaPlaylist(segments, s.timelineComplete(), s.plannedStartSec), head)
                }
            }
            "init.mp4" -> sendInit(out, s, head)
            else -> {
                val n = RemuxHls.segmentNumber(name)
                val segments = s.segments()
                val segment = n?.let { segments.getOrNull(it) }
                if (n == null || segment == null) {
                    log("-> 404 segment $name")
                    com.arkiv.player.cast.CastDiag.w("TV asked for $name: not there (${segments.size} segments) → 404")
                    out.write(response("404 Not Found", "text/plain", 0))
                } else {
                    val at = RemuxHls.startOf(segments, n)
                    if (!head) s.lastRequestedSec = at
                    val sent = runCatching { sendSegment(out, s, segment, head) }
                    com.arkiv.player.cast.CastDiag.i(
                        "TV $method $name at ${fmt(at)}s (${String.format(java.util.Locale.US, "%.1f", segment.durationSec)}s) " +
                            "in ${clock() - t0}ms" + (sent.exceptionOrNull()?.let { " FAILED: ${it.javaClass.simpleName}" } ?: "") +
                            " · remux at ${fmt(s.mainSec)}s",
                    )
                    sent.getOrThrow()
                }
            }
        }
        out.flush()
    }

    private fun sendText(out: OutputStream, body: String, head: Boolean) {
        val bytes = body.toByteArray()
        out.write(response("200 OK", "application/vnd.apple.mpegurl", bytes.size.toLong(), extra = "Cache-Control: no-cache\r\n"))
        if (!head) out.write(bytes)
    }

    private fun sendInit(out: OutputStream, s: Source, head: Boolean) {
        val init = s.initIndex()
        val file = if (init === s.index) s.locate()?.first else s.head?.file
        file ?: return out.write(response("404 Not Found", "text/plain", 0))
        val end = init.initEnd
        if (end <= 0) return out.write(response("404 Not Found", "text/plain", 0))
        out.write(response("200 OK", "video/mp4", end))
        if (head) return
        RandomAccessFile(file, "r").use { raf -> copy(raf, 0, end, out) }
    }

    private fun sendSegment(out: OutputStream, s: Source, segment: RemuxHls.Segment, head: Boolean) {
        val pieces = s.pieces().subList(segment.first, segment.last + 1)
        val mainFile = if (pieces.any { !it.fromHead }) {
            s.locate()?.first ?: return out.write(response("404 Not Found", "text/plain", 0))
        } else {
            null
        }
        val headRaf = s.head?.takeIf { pieces.any { it.fromHead } }?.let { RandomAccessFile(it.file, "r") }
        val mainRaf = mainFile?.let { RandomAccessFile(it, "r") }
        try {
            fun rafOf(p: RemuxHls.Piece): RandomAccessFile = if (p.fromHead) headRaf!! else mainRaf!!
            // The rewritten moofs first: their sizes are part of the Content-Length.
            val moofs = pieces.map { p ->
                val f = p.fragment
                Fmp4Index.rewriteMoof(readAt(rafOf(p), f.start, f.moofSize), f.start, s.index.baseTimes(f))
            }
            val length = pieces.indices.sumOf { i ->
                val f = pieces[i].fragment
                moofs[i].size + (f.end - f.start - f.moofSize)
            }
            out.write(response("200 OK", "video/mp4", length))
            if (head) return
            pieces.forEachIndexed { i, p ->
                out.write(moofs[i])
                copy(rafOf(p), p.fragment.start + p.fragment.moofSize, p.fragment.end, out)
            }
        } finally {
            runCatching { headRaf?.close() }
            runCatching { mainRaf?.close() }
        }
    }

    private companion object {
        /** How often the pacing re-indexes the remux to decide (see [remuxShouldWait]). */
        const val PACE_CHECK_MS = 1_000L
    }

    private fun response(status: String, type: String, length: Long, extra: String = ""): ByteArray =
        ("HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: $length\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            extra +
            "Connection: close\r\n\r\n").toByteArray()

    private fun copy(raf: RandomAccessFile, from: Long, to: Long, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        raf.seek(from)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
        val room = (raf.length() - offset).coerceAtLeast(0L)
        val n = minOf(size.toLong(), room).toInt()
        val bytes = ByteArray(n)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }
    }
}

/** The pure half of [RemuxHlsServer]: segment grouping, playlists and URL routing. */
object RemuxHls {

    /**
     * Seconds of content per segment, aimed for. The remux cuts a fragment at the first keyframe
     * past 2 s, so a segment is a few whole fragments: about this long, never split mid-fragment.
     */
    const val TARGET_SEGMENT_SEC = 6.0

    /**
     * `#EXT-X-TARGETDURATION`, FIXED. An EVENT playlist must not change it between reloads, and a
     * segment is at most [TARGET_SEGMENT_SEC] plus one fragment (one GOP), so this bounds them.
     */
    const val TARGET_DURATION = 15

    /**
     * Seconds of remux past the start point before the receiver is loaded. Enough that it never
     * catches the remux in its first minute (the export is still spinning up then), little enough
     * that at ~12x realtime it costs a few seconds, not the minute the old 40 MB head start did.
     */
    const val START_LEAD_SEC = 30.0

    /** How long the cast waits for the remux to reach the phone's position before starting at 0:00. */
    const val RESUME_WAIT_SEC = 45

    /** Fragments [first]..[last] (inclusive) of the index, served as one segment. */
    data class Segment(val first: Int, val last: Int, val durationSec: Double)

    /**
     * Groups [fragments] into segments of about [targetSec]. Append-only on purpose: a segment
     * is only emitted once it is full (or the remux is [complete]), so a later call never moves a
     * boundary a player has already seen in the playlist.
     */
    fun segments(fragments: List<Fmp4Index.Fragment>, complete: Boolean, targetSec: Double = TARGET_SEGMENT_SEC): List<Segment> {
        val out = ArrayList<Segment>()
        var first = 0
        var acc = 0.0
        fragments.forEachIndexed { i, f ->
            acc += f.durationSec
            if (acc >= targetSec) {
                out += Segment(first, i, acc)
                first = i + 1
                acc = 0.0
            }
        }
        if (complete && first < fragments.size) out += Segment(first, fragments.size - 1, acc)
        return out
    }

    /** Seconds into the title where segment [n] of [segments] begins. */
    fun startOf(segments: List<Segment>, n: Int): Double {
        var at = 0.0
        for (i in 0 until n.coerceAtMost(segments.size)) at += segments[i].durationSec
        return at
    }

    /**
     * The media playlist. [startSec], when the cast has planned one, goes out as
     * `#EXT-X-START:TIME-OFFSET=…,PRECISE=YES` so the receiver begins exactly there. Without it the
     * KALLEY, loaded "from 0" on a growing (EVENT) playlist, first asked for segment 46 (~406 s) --
     * picking its own starting point as for a live stream -- and only came back to s0 after ~40 s
     * (2026-10-01).
     */
    fun mediaPlaylist(segments: List<Segment>, complete: Boolean, startSec: Double? = null): String = buildString {
        val target = maxOf(TARGET_DURATION, Math.ceil(segments.maxOfOrNull { it.durationSec } ?: 0.0).toInt())
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:7\n")
        append("#EXT-X-TARGETDURATION:$target\n")
        if (startSec != null) {
            append(String.format(java.util.Locale.US, "#EXT-X-START:TIME-OFFSET=%.3f,PRECISE=YES\n", startSec.coerceAtLeast(0.0)))
        }
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-PLAYLIST-TYPE:").append(if (complete) "VOD" else "EVENT").append('\n')
        append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-MAP:URI=\"init.mp4\"\n")
        segments.forEachIndexed { i, s ->
            append(String.format(java.util.Locale.US, "#EXTINF:%.3f,\n", s.durationSec))
            append("s").append(i).append(".m4s\n")
        }
        if (complete) append("#EXT-X-ENDLIST\n")
    }

    /**
     * One variant, so the receiver is TOLD the codecs instead of guessing them: an HLS player that
     * has to guess assumes H.264/AAC, and the Magis titles this serves are HEVC.
     */
    fun masterPlaylist(tracks: List<Fmp4Index.Track>, fragments: List<Fmp4Index.Fragment>): String = buildString {
        val codecs = tracks.mapNotNull { it.codec }.joinToString(",")
        val video = tracks.firstOrNull { it.handler == "vide" }
        val seconds = fragments.sumOf { it.durationSec }
        val bytes = fragments.sumOf { it.size }
        // Peak-ish: the average with headroom. Only used by the player to pick a variant, and
        // there is one.
        val bandwidth = if (seconds > 1.0) (bytes * 8 / seconds * 1.5).toLong().coerceAtLeast(500_000L) else 8_000_000L
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:7\n")
        append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
        if (codecs.isNotEmpty()) append(",CODECS=\"").append(codecs).append('"')
        if (video != null && video.width > 0 && video.height > 0) {
            append(",RESOLUTION=").append(video.width).append('x').append(video.height)
        }
        append('\n')
        append("media.m3u8\n")
    }

    data class Route(val token: String, val name: String)

    /** `/r/<token>/<name>` → its parts, anything else → null. */
    fun route(path: String): Route? {
        val parts = path.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "r" || parts[1].isEmpty() || parts[2].isEmpty()) return null
        return Route(parts[1], parts[2])
    }

    /**
     * Where the remux an earlier cast left on disk (the HEAD) hands over to the one being written
     * now. A cast that ends stops its remux and keeps what it wrote; the next cast of the title used
     * to throw that away and start from zero -- 216-507 MB and 30-40 s per reconnect, measured
     * 2026-10-01. Instead the head is served at once and the new remux takes over where it ends.
     *
     * Two runs of the same title cut their VIDEO at the same instants (on keyframes, from the same
     * start) while the audio split between fragments may differ, so the hand-over is at a fragment
     * boundary both runs share, compared on the video track's decode time.
     */
    sealed interface Splice {
        /** The new remux has not reached the end of the head: the head alone is served. */
        data object Waiting : Splice

        /** The head's first [headCount] fragments, then the new remux's from [mainFrom] on. */
        data class Joined(val headCount: Int, val mainFrom: Int) : Splice

        /** No shared cut where one is needed: the head is dropped, the new remux served alone. */
        data object Impossible : Splice
    }

    /**
     * Decides [Splice] for [head] (fixed) and [main] (growing), cut-compared on [videoTrack].
     *
     * The head fragments already in a published segment can never be taken back (the receiver may
     * hold them), so the hand-over is at the end of the head or, failing that, at any cut of the
     * unpublished tail; with neither, it is [Splice.Impossible].
     */
    fun splice(head: List<Fmp4Index.Fragment>, main: List<Fmp4Index.Fragment>, videoTrack: Int): Splice {
        if (head.isEmpty()) return Splice.Joined(0, 0)
        val headEnd = endOf(head.last(), videoTrack) ?: return Splice.Impossible
        val mainEnd = main.lastOrNull()?.let { endOf(it, videoTrack) } ?: return Splice.Waiting
        if (mainEnd < headEnd) return Splice.Waiting
        val published = segments(head, complete = false).lastOrNull()?.let { it.last + 1 } ?: 0
        val mainStarts = HashMap<Long, Int>()
        main.forEachIndexed { i, f -> startOf(f, videoTrack)?.let { mainStarts.putIfAbsent(it, i) } }
        for (k in head.size downTo published) {
            val at = if (k == head.size) headEnd else startOf(head[k], videoTrack) ?: continue
            val j = mainStarts[at] ?: main.size.takeIf { at == mainEnd } ?: continue
            return Splice.Joined(k, j)
        }
        return Splice.Impossible
    }

    /** One fragment of what is served, and whether it is read from the head or the new remux. */
    data class Piece(val fragment: Fmp4Index.Fragment, val fromHead: Boolean)

    /** What is served for [splice]: see [Splice]. */
    fun timeline(head: List<Fmp4Index.Fragment>, main: List<Fmp4Index.Fragment>, splice: Splice): List<Piece> =
        when (splice) {
            Splice.Waiting -> head.map { Piece(it, true) }
            Splice.Impossible -> main.map { Piece(it, false) }
            is Splice.Joined ->
                head.subList(0, splice.headCount).map { Piece(it, true) } +
                    main.drop(splice.mainFrom).map { Piece(it, false) }
        }

    private fun startOf(f: Fmp4Index.Fragment, track: Int): Long? =
        f.trafs.firstOrNull { it.trackId == track }?.baseDecodeTime

    private fun endOf(f: Fmp4Index.Fragment, track: Int): Long? =
        f.trafs.firstOrNull { it.trackId == track }?.let { it.baseDecodeTime + it.durationTicks }

    /** The shape of the token [RemuxHlsServer] mints: 16 random bytes, lowercase hex. */
    fun isToken(s: String): Boolean = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** `s12.m4s` → 12. */
    fun segmentNumber(name: String): Int? =
        name.takeIf { it.startsWith("s") && it.endsWith(".m4s") }?.removePrefix("s")?.removeSuffix(".m4s")?.toIntOrNull()

    /**
     * Where to start the receiver: [wantedMs] when the playable part covers it with [leadSec] to
     * spare, else null (keep waiting, or start from the top).
     */
    fun startIfCovered(wantedMs: Long, availableSec: Double, leadSec: Double): Long? =
        if (availableSec * 1000.0 >= wantedMs + leadSec * 1000.0) wantedMs.coerceAtLeast(0L) else null

    /**
     * The start position handed to the receiver for a remux load: never exactly 0. Loaded with
     * 0 ms on a growing playlist the KALLEY started at ~406 s instead (see [mediaPlaylist]): a zero
     * start reads as "none given" there, so the beginning is asked for as 1 ms.
     */
    fun loadStartMs(startMs: Long): Long = startMs.coerceAtLeast(1L)

    /** A phone seek while the remux is still being written, held inside what can be played. */
    fun clampSeek(targetMs: Long, availableSec: Double, complete: Boolean, marginSec: Double = 10.0): Long {
        if (complete) return targetMs.coerceAtLeast(0L)
        val limit = ((availableSec - marginSec) * 1000.0).toLong().coerceAtLeast(0L)
        return targetMs.coerceIn(0L, limit)
    }
}
