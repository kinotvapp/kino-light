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
) {

    private class Source(
        val key: String,
        val token: String,
        /** The remux as it stands: the file (`.part` while written) and whether it is complete. */
        val locate: () -> Pair<File, Boolean>?,
    ) {
        val index = Fmp4Index()
        @Volatile var complete = false
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
            ?: Source(key, newToken(), locate).also {
                source = it
                log("serving a new remux as HLS (key #${Integer.toHexString(key.hashCode())})")
            }
        val socket = server ?: start()
        val ip = lanIp() ?: return null
        return "http://$ip:${socket.localPort}/r/${current.token}/master.m3u8"
    }

    /** Is [key] the remux this server hands out? */
    fun isServing(key: String): Boolean = source?.key == key

    /** Seconds of [key]'s remux that can be played right now, or 0 if it is not the one served. */
    fun availableSec(key: String): Double {
        val s = source?.takeIf { it.key == key } ?: return 0.0
        refresh(s)
        return RemuxHls.segments(s.index.fragments, s.complete).sumOf { it.durationSec }
    }

    /** Has [key]'s remux finished, as of the last look? */
    fun isComplete(key: String): Boolean = source?.takeIf { it.key == key }?.complete == true

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
        }
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                s.index.update(length) { offset, size -> readAt(raf, offset, size) }
            }
        }.onFailure { log("could not index ${file.name}: $it") }
        s.complete = complete
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
        when (val name = route.name) {
            "master.m3u8" -> sendText(out, RemuxHls.masterPlaylist(s.index.tracks, s.index.fragments), head)
            "media.m3u8" -> {
                val segments = RemuxHls.segments(s.index.fragments, s.complete)
                if (s.index.initEnd < 0 || segments.isEmpty()) {
                    out.write(response("404 Not Found", "text/plain", 0))
                } else {
                    sendText(out, RemuxHls.mediaPlaylist(segments, s.complete), head)
                }
            }
            "init.mp4" -> sendInit(out, s, head)
            else -> {
                val n = RemuxHls.segmentNumber(name)
                val segment = n?.let { RemuxHls.segments(s.index.fragments, s.complete).getOrNull(it) }
                if (segment == null) {
                    log("-> 404 segment $name")
                    out.write(response("404 Not Found", "text/plain", 0))
                } else {
                    sendSegment(out, s, segment, head)
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
        val (file, _) = s.locate() ?: return out.write(response("404 Not Found", "text/plain", 0))
        val end = s.index.initEnd
        if (end <= 0) return out.write(response("404 Not Found", "text/plain", 0))
        out.write(response("200 OK", "video/mp4", end))
        if (head) return
        RandomAccessFile(file, "r").use { raf -> copy(raf, 0, end, out) }
    }

    private fun sendSegment(out: OutputStream, s: Source, segment: RemuxHls.Segment, head: Boolean) {
        val (file, _) = s.locate() ?: return out.write(response("404 Not Found", "text/plain", 0))
        RandomAccessFile(file, "r").use { raf ->
            val fragments = s.index.fragments.subList(segment.first, segment.last + 1)
            // The rewritten moofs first: their sizes are part of the Content-Length.
            val moofs = fragments.map { f ->
                Fmp4Index.rewriteMoof(readAt(raf, f.start, f.moofSize), f.start, s.index.baseTimes(f))
            }
            val length = fragments.indices.sumOf { i -> moofs[i].size + (fragments[i].end - fragments[i].start - fragments[i].moofSize) }
            out.write(response("200 OK", "video/mp4", length))
            if (head) return
            fragments.forEachIndexed { i, f ->
                out.write(moofs[i])
                copy(raf, f.start + f.moofSize, f.end, out)
            }
        }
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

    fun mediaPlaylist(segments: List<Segment>, complete: Boolean): String = buildString {
        val target = maxOf(TARGET_DURATION, Math.ceil(segments.maxOfOrNull { it.durationSec } ?: 0.0).toInt())
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:7\n")
        append("#EXT-X-TARGETDURATION:$target\n")
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

    /** `s12.m4s` → 12. */
    fun segmentNumber(name: String): Int? =
        name.takeIf { it.startsWith("s") && it.endsWith(".m4s") }?.removePrefix("s")?.removeSuffix(".m4s")?.toIntOrNull()

    /**
     * Where to start the receiver: [wantedMs] when the playable part covers it with [leadSec] to
     * spare, else null (keep waiting, or start from the top).
     */
    fun startIfCovered(wantedMs: Long, availableSec: Double, leadSec: Double): Long? =
        if (availableSec * 1000.0 >= wantedMs + leadSec * 1000.0) wantedMs.coerceAtLeast(0L) else null

    /** A phone seek while the remux is still being written, held inside what can be played. */
    fun clampSeek(targetMs: Long, availableSec: Double, complete: Boolean, marginSec: Double = 10.0): Long {
        if (complete) return targetMs.coerceAtLeast(0L)
        val limit = ((availableSec - marginSec) * 1000.0).toLong().coerceAtLeast(0L)
        return targetMs.coerceIn(0L, limit)
    }
}
