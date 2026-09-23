package com.arkiv.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP proxy for magis's CDN: this was originally built because VLC couldn't send
 * `Content-Auth`/`Content-License` or put them on a Range request, so the stream goes through
 * here instead, which can add them to the request to the origin. It also pre-warms the startup
 * chunk and the tail of the file (see [preWarm]) and serves seek windows (see
 * [preWarmSeek]) to paper over the CDN's variable latency (0.2-20s per range) so the player
 * never runs out of tracks or data.
 *
 * Until this branch's (light-magis) archive.org pruning, this proxy had a SECOND mode -- a
 * disk cache that downloaded once into a growing file, exclusive to archive.org -- that was
 * deleted along with the rest of that source: magis never used it (its path is `direct=true`,
 * see [serve]'s dispatcher). What's left here is exactly what magis needed.
 */
class ArchiveCacheProxy(private val cacheDir: File) {
    /**
     * Stable cache key per origin URL (SHA-1), for the in-memory/disk tables below ([hotBuffers],
     * [tails], [seekWindows], [tailOnDisk], [liveByKey]). Used to come from `DiskLruCache.keyFor`,
     * deleted along with the rest of the disk cache (it was exclusive to archive.org); the hash
     * itself wasn't part of that and is kept so it doesn't invalidate tails already saved on disk
     * by existing installs.
     */
    private fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    // The socket is created in start(), NOT in the constructor: this proxy is a singleton and
    // lives the whole process, so a constructor-time socket got closed on the first stop() with no
    // way to reopen it (a closed ServerSocket doesn't reopen). Since the bar's stop button calls
    // stop(), that left archive loading forever until the app was killed.
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1
    @Volatile private var running = false

    /**
     * How many connections to the origin are live per file. Diagnostics only: none get forcibly
     * closed anymore (see the note in [openAtOrigin]), but if this number grows without ever
     * coming back down there are connections stuck hanging, and it shows up here before it's
     * noticed while playing.
     */
    private val liveByKey = ConcurrentHashMap<String, Int>()

    private fun releaseLive(key: String) {
        liveByKey.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
    }

    /**
     * Which range each currently open connection requested, and since when. Pure diagnostics,
     * existing for one concrete question: when the origin rejects, is it because we're asking for
     * several things at once? Counting connections isn't enough to answer that -- it takes seeing
     * WHAT is being requested in parallel and for how long, which is what tells apart "the CDN is
     * throttling us by concurrency" from "this particular request just went wrong".
     */
    private val rangesInFlight = ConcurrentHashMap<String, Long>()

    /** The ranges open right now, with their age, to drop into a log line. */
    private fun snapshotOfRangesInFlight(): String {
        val now = System.currentTimeMillis()
        if (rangesInFlight.isEmpty()) return "none"
        return rangesInFlight.entries
            .sortedBy { it.value }
            .joinToString(" | ") { (r, t) -> "$r ${now - t}ms ago" }
    }

    /**
     * The connections to the origin open RIGHT NOW, so they can be abandoned when the network
     * changes underneath. Unlike [liveByKey] -which only counts, for diagnostics- this holds what
     * to close them with. See [abandonConnections] and [NetworkChange].
     */
    private val liveConnections = LiveConnections()

    /**
     * Closes every open connection to the origin. Called by the network watchdog when the device
     * switches networks: those sockets were left tied to an interface that no longer exists, and
     * without this the read just waits until [OriginPolicy]'s BODY deadline -90 s in archive,
     * 30 s in magis- before even the first retry begins.
     *
     * No need to notify anyone else: closing the socket makes the read fail on the spot, and from
     * there the usual retry policy takes over, which already knows how to open again -- this time
     * over the new network.
     */
    fun abandonConnections(reason: String) {
        val closed = liveConnections.closeAll()
        if (closed > 0) {
            android.util.Log.w("ArchiveCacheProxy", "$reason → abandoning $closed connection(s) to the origin")
        }
    }
    // Real size of each origin, to be able to window. See totalOfOrigin.
    private val totals = ConcurrentHashMap<String, Long>()

    // Duration of each origin, for the cast playlist. See durationOfOrigin.
    private val durations = ConcurrentHashMap<String, Long>()

    // Segment table of each origin, for the cast playlist. See segmentsFor.
    private val segments = ConcurrentHashMap<String, List<TsSegmenter.Segment>>()

    // Where each segment REALLY starts (the IDR closest to the estimated boundary), by origin and
    // by index. Learned while serving, see serveSegment.
    private val idrByOrigin = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    /** How far past the estimated boundary to look for the real keyframe. A typical GOP is 2-5s,
     *  which at magis's bitrates is around half a meg long. */
    private val GOP_MARGIN = 1_500_000L

    /** How many [GOP_MARGIN] windows are walked looking for an IDR after a jump. */
    private val IDR_WINDOWS = 6

    /**
     * Segment length aimed for in the cast playlist, in seconds.
     *
     * Thirty. Ten was too fine once each segment carries [GOP_MARGIN] of overlap while the real
     * keyframe is found -- 1.5 MB of slack on a 1.35 MB segment is more overlap than segment. At
     * thirty the slack is a third of the fetch, and sixty was MEASURED to be worse (2026-09-12). Longer segments were tried as a way
     * to reduce how often a mid-GOP boundary can stall the receiver -- ~120 boundaries instead of
     * ~700 -- and the cure cost more than the disease: at 60 s a segment is 8.1 MB, the proxy
     * holds all of it before sending a byte, and the receiver fetches segment 0 in full even when
     * resuming at minute 62. Startup went from ~11 s to ~50 s and the retry loop merely got more
     * expensive per attempt.
     *
     * The stalls were never about how MANY boundaries there are; they are about WHERE each one
     * lands. See [TsSegmenter.segmentByBitrate].
     */
    private val TARGET_SEGMENT_SEC = 30.0

    /**
     * Last HTTP code each origin gave. Exists because the player CANNOT tell why something failed:
     * no matter what happens here, the player sees a 502 from the proxy. And the difference
     * matters -- a 404 means "archive renamed the file" and can fix itself (see
     * CoincidenciaDeArchivo), while a 503 or a timeout can only be retried. Saving it here is the
     * cheapest way for that distinction to survive until whoever knows what to do with it.
     */
    private val lastCodes = ConcurrentHashMap<String, Int>()

    /** What [originUrl] answered last time, or null if it was never asked anything. */
    fun lastCodeFor(originUrl: String): Int? = lastCodes[originUrl]

    private fun recordCode(origin: String, code: Int) {
        lastCodes[origin] = code
    }

    /**
     * Startup DOWNLOADING in memory, by cache key. See [preWarm].
     *
     * Used to hold an already-complete `ByteArray` here, and that's why [preWarm] had to wait for
     * the whole 2 MB before letting the video open: measured on the Fire TV, 0.5 to 5 s of spinner
     * on every playback. Now it's a [GrowingBuffer] and gets read while it fills -- the player
     * opens as soon as there's something and never runs dry because the buffer keeps growing
     * behind it.
     *
     * Only magis uses it: nobody else calls `preWarm`, so for every other source this map is
     * always empty and the path is exactly the usual one.
     */
    private val hotBuffers = ConcurrentHashMap<String, GrowingBuffer>()

    /**
     * How much of the startup has to have arrived before letting the video open.
     *
     * Not "how much gets pre-warmed" (that's still [HOT_STARTUP_SIZE]): it's only how much gets
     * WAITED FOR. It's enough that it started flowing -- what sank libVLC was its first read
     * hanging, not the size of the cushion.
     */
    private val MIN_STARTUP = 64 * 1024

    /**
     * Wait ceiling for the startup to start flowing. If not even the first block arrived within
     * this time, the origin is dead and it plays with no guarantee (better that than an endless
     * spinner).
     */
    private val STARTUP_WAIT_MS = 8_000L

    /**
     * Wait ceiling for the TAIL, and only when the duration has to come from it.
     *
     * Short on purpose: the duration is a nicety for the bar, never a reason not to play. The good
     * path is the gateway sending it (it already does for films and, since 2026-08-11, for series
     * chapters too); this is the backup's backup.
     */
    private val TAIL_WAIT_MS = 2_000L

    /**
     * The END of each file, by cache key: (absolute byte where it starts, bytes). Filled by
     * [preWarm] and consumed by [HotTail], which is where the reasoning lives.
     *
     * Unlike [hotBuffers], this one is NOT consumed on use: libVLC probed the end SEVERAL times in
     * a row with different offsets, and all of those are what has to be answered without the
     * network.
     */
    private val tails = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

    /**
     * The same tail, but on disk, so it survives the app restarting.
     *
     * Without this [tails] emptied on every startup and libVLC's EOF probing paid for the network
     * again -- measured on the Fire TV on 2026-08-14: 6205 ms to fetch 256 KB with two CDN
     * rejections, and 5376 ms to the first frame. On a Fire TV, which kills the app as soon as it
     * goes to the background, that "first time" is almost always. See [TailOnDisk].
     */
    private val tailOnDisk = TailOnDisk(File(cacheDir, "colas"))

    /**
     * Tails STILL being downloaded, by cache key.
     *
     * Exists because once startup stopped waiting on the tail, pre-warming the tail and libVLC's
     * EOF probing stopped going one after another and started going at the SAME time: two
     * connections asking for THE SAME end-of-file bytes. Measured on the Fire TV on 2026-08-13,
     * with the CDN on a bad streak, they rejected each other for 7 s -`origin rejected
     * bytes=-262144`, `origin rejected bytes=632603872-`, two attempts each- and only answered on
     * the third, in 167 ms. VLC took 7719 ms to open waiting on its own tail.
     *
     * With this, whoever arrives second waits on the one already in flight instead of opening a
     * competing connection. It's the same idea as [GrowingBuffer] for the head: one download,
     * several readers.
     */
    private val tailsInFlight = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /**
     * How long a tail in flight gets waited on before going to the origin anyway.
     *
     * Generous on purpose: here waiting is NOT wasted time -the download being waited on is the
     * one that's going to answer- and the deadline only exists so a pre-warm that died without
     * notice doesn't leave the player hanging. Past the deadline, the origin gets asked, which is
     * what always used to happen.
     */
    private val TAIL_IN_FLIGHT_WAIT_MS = 10_000L

    /**
     * A stretch of the file around a SEEK point, kept in memory.
     *
     * The why, measured on the Fire TV on 2026-08-13 resuming a film at 13:26: libVLC didn't seek
     * once -- it **bisected**. It requested ten ranges in a row -`bytes=62148288-`, `63899508-`,
     * `63533848-`, `63443420-`…- reading a few hundred KB from each and cutting the connection
     * right away. Each one opened its own connection to the CDN at ~300 ms. And all ten landed
     * within **1.8 MB** of the file.
     *
     * With this, the first of those ranges leaves a window in memory and the other nine get
     * answered without touching the network. It's the same thing the original app does another
     * way: its player never asks the CDN for bytes, it tells the download engine which point it's
     * going to (`Seek {moment, offset}`, see `yc/C6280e.java` in the decompiled app) and the engine
     * prepares the area.
     *
     * The window is NOT downloaded ahead of time, and that's the important part: it fills from the
     * connection the player already opened, **continuing after it cuts off**. That way sequential
     * playback -which never cuts- pays nothing extra, not a connection, not a single byte.
     */
    private class SeekWindow(val start: Long, val buffer: GrowingBuffer) {
        /** Whether [requested] falls inside what's ALREADY saved. */
        fun covers(requested: Long): Boolean =
            requested >= start && requested < start + buffer.available
    }

    private val seekWindows = ConcurrentHashMap<String, MutableList<SeekWindow>>()

    /**
     * How much is saved around a seek. 4 MB covers the 1.8 MB the measured bisection spanned with
     * margin, and it's money: that's 4 MB of RAM on a Fire Stick.
     */
    private val SEEK_WINDOW_SIZE = 4 * 1024 * 1024

    /** How many windows per file. Two: the current seek's and the previous one's, nothing more. */
    private val WINDOWS_PER_FILE = 2

    /**
     * How far BEFORE the estimated byte the pre-warmed seek window starts, and how much it covers.
     *
     * These come from measuring, not from picking a round number: across two real resumes the gap
     * between the byte the constant rate estimates and the ones the player ended up requesting was
     * **-2.7 MB to +3.7 MB**. Starting 4 MB early and covering 8 spans that whole range with some
     * room to spare.
     */
    private val SEEK_MARGIN = 4L * 1024 * 1024
    private val PREWARMED_SEEK_WINDOW_SIZE = 8 * 1024 * 1024

    /**
     * How much time the first connection gets before the tail is also requested over a second one
     * in parallel.
     *
     * 1.2 s: more than the CDN's good case (0.2-0.8 s measured) and well under the 3 s deadline
     * that gives it up for dead. That's where this duplicate wins: when the first one is on its
     * way to not answering, there's no need to wait for it to give up before rolling the dice
     * again.
     */
    private val DUPLICATE_AFTER_MS = 1_200L

    /** How many connections at most for the tail. Two: the duplicate, not a burst. */
    private val TAIL_SHOTS = 2

    /**
     * How long the tail waits to know the file's size before giving up and asking by suffix
     * instead.
     *
     * Short because the data comes from the HEAD's response, which is downloading in parallel and
     * whose first byte is exactly what the startup was already waiting on: if it hasn't arrived by
     * 2 s, the problem is the CDN and not this deadline.
     */
    private val TOTAL_WAIT_MS = 2_000L

    /** How much of the tail is saved. Same as the duration probe: 256 KB is plenty. */
    private val HOT_TAIL_SIZE = TsDurationProbe.PROBE_BYTES

    /**
     * The one that cuts the connection to an origin that doesn't answer in time. See
     * [codeWithDeadline].
     *
     * A single thread is enough: it only schedules `disconnect()`, which doesn't block. Daemon so
     * it doesn't stop the process from dying.
     */
    private val executioner = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "arkiv-origin-executioner").apply { isDaemon = true }
    }

    /**
     * How much of the startup gets pre-warmed. It's the number to move if this gets slow, and also
     * the first one to check if the black-and-mute comes back.
     *
     * Started at 2 MB, chosen with slack so the player can identify programs and tracks without
     * depending on the CDN's latency. Measured on 2026-08-11 on the Fire TV over eight startups,
     * that slack became THE dominant phase: fetching the head cost between 917 and 9210 ms,
     * against 246-511 ms for the 256 KB tail in the same runs -- so the size is what dictates it.
     *
     * **Dropping it to 512 KB was already tried, on 2026-08-11 on the Fire TV, and it does NOT pay
     * off.** The reasoning was sound -these TS run at ~152 KB/s in practice, so 2 MB is ~13 s of
     * video preloaded just to identify tracks- but what's saved on one side is paid on the other:
     *
     * | | 2 MB (9 startups) | 512 KB (6 startups) |
     * |---|---|---|
     * | fetching the head (good runs) | 917-1828 ms | 515-911 ms |
     * | VLC → first frame (median) | 795 ms | 823 ms |
     * | heartbeats with `pistas=v0/a0` | 1 of 9 | 2 of 6 |
     *
     * And the detail that settles it: the TWO startups with `v0/a0` were exactly the two with the
     * worst opening (1782 ms and 2018 ms, against 513-1027 ms for the rest). With less hot data
     * libVLC never finished identifying the stream with what it had in memory and went out to the
     * network mid-startup, which is precisely what this pre-warm exists to prevent. It didn't
     * actually fail -zero rescues, both recovered- but the end of that road is the black-and-mute
     * documented in [preWarm], and the savings don't justify it.
     *
     * What actually dominates when this gets slow isn't the size: on the bad runs the 2 MB head and
     * the 256 KB tail finish in the SAME millisecond (5205/5212, 5264/5266), meaning the bottleneck
     * is the link or the CDN, and no payload trim fixes that.
     */
    private val HOT_STARTUP_SIZE = 2 * 1024 * 1024

    /** Idempotent: if a socket is already live, returns its port; otherwise opens a new one. */
    @Synchronized
    fun start(): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = ServerSocket(0)
        server = sock
        running = true
        // The loop captures THIS socket instead of reading the field: if a stop()+start() happened
        // meanwhile, the old thread dies with its own and doesn't end up accepting over the new
        // proxy's.
        Thread {
            while (running && !sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return sock.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    /**
     * Local URL the player can play. [headers] travels encoded in the URL itself because that was
     * the only thing VLC used to let through: it only understood `:http-referrer` and
     * `:http-user-agent`, and magis serves the VOD behind `Content-Auth` and `Content-License`.
     * The proxy puts them on the request to the origin.
     *
     * `h` goes BEFORE `u` on purpose: there's code that pulls out the origin with
     * `substringAfter("u=")`.
     */
    fun proxyUrl(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        direct: Boolean = false,
    ): String {
        val u = URLEncoder.encode(originUrl, "UTF-8")
        val d = if (direct) "d=1&" else ""
        if (headers.isEmpty()) return "http://127.0.0.1:$port/s?${d}u=$u"
        val h = URLEncoder.encode(HeaderCodec.encode(headers), "UTF-8")
        return "http://127.0.0.1:$port/s?h=$h&${d}u=$u"
    }

    companion object {
        /**
         * The same proxy URL, but asking to serve from [fraction] of the file onward as if that
         * stretch were the whole file. See [FileWindow] for the why.
         *
         * `f` goes before `u` like the rest of the parameters: there's code that pulls out the
         * origin with `substringAfter("u=")` and anything after it would leak in.
         */
        fun withFraction(proxyUrl: String, fraction: Float): String {
            if (fraction <= 0f) return proxyUrl
            val clean = proxyUrl.replace(Regex("""[?&]f=[^&]*"""), "")
            val i = clean.indexOf("u=")
            if (i < 0) return clean
            return clean.substring(0, i) + "f=$fraction&" + clean.substring(i)
        }

        // `conVentanaDesde(proxyUrl, desde)` used to live here: it built a URL with `w=$desde&` so
        // the disk-cache download would start far from byte 0 when resuming. Deleted along with the
        // rest of the disk cache (exclusive to archive.org, see `VentanaDeDescarga` in history) --
        // [serve]'s dispatcher no longer reads `w=`, so leaving the URL builder would set up a
        // parameter with no effect.

        /** Loopback authority that [proxyUrl] writes, and the only thing [lanUrl] replaces. */
        private const val LOOPBACK = "http://127.0.0.1:"

        /**
         * The same proxy URL, spelled so the Chromecast can reach it: the phone's LAN [ip] instead
         * of loopback. `null` if [proxyUrl] isn't one of ours or there's no LAN ip yet.
         *
         * This is the ONLY URL a Cast receiver can be given for Magis. Its VOD sits behind
         * `Content-Auth`/`Content-License`, and the Default Media Receiver cannot send custom
         * headers (that needs a custom receiver app), so the CDN answers it 401. The proxy is what
         * puts those headers on the request to the origin, so the receiver has to come through it.
         *
         * No socket is widened here, and none needs to be: [start] opens `ServerSocket(0)` with no
         * bind address, which already listens on every interface — the same convention
         * `LiveHlsProxy` documents in `urlFor` and `LocalFileServer` relies on. Loopback keeps
         * working for the local player exactly as before.
         *
         * It rewrites an EXISTING url instead of rebuilding one from `originUrl`+`headers` so the
         * two spellings cannot drift: same port, same path, same query, byte for byte. That matters
         * more than it looks — `h=` is a [HeaderCodec] blob and `u=` is percent-encoded, so
         * re-encoding either would corrupt the headers the proxy exists to inject. It also rules
         * out a blind string replace: an origin that itself mentions 127.0.0.1 lives inside `u=`
         * and must survive untouched.
         *
         * ⚠️ The auth headers ride in the query, so this url hands them to anyone on the LAN. That
         * is the same exposure `LiveHlsProxy` covers with a random token, and the reason this is
         * built only at cast time rather than alongside the local url.
         */
        fun lanUrl(proxyUrl: String, ip: String): String? {
            if (ip.isBlank() || !proxyUrl.startsWith(LOOPBACK)) return null
            // Everything after "http://127.0.0.1:" is "<port>/<path>?<query>"; the first '/' ends
            // the authority, and the query is never touched.
            val after = proxyUrl.substring(LOOPBACK.length)
            val cut = after.indexOf('/')
            if (cut <= 0) return null
            val proxyPort = after.substring(0, cut)
            if (proxyPort.any { !it.isDigit() }) return null
            return "http://$ip:$proxyPort${after.substring(cut)}"
        }

        /**
         * The LAN url of the HLS PLAYLIST for the same stream -- what the Cast receiver is given.
         *
         * Same host, port and query as [lanUrl]; only the path changes from `/s` to `/hls.m3u8`.
         * The receiver refuses a bare transport stream served progressively (its own log:
         * `FFmpegDemuxer: open context failed`) but plays those identical bytes as a playlist of
         * byte ranges, which `/hls.m3u8` describes and `/s` still serves.
         */
        fun lanPlaylistUrl(proxyUrl: String, ip: String): String? =
            lanUrl(proxyUrl, ip)?.let { lan ->
                val i = lan.indexOf("/s?")
                if (i < 0) null else lan.substring(0, i) + "/hls.m3u8?" + lan.substring(i + 3)
            }
    }

    /**
     * "Buffered" fraction [0..1] for the progress bar. ALWAYS 0f: it used to measure the progress
     * of the single-download-into-a-growing-file disk cache, exclusive to archive.org and deleted
     * in this branch's pruning. Magis's path (`direct=true`) never went through that cache -it had
     * nowhere to pull this fraction from- so for the only caller left this changes nothing: it
     * already always returned 0f. The function is kept (not the calculation) so PlayerScreen's
     * call site doesn't need touching.
     */
    fun bufferedFraction(proxyUrl: String): Float = 0f

    private fun serve(socket: Socket) {
        // socket.use{} closes the socket on exit; runCatching swallows network/IO exceptions (e.g.
        // the player closes the socket on seek → writing throws broken pipe) so the thread doesn't die.
        socket.use { s ->
            runCatching {
                val input = s.getInputStream()
                val header = StringBuilder()
                val one = ByteArray(1)
                while (input.read(one) == 1) {
                    header.append(one[0].toInt().toChar())
                    if (header.endsWith("\r\n\r\n") || header.length > 8192) break
                }
                val lines = header.toString().split("\r\n")
                val reqLine = lines.firstOrNull().orEmpty()
                val path = reqLine.split(' ').getOrNull(1).orEmpty()
                val origin = path.substringAfter("u=", "").substringBefore('&').let {
                    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                } ?: return@runCatching
                val extraHeaders = HeaderCodec.decode(
                    path.substringAfter("h=", "").substringBefore('&'),
                )
                // Service mode. Chosen by whoever builds the URL (magis → direct). It's the ONLY
                // path left: archive.org's disk cache (`w=`/no-`d=1` routes) was deleted in this
                // branch's pruning along with the rest of that source.
                val direct = path.contains("d=1")
                // Window: serve from this fraction of the file onward as if it were the whole file,
                // to be able to RESUME without the player having to seek. See FileWindow.
                val fraction = path.substringAfter("f=", "").substringBefore('&')
                    .toFloatOrNull()?.takeIf { it > 0f } ?: 0f
                val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()

                val key = keyFor(origin)
                val out = s.getOutputStream()

                // Every incoming request gets logged: the proxy is the border between "the player
                // isn't asking" and "the proxy isn't delivering", which from outside look identical
                // (the player buffering at 0% forever).
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "← requests range=${rangeHeader ?: "(all)"} direct=$direct window=$fraction path=${path.substringBefore('?')}",
                )

                // HLS playlist over the SAME stream, for the Cast receiver. It refuses a bare
                // transport stream served progressively -- read off the receiver's own log
                // (2026-09-12): `{"error":"FFmpegDemuxer: open context failed"}`, so it never
                // reaches a decoder -- but it plays the identical bytes offered as a playlist of
                // byte ranges. Nothing is converted; `/s` still serves the media, one range at a
                // time, exactly as it does for the phone.
                if (path.startsWith("/hls.m3u8")) {
                    servePlaylist(path, origin, extraHeaders, out)
                    return@runCatching
                }

                // One segment of that playlist, addressed by index. The index is resolved to a byte
                // range HERE rather than by the receiver, because the receiver does not implement
                // `EXT-X-BYTERANGE` -- see the KDoc of [TsSegmenter.playlist] for the measurement.
                // From its side this is an ordinary resource it GETs whole.
                if (path.startsWith("/seg")) {
                    val n = path.substringAfter("n=", "").substringBefore('&').toIntOrNull()
                    if (n == null) {
                        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        serveSegment(n, origin, extraHeaders, out)
                    }
                    return@runCatching
                }

                // DIRECT path (magis): every Range goes to the origin as-is and its body is
                // returned without touching disk.
                //
                // `d=1` is also where the ENDURANCE PROFILE comes from: today only magis uses this
                // path (`proxyUrl(direct = true)` has no other caller), and magis and archive fail
                // in opposite ways -- see OriginPolicy.Profile. If some other source ever asks for
                // `d=1`, the profile has to travel in the URL, not be inferred.
                if (direct) {
                    if (!passthrough(
                            origin, rangeHeader, out, extraHeaders,
                            uniqueKey = key, fraction = fraction,
                            profile = OriginPolicy.Profile.MAGIS,
                        )
                    ) {
                        android.util.Log.w("ArchiveCacheProxy", "direct: the origin didn't serve the range")
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    return@runCatching
                }

                // With no `d=1` there's no cache to fall back to (it was exclusive to archive.org,
                // deleted in this pruning): last resort, the same plain passthrough as always.
                if (!passthrough(origin, rangeHeader, out, extraHeaders)) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                }
            }.onFailure { e ->
                runCatching { android.util.Log.w("ArchiveCacheProxy", "serve() failed: ${e.message}") }
            }
        }
    }

    /**
     * Answers `/hls.m3u8` with a playlist of byte ranges over the same origin.
     *
     * Two facts are needed and both are cheap: the total size (one 1-byte ranged request, already
     * remembered per origin by [totalOfOrigin]) and the duration (two 256 KB ends, which
     * [TsDurationProbe] already knows how to read and which the app pays for anyway to draw the
     * progress bar). Both are cached, so a receiver that re-fetches the playlist -- it does, several
     * times -- costs nothing after the first.
     *
     * Segments are prorated, NOT cut on PCR, and that is deliberate: cutting on PCR needs a read
     * per boundary, and against this CDN a read costs 0.2 s to 20 s. A two-hour title is ~720
     * boundaries, so the exact version would take hours to answer one playlist request. The price
     * is that seeking lands approximately on a variable-bitrate title. See
     * [TsSegmenter.segmentByBitrate]; a LOCAL file, where reads are free, uses the exact path.
     */
    private fun servePlaylist(
        path: String,
        origin: String,
        headers: Map<String, String>,
        out: java.io.OutputStream,
    ) {
        val segs = segmentsFor(origin, headers)
        // Relative URIs: same host, same port, same query -- only the path and the added `n=`
        // differ. Relative keeps the LAN ip out of the playlist, so whatever URL the receiver used
        // to fetch it is the one it keeps using.
        val query = path.substringAfter('?', "")
        val body = TsSegmenter.playlist(segs) { i ->
            "/seg?n=$i" + if (query.isEmpty()) "" else "&$query"
        }
        if (body.isEmpty()) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "playlist: can't build it (no size or no duration) → 502, the receiver will show an error",
            )
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val bytes = body.toByteArray()
        android.util.Log.w(
            "ArchiveCacheProxy",
            "playlist → ${segs.size} segments, one URI each (${bytes.size}B)",
        )
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    /**
     * The segment table for an origin, remembered. The receiver asks for the playlist more than
     * once and then for ~700 segments, and every one of those would otherwise re-probe the size
     * and the duration -- two CDN round trips each, against an origin that answers between 0.2 s
     * and 20 s.
     */
    private fun segmentsFor(origin: String, headers: Map<String, String>): List<TsSegmenter.Segment> {
        segments[origin]?.let { return it }
        val total = totalOfOrigin(origin, headers, OriginPolicy.Profile.MAGIS)
        val durMs = durationOfOrigin(origin, headers)
        val out = TsSegmenter.segmentByBitrate(total, durMs / 1000.0, TARGET_SEGMENT_SEC)
        if (out.isNotEmpty()) {
            segments[origin] = out
            android.util.Log.w(
                "ArchiveCacheProxy",
                "segment table: ${out.size} of ~${TARGET_SEGMENT_SEC}s over $total bytes / ${durMs}ms",
            )
        }
        return out
    }

    /**
     * Serves segment [n], starting at a REAL random access point.
     *
     * This is where the "it plays in sections" bug was fixed. The playlist's boundaries are
     * prorated arithmetic, so they land mid-GOP, and a hardware decoder drops every frame until it
     * sees an IDR -- measured on the KALLEY's own decoder, which emitted 2 pictures out of every
     * 17 (`OMX_VDEC ooo … diff=333666`, one frame each 333 ms of a 23.976 fps title, so ~3 fps).
     * Chromium will not fix that and the Cast receiver is Chromium, so the segment has to arrive
     * already aligned.
     *
     * Aligning up front was priced and rejected: finding a keyframe means reading forward up to a
     * whole GOP, and at ~700 boundaries that is ~280 MB before a single playlist could be answered.
     * Instead each boundary is discovered WHILE the segment around it is served -- bytes this proxy
     * has to move anyway -- and remembered. Sequential playback therefore pays nothing beyond
     * [GOP_MARGIN] of overlap per segment; only a seek into a segment never visited costs a probe.
     */
    private fun serveSegment(
        n: Int,
        origin: String,
        headers: Map<String, String>,
        out: java.io.OutputStream,
    ) {
        val segs = segmentsFor(origin, headers)
        val seg = segs.getOrNull(n)
        if (seg == null) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n out of range (there are ${segs.size})")
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val total = totalOfOrigin(origin, headers, OriginPolicy.Profile.MAGIS)
        val starts = idrByOrigin.getOrPut(origin) { ConcurrentHashMap() }
        // Segment 0 starts at byte 0: the file's own first packet is a random access point.
        if (n == 0) starts.putIfAbsent(0, 0L)

        val start = starts[n] ?: findIdr(origin, headers, seg.start, total)
        if (start == null) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n: no random access point near ${seg.start}")
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        starts.putIfAbsent(n, start)

        // Where the NEXT segment should begin, by the playlist's arithmetic, plus a GOP of slack to
        // look for the real keyframe. The last segment simply runs to EOF.
        val isLast = n >= segs.size - 1
        val estimatedEnd = if (isLast) total else segs[n + 1].start
        val until = if (isLast) total else minOf(total, estimatedEnd + GOP_MARGIN)
        val body = rawRange(origin, headers, "bytes=$start-${until - 1}")
        if (body == null || body.isEmpty()) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n ($start-${until - 1}): the origin didn't serve it")
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        // Cut at the first random access point at or after the estimated boundary, and remember it
        // as where segment n+1 begins -- learned for free from bytes already in hand.
        var length = body.size
        if (!isLast) {
            val from = (estimatedEnd - start).coerceIn(0L, body.size.toLong()).toInt()
            val cut = firstIdrIn(body, from)
            if (cut != null) {
                length = cut
                starts.putIfAbsent(n + 1, start + cut)
            } else {
                // No keyframe within the margin: serve up to the estimate and let the next segment
                // probe for itself. Rare, and better than a segment that runs long.
                length = from.coerceAtLeast(1)
            }
        }

        android.util.Log.w(
            "ArchiveCacheProxy",
            "segment $n → ${length}B from $start (estimate was ${seg.start}, " +
                "next starts ${starts[n + 1] ?: -1})",
        )
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Content-Length: $length\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body, 0, length)
        out.flush()
    }

    /** First random access point at or after [from] inside [buf], or null. */
    private fun firstIdrIn(buf: ByteArray, from: Int): Int? {
        val base = MpegTs.alignment(buf)
        if (base < 0) return null
        // Walk packets from the first one at or after `from`.
        var i = base + ((from - base).coerceAtLeast(0) + MpegTs.PACKET - 1) / MpegTs.PACKET * MpegTs.PACKET
        while (i + MpegTs.PACKET <= buf.size) {
            if (MpegTs.isRandomAccess(buf, i)) return i
            i += MpegTs.PACKET
        }
        return null
    }

    /** Probes the origin for the first random access point at or after [from]. Used only when a
     *  segment is reached without having served the one before it -- that is, after a seek. */
    private fun findIdr(origin: String, headers: Map<String, String>, from: Long, total: Long): Long? {
        var at = from.coerceIn(0L, total)
        repeat(IDR_WINDOWS) {
            val end = minOf(total, at + GOP_MARGIN)
            if (end <= at) return null
            val block = rawRange(origin, headers, "bytes=$at-${end - 1}") ?: return null
            firstIdrIn(block, 0)?.let { return at + it }
            at = end
        }
        android.util.Log.w("ArchiveCacheProxy", "no random access point within ${IDR_WINDOWS} windows of $from")
        return null
    }

    /**
     * The instant of a real keyframe at or near [targetMs], in ms from the start of the title.
     *
     * Clipping a remux anywhere else desynchronises the tracks: video can only begin at a keyframe
     * so the muxer moves it back to one, while audio begins at the instant asked for, and the two
     * no longer line up. Measured in the bytes of a clipped remux -- the first fragment carried
     * 8.363 s of audio against 6.792 s of video, 1.57 s of audio with no picture to go with it,
     * and it never recovered because the fragments carry no `tfdt` to re-anchor them. Starting
     * from zero was fine precisely because nothing was clipped.
     *
     * Asking to cut where a keyframe already is removes the mismatch at the source.
     *
     * Returns [targetMs] unchanged when the stream cannot be probed -- a slightly misaligned
     * start is better than refusing to cast.
     */
    fun msOfKeyframeNear(origin: String, headers: Map<String, String>, targetMs: Long): Long {
        if (targetMs <= 0L) return 0L
        val total = totalOfOrigin(origin, headers, OriginPolicy.Profile.MAGIS)
        val durMs = durationOfOrigin(origin, headers)
        if (total <= 0L || durMs <= 0L) {
            android.util.Log.w("ArchiveCacheProxy", "keyframe search: no size or duration, using ${targetMs}ms as asked")
            return targetMs
        }
        // The clock the whole title is measured against.
        val head = rawRange(origin, headers, "bytes=0-${GOP_MARGIN - 1}") ?: return targetMs
        val initialPcr = MpegTs.firstPcr(head)?.pcr?.base90k ?: return targetMs

        // Aim a GOP early so the keyframe found is at or before the point asked for: starting a
        // moment early is harmless, starting late skips content.
        val targetByte = (total * (targetMs.toDouble() / durMs)).toLong()
            .minus(GOP_MARGIN)
            .coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val idr = findIdr(origin, headers, targetByte, total) ?: return targetMs

        // Its PCR is the answer: the instant that keyframe sits at.
        val block = rawRange(origin, headers, "bytes=$idr-${minOf(total, idr + MpegTs.PACKET * 400L) - 1}")
            ?: return targetMs
        val pcr = MpegTs.firstPcr(block)?.pcr?.base90k ?: return targetMs
        val ms = MpegTs.deltaTicks(initialPcr, pcr) * 1000 / MpegTs.PCR_HZ
        android.util.Log.w(
            "ArchiveCacheProxy",
            "keyframe for ${targetMs}ms is at ${ms}ms (byte $idr) → clipping there so the tracks line up",
        )
        return ms.coerceAtLeast(0L)
    }

    /** Duration of an origin, remembered: the receiver asks for the playlist more than once. */
    private fun durationOfOrigin(origin: String, headers: Map<String, String>): Long {
        durations[origin]?.let { return it }
        val head = rawRange(origin, headers, "bytes=0-${TsDurationProbe.PROBE_BYTES - 1}")
        val tail = rawRange(origin, headers, "bytes=-${TsDurationProbe.PROBE_BYTES}")
        if (head == null || tail == null) return 0L
        val ms = TsDurationProbe.durationMs(head, tail)
        if (ms > 0L) durations[origin] = ms
        return ms
    }

    /** One ranged read straight from the origin, for the playlist's own bookkeeping. */
    private fun rawRange(origin: String, headers: Map<String, String>, range: String): ByteArray? =
        runCatching {
            val conn = (URL(origin).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", range)
                setRequestProperty("Connection", "close")
                connectTimeout = OriginPolicy.Profile.MAGIS_PROBE.connectMs
                readTimeout = OriginPolicy.responseMs(0, OriginPolicy.Profile.MAGIS_PROBE)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect(); null
            } else {
                conn.inputStream.use { it.readBytes() }.also { runCatching { conn.disconnect() } }
            }
        }.getOrNull()

    /**
     * An origin's real size, read from the `Content-Range` of a 1-byte request.
     *
     * Needed to be able to window: the fraction the player asks for can only be converted to a
     * byte once the total is known. Remembered per origin because every seek reopens the stream
     * with a different fraction, and this trip to the CDN, even for one byte, also pays its
     * latency.
     */
    private fun totalOfOrigin(
        origin: String,
        extraHeaders: Map<String, String>,
        profile: OriginPolicy.Profile = OriginPolicy.Profile.ARCHIVE,
    ): Long {
        totals[origin]?.let { return it }
        repeat(OriginPolicy.attempts(profile)) { attempt ->
            val total = runCatching {
                val conn = (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=0-0")
                    if (!profile.reuseSockets) setRequestProperty("Connection", "close")
                    // Used to be a fixed 8 s. It's a single byte, but what's being paid for here is
                    // the node's latency, not the size: against the measured 72 s, 8 s was never enough.
                    connectTimeout = profile.connectMs
                    readTimeout = OriginPolicy.responseMs(attempt, profile)
                }
                val cr = conn.getHeaderField("Content-Range")
                runCatching { conn.inputStream.use { it.readBytes() } }
                runCatching { conn.disconnect() }
                FileWindow.totalFromContentRange(cr)
            }.getOrDefault(0L)
            if (total > 0) { totals[origin] = total; return total }
            if (attempt < OriginPolicy.attempts(profile) - 1) {
                Thread.sleep(OriginPolicy.waitMs(attempt, profile))
            }
        }
        android.util.Log.w("ArchiveCacheProxy", "window: couldn't find out the origin's size")
        return 0L
    }

    /**
     * `conn.responseCode` with its OWN deadline, separate from reading the body's.
     *
     * `HttpURLConnection` has a single `readTimeout` governing both, and that's why lowering the
     * number wasn't enough: waiting for the response and riding out a stall mid-body need opposite
     * deadlines (see [OriginPolicy.Profile]). Here the short deadline is enforced by a timer that
     * cuts the connection out from under it: a `disconnect()` from another thread makes the blocked
     * `responseCode` throw, which is exactly what's wanted.
     *
     * The `AtomicBoolean` is what avoids the ugly race -the timer disconnecting RIGHT after the
     * response arrived and breaking the stream for a request that had gone fine-: whichever marks
     * it first wins, and if the timer wins this returns [OriginPolicy.NO_RESPONSE] so the
     * caller retries instead of reading an already-dead connection.
     */
    private fun codeWithDeadline(conn: HttpURLConnection, deadlineMs: Int): Int {
        val resolved = AtomicBoolean(false)
        val cutoff = executioner.schedule(
            { if (resolved.compareAndSet(false, true)) runCatching { conn.disconnect() } },
            deadlineMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
        val code = runCatching { conn.responseCode }.getOrDefault(OriginPolicy.NO_RESPONSE)
        val killedByTheTimer = !resolved.compareAndSet(false, true)
        cutoff.cancel(false)
        return if (killedByTheTimer) OriginPolicy.NO_RESPONSE else code
    }

    /**
     * Opens the range at the origin, retrying: magis's CDN rejects requests at random (seen on
     * device: a perfectly valid seek returned non-206 twice in a row and the film died right
     * there). Used to give up on the first no, and since the response header had already gone out,
     * the player was left waiting on a body that never arrived.
     *
     * Registers the good connection in [conexiones] before returning it: the previous one for the
     * same file has to die on the spot or the CDN leaves the new one hanging.
     */
    private fun openAtOrigin(
        origin: String,
        range: String?,
        extraHeaders: Map<String, String>,
        uniqueKey: String?,
        profile: OriginPolicy.Profile = OriginPolicy.Profile.ARCHIVE,
    ): Pair<HttpURLConnection, SingleConnection.Closer>? {
        var lastCode = OriginPolicy.NO_RESPONSE
        val attempts = OriginPolicy.attempts(profile)
        repeat(attempts) { attempt ->
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (range != null) setRequestProperty("Range", range)
                    // A new socket for origins that don't tolerate the pool. See Profile.reuseSockets.
                    if (!profile.reuseSockets) setRequestProperty("Connection", "close")
                    connectTimeout = profile.connectMs
                    // The BODY's deadline, the long one. The RESPONSE's -the short one, the one that
                    // cuts a dead connection- is applied by codeWithDeadline() below, because
                    // HttpURLConnection doesn't tell the two apart and here they need to differ.
                    readTimeout = OriginPolicy.bodyMs(profile)
                }
            }.getOrNull()
            if (conn != null) {
                val closer = SingleConnection.Closer { runCatching { conn.disconnect() } }
                // From HERE and not from the `return` further down: in between is the wait for the
                // headers (`codeWithDeadline`), which against a dead network hangs for up to 90 s.
                liveConnections.register(closer)
                // The previous connection is NOT killed, and this is the opposite of what it used to do.
                //
                // `SingleConnection` was put in place believing the CDN served one connection per
                // file at a time. Measured at the time: it's false — it serves two simultaneous ones
                // to the same file without complaint (206 in 0.77 s for the second, with the first
                // still downloading). And on opening, libVLC made SEVERAL requests in a row to probe
                // the stream (seen: bytes=0-, 216576-, 1115160- in 800 ms): killing the previous one
                // on each cut off exactly the reads it uses to identify programs and tracks, and it
                // ended up with none (`pistas=v0/a0`), black and mute. In other words: the safeguard
                // was causing the problem it claimed to prevent.
                //
                // What DID need fixing -that an abandoned connection doesn't keep draining- is
                // already handled by the `disconnect()` in passthrough's finally, which also runs
                // when the player cuts off abruptly ("broken pipe").
                val live = uniqueKey?.let { liveByKey.merge(it, 1) { a, b -> a + b } } ?: 1
                val rangeLabel = "${range ?: "(all)"}#${attempt + 1}"
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "opening ${range ?: "(all)"} → live connections for this file: $live · " +
                        "in flight: ${snapshotOfRangesInFlight()}",
                )
                rangesInFlight[rangeLabel] = System.currentTimeMillis()
                val deadlineMs = OriginPolicy.responseMs(attempt, profile)
                val responseT0 = System.currentTimeMillis()
                val code = codeWithDeadline(conn, deadlineMs)
                val tookMs = System.currentTimeMillis() - responseT0
                recordCode(origin, code)
                lastCode = code
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    // The time to the HEADER, which is what decides whether the deadline is
                    // enough. Without this only the total for the body was visible, which mixes
                    // the wait for the CDN with how long it takes to download the bytes: two
                    // different things.
                    rangesInFlight.remove(rangeLabel)
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "origin answered ${range ?: "(all)"} with $code in ${tookMs}ms " +
                            "(deadline ${deadlineMs}ms, attempt ${attempt + 1}, $live connection(s) at once)",
                    )
                    return conn to closer
                }
                // `code=-1` + a time right up against the deadline = OUR timer expired, not the
                // CDN's. The distinction matters and wasn't visible: it read "origin rejected" and
                // looked like the origin's fault when it was the deadline choking a request that
                // was going to answer.
                val deadlineExpired = code == -1 && tookMs >= deadlineMs - 150
                rangesInFlight.remove(rangeLabel)
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "origin rejected ${range ?: "(all)"} with $code in ${tookMs}ms " +
                        "(deadline ${deadlineMs}ms, attempt ${attempt + 1}/$attempts, " +
                        "$live connection(s) at once)" +
                        (if (deadlineExpired) " ← OUR DEADLINE EXPIRED, not the CDN" else "") +
                        " · in flight: ${snapshotOfRangesInFlight()}",
                )
                uniqueKey?.let { releaseLive(it) }
                liveConnections.release(closer)
                runCatching { conn.disconnect() }
                // A 404 doesn't improve by insisting: the file isn't where we have it recorded.
                // Cutting here saves two timeouts and, above all, lets the 404 arrive clean all the
                // way up, which is what triggers the metadata revalidation (see
                // CoincidenciaDeArchivo).
                if (!OriginPolicy.worthRetrying(code)) {
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "origin: $code isn't retried, giving up on ${range ?: "(all)"}",
                    )
                    return null
                }
            }
            if (attempt < attempts - 1) Thread.sleep(OriginPolicy.waitMs(attempt, profile))
        }
        // Out of attempts against the front door. There used to be a plan B here for archive.org
        // (talking directly to the node holding the file, skipping `download.php` — see
        // NodoDeArchive, deleted in this branch's pruning along with the rest of that source): magis
        // serves from its own CDN and never had an alternate node to fall back to.
        return null
    }

    /**
     * Gets the stream's startup ready in memory BEFORE the player opens the URL.
     *
     * The why, measured: magis's CDN takes between 0.2 s and 20 s to release the first byte, and
     * when the first read stalled **libVLC gave up identifying the stream**. It doesn't fail or
     * warn: it ends up with no tracks (`pistas=v0/a0`, no picture, no sound) and from then on
     * swallows the file at full speed without ever trying again -- the film stays black forever
     * even if the data arrives two seconds later. It's the explanation for "the first time it works
     * and the second it doesn't": it was never the decoder or the undestroyed view, it was who won
     * that race.
     *
     * With the startup already in hand, the player's first read gets answered instantly and always
     * manages to identify the tracks. Whatever the CDN takes becomes a wait BEFORE opening the
     * video, which is recoverable, instead of a silent failure with no way back.
     *
     * Returns false if it couldn't (no network, the origin doesn't cooperate): the caller still
     * plays, just without the guarantee.
     */
    suspend fun preWarm(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        fraction: Float = 0f,
        profile: OriginPolicy.Profile = OriginPolicy.Profile.MAGIS,
        /**
         * Whether to WAIT for the tail before returning. Only needed when the duration comes from
         * it ([durationOfPreWarmed]); when the gateway sends it, the tail is only for the player's
         * EOF probes, which happen AFTER opening and so can be left running in the background.
         *
         * Measured on 2026-08-11 on the Fire TV, and it's the reason this parameter exists: with
         * the head no longer blocking, the tail became the brake. Three startups of the same
         * chapter, all three with the duration already in hand: 281 ms tail → 1050 ms total; 3398
         * and 3446 ms tails → 3883 and 4083 ms totals. 3.4 s were being spent waiting on bytes
         * nobody needed at that point.
         */
        waitForTail: Boolean = true,
        /**
         * Container declared by the source ("ts", "mp4"…), to decide whether the tail is needed.
         * Empty = unknown, and it gets pre-warmed anyway. See [HotTail.needsPreWarming].
         */
        container: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(originUrl)
        val start = if (fraction > 0f) {
            FileWindow.start(totalOfOrigin(originUrl, headers, profile), fraction)
        } else 0L
        val t0 = System.currentTimeMillis()
        // BOTH ENDS AT ONCE. They used to run in series and that was the most expensive phase of
        // startup: measured on the Fire TV over eight playbacks, `pre-warm` dominated in 6 of 8 at
        // 1443-6647 ms. They ask for different stretches of the file and the CDN serves several
        // connections without degrading (measured: with three draining, a tail range still
        // answered in 0.44-0.82 s), so the cost becomes the MAX of the two instead of the sum.
        // IS THE TAIL EVEN NEEDED? Not for mp4: measured on the Fire TV, three titles downloaded it
        // and never used it once, and one of them cost 8284 ms with three CDN rejections in
        // parallel with opening the video. When in doubt, it's downloaded anyway. See
        // [HotTail.needsPreWarming].
        val tailNeeded = HotTail.needsPreWarming(container)
        if (!tailNeeded) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "tail skipped: the '$container' container opens without reading the end of the file",
            )
        }
        val buffer = GrowingBuffer(HOT_STARTUP_SIZE)
        hotBuffers["$key@$start"] = buffer
        // Filling it is NOT waited on: it's published in the map right away and keeps going on its
        // own. The proxy serves the player from this same buffer while it grows (see serveArranque).
        Thread {
            runCatching { downloadStartup(originUrl, headers, start, profile, buffer) }
            buffer.close()
        }.apply { isDaemon = true; name = "arkiv-prewarm" }.start()
        // The tail ALWAYS runs on a Thread, never on an `async`, and that isn't a style preference:
        // `withContext` doesn't return until its children finish. An `async` is a child, so the
        // `withTimeoutOrNull` below used to cancel the WAIT and not the work, and on leaving the
        // block this same function sat still waiting on the tail it had just walked away from.
        // Measured on the Fire TV on 2026-08-13 with a series chapter: the log said "tail didn't
        // arrive in 2000ms" and pre-warming still took 9218 ms. The thread DOES escape the scope,
        // and that's why the deadline becomes an actual deadline. See PrecalentadoNoBloqueaTest.
        //
        // `waitForTail` now only decides whether anyone looks at the result; the work launches
        // either way, because the player's EOF probes want that tail in memory in both cases.
        val tail = CompletableDeferred<Unit>()
        if (tailNeeded) {
            Thread {
                runCatching { preWarmTail(originUrl, headers, key, profile) }
                tail.complete(Unit)
            }.apply { isDaemon = true; name = "arkiv-prewarm-tail" }.start()
        } else {
            // Nobody is going to wait on it, but the file's size IS needed for the seek pre-warm
            // ([preWarmSeek] reads it off `totals`). Requested with a ONE-byte range instead of the
            // tail's 256 KB.
            tail.complete(Unit)
            Thread {
                runCatching { totalOfOrigin(originUrl, headers, profile) }
            }.apply { isDaemon = true; name = "arkiv-size" }.start()
        }

        // The ONLY thing always waited on: that the startup has started flowing. That's enough for
        // the player's first read to get answered instantly, which is what prevented the
        // black-and-mute.
        val started = buffer.waitUntil(MIN_STARTUP, STARTUP_WAIT_MS)
        // The wait on the tail is BOUNDED. Measured on 2026-08-11 on the Fire TV: when the CDN gets
        // dense, that 256 KB takes 8 s -and it isn't per-connection bad luck, because a second
        // connection in parallel took just as long too: it's the link or the CDN throttling the
        // whole client. Two playbacks out of six came out at 10.3 s and 13.8 s waiting on that data.
        //
        // Past this deadline it plays WITHOUT duration: the bar looks bad, but the video starts.
        // Never the other way around -- never hold up the video for a progress bar. The tail keeps
        // downloading in the background regardless, so the player's EOF probes find it once it arrives.
        if (waitForTail && withTimeoutOrNull(TAIL_WAIT_MS) { tail.await() } == null) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "the tail didn't arrive within ${TAIL_WAIT_MS}ms → playing without duration",
            )
        }
        android.util.Log.w(
            "ArchiveCacheProxy",
            "startup servable after ${System.currentTimeMillis() - t0}ms " +
                "(${buffer.available / 1024}KB of ${HOT_STARTUP_SIZE / 1024}KB, still downloading)",
        )
        if (!started && buffer.available == 0) {
            android.util.Log.w("ArchiveCacheProxy", "preWarm: no bytes arrived")
            hotBuffers.remove("$key@$start")
            return@withContext false
        }
        true
    }

    /**
     * Streams the first [HOT_STARTUP_SIZE] bytes from [start] into [destination], as they arrive.
     * Runs on its own thread: whoever launches it does NOT wait on it (see [preWarm]).
     */
    private fun downloadStartup(
        originUrl: String,
        headers: Map<String, String>,
        start: Long,
        profile: OriginPolicy.Profile,
        destination: GrowingBuffer,
    ) {
        val (conn, _) =
            openAtOrigin(originUrl, "bytes=$start-", headers, uniqueKey = null, profile = profile)
                ?: run {
                    android.util.Log.w("ArchiveCacheProxy", "preWarm: the origin didn't give the startup chunk")
                    return
                }
        // The file's SIZE comes free from this same response, and it's needed right away: it's
        // what lets [preWarmTail] ask for the end by an ABSOLUTE range instead of by suffix. See
        // there for why that difference is worth seconds.
        recordTotal(originUrl, conn, start)
        runCatching {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (total < HOT_STARTUP_SIZE) {
                    val read = ins.read(buf, 0, minOf(buf.size, HOT_STARTUP_SIZE - total))
                    if (read < 0) break
                    // Each block becomes available RIGHT AWAY for whoever is serving the player.
                    destination.write(buf, read)
                    total += read
                }
            }
        }
        runCatching { conn.disconnect() }
    }

    /**
     * How long the file runs, worked out from what [preWarm] has ALREADY downloaded. 0 = nothing
     * to work with.
     *
     * It's the same PCR calculation [TsDurationProbe.probeRemote] used to do, just without the
     * network: the probe asked for the head and tail on its own -the same 256 KB at the end the
     * pre-warm already had- and the two requests raced each other against the same CDN. Measured
     * on 2026-08-11 on the Fire TV, in two of eight startups the probe lost that race and the film
     * came out with no duration.
     */
    fun durationOfPreWarmed(originUrl: String): Long {
        val key = keyFor(originUrl)
        // Whatever arrived from the startup is enough: the FIRST PCR is in the first packets, and
        // by here it already waited for the buffer to pass MIN_STARTUP. It doesn't need to be complete.
        val head = hotBuffers["$key@0"]?.slice(0)?.takeIf { it.isNotEmpty() } ?: return 0L
        val tail = tails[key]?.second ?: return 0L
        return TsDurationProbe.durationMs(head, tail)
    }

    /**
     * Saves the end of the file so the player's EOF probes don't touch the network. See
     * [HotTail] for the measurement that justifies this.
     *
     * Goes by SUFFIX range (`bytes=-N`) for the same reason as the duration probe: there's no need
     * to ask the size first, and the response carries the `Content-Range` with the total and the
     * byte it starts at, which is exactly what needs to be saved to be able to answer absolute
     * ranges afterward.
     */
    private fun preWarmTail(
        originUrl: String,
        headers: Map<String, String>,
        key: String,
        profile: OriginPolicy.Profile,
    ) {
        val t0 = System.currentTimeMillis()
        // DO WE ALREADY HAVE IT FROM ANOTHER SESSION? It's the first thing tried: a file's tail
        // never changes, and fetching it from disk costs microseconds against the seconds the CDN
        // costs.
        tailOnDisk.read(key)?.let { saved ->
            tails[key] = saved.start to saved.bytes
            totals[originUrl] = saved.total
            android.util.Log.w(
                "ArchiveCacheProxy",
                "tail from disk: ${saved.bytes.size / 1024}KB from ${saved.start} " +
                    "(total=${saved.total}) without touching the network",
            )
            return
        }
        // Reported BEFORE opening, not after: the race this avoids begins the instant the player
        // opens the media, which is milliseconds after this thread starts.
        val inFlight = java.util.concurrent.CountDownLatch(1)
        tailsInFlight[key] = inFlight
        try {
            preWarmTailInner(originUrl, headers, key, profile, t0)
        } finally {
            tailsInFlight.remove(key)
            inFlight.countDown()
        }
    }

    /**
     * Asks the origin for [range] and, if it hasn't answered by [DUPLICATE_AFTER_MS], asks again
     * over ANOTHER connection and keeps whichever arrives first.
     *
     * The why, measured on the Fire TV on 2026-08-13 with a new chapter: the CDN rejected the tail
     * twice in a row -answering nothing at all, which is how this CDN fails- and each rejection
     * costs [OriginPolicy.Profile.MAGIS]'s 3 s deadline. VLC, which needed the end of the file to
     * open, was left waiting **7.3 s**. Retrying in series doesn't help: it waits for the previous
     * attempt to give up before rolling the dice again.
     *
     * That the CDN tolerates simultaneous connections isn't an assumption: it's measured (two to
     * the same file coexist, the second answered in 0.77 s with the first still downloading). And
     * the duplicate's cost is bounded -- at most one extra request, and only when the first is
     * already taking longer than normal.
     *
     * The original app solves this another way: its native engine has SEVERAL CDN nodes with their
     * latency measured (`Status.links`, `Status.latency`) and picks. We have a single node, so what
     * can be varied is the connection, not the destination.
     */
    private fun openWithDuplicate(
        origin: String,
        range: String?,
        headers: Map<String, String>,
        profile: OriginPolicy.Profile,
    ): Pair<HttpURLConnection, SingleConnection.Closer>? {
        val winner = java.util.concurrent.atomic.AtomicReference<Pair<HttpURLConnection, SingleConnection.Closer>?>()
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val ready = java.util.concurrent.CountDownLatch(1)
        repeat(TAIL_SHOTS) { i ->
            Thread {
                // The duplicate goes out LATE on purpose: if the first answers in time -the normal
                // case- this thread wakes up, sees there's already a winner, and doesn't touch the network.
                if (i > 0) runCatching { Thread.sleep(DUPLICATE_AFTER_MS) }
                if (winner.get() == null) {
                    val r = runCatching { openAtOrigin(origin, range, headers, null, profile) }.getOrNull()
                    if (r != null) {
                        if (winner.compareAndSet(null, r)) {
                            if (i > 0) {
                                android.util.Log.w(
                                    "ArchiveCacheProxy",
                                    "the DUPLICATE request for ${range ?: "(all)"} won",
                                )
                            }
                            ready.countDown()
                        } else {
                            // Arrived second: its connection is of no use to anyone and has to be
                            // released, or it sits draining the file against the same CDN we're rushing.
                            runCatching { r.first.disconnect() }
                        }
                    }
                }
                if (finished.incrementAndGet() == TAIL_SHOTS) ready.countDown()
            }.apply { isDaemon = true; name = "arkiv-tail-$i" }.start()
        }
        runCatching { ready.await() }
        return winner.get()
    }

    private fun preWarmTailInner(
        originUrl: String,
        headers: Map<String, String>,
        key: String,
        profile: OriginPolicy.Profile,
        t0: Long,
    ) {
        // THE END IS REQUESTED BY ABSOLUTE RANGE, NOT BY SUFFIX. This isn't a style preference:
        // it's the most expensive thing found while measuring. On the Fire TV, on 2026-08-13, over
        // 31 requests to magis's CDN:
        //
        //   range shape            rejections   OK responses
        //   bytes=-262144 (suffix)     19            0
        //   bytes=N-    (absolute)      0           12
        //
        // The NINETEEN rejections were all suffix and none were absolute. And it's not that the
        // stretch isn't there: in the same startup, after six rejections in a row of
        // `bytes=-262144` -two parallel connections, three attempts each, 8.8 s thrown away- the
        // player requested that same end via `bytes=322515168-` and the CDN served it in 267 ms.
        // Each rejection costs the MAGIS profile's 3 s deadline, and libVLC wouldn't open until it
        // had the end: that's where the 7036, 8485 and 9435 ms tails came from.
        //
        // The size doesn't cost an extra request: [recordTotal] noted it from the head's response,
        // which is downloading in parallel. It's given a moment to arrive; if it doesn't, it falls
        // back to the usual suffix, which is worse but works sometimes.
        val deadline = System.currentTimeMillis() + TOTAL_WAIT_MS
        var knownTotal = totals[originUrl] ?: 0L
        while (knownTotal <= 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            knownTotal = totals[originUrl] ?: 0L
        }
        val tailRange = if (knownTotal > HOT_TAIL_SIZE) {
            "bytes=${knownTotal - HOT_TAIL_SIZE}-${knownTotal - 1}"
        } else {
            android.util.Log.w("ArchiveCacheProxy", "tail: no size in time, falling back to suffix")
            "bytes=-$HOT_TAIL_SIZE"
        }
        val (conn, _) = openWithDuplicate(
            originUrl, tailRange, headers, profile,
        ) ?: run {
            android.util.Log.w("ArchiveCacheProxy", "preWarm tail: the origin didn't give it")
            return
        }
        val contentRange = conn.getHeaderField("Content-Range")
        val bytes = runCatching { conn.inputStream.use { it.readBytes() } }.getOrNull()
        runCatching { conn.disconnect() }
        // `bytes <start>-<end>/<total>`: without this an absolute range can't be translated to an
        // offset inside what's saved, and serving blind would be worse than going to the origin.
        val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(contentRange.orEmpty())
        if (bytes == null || bytes.isEmpty() || m == null) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "preWarm tail: no usable Content-Range (${contentRange ?: "none"})",
            )
            return
        }
        val start = m.groupValues[1].toLong()
        val total = m.groupValues[3].toLong()
        totals[originUrl] = total
        tails[key] = start to bytes
        // And to disk, so the next time this title opens it doesn't need fetching again.
        tailOnDisk.save(key, start, total, bytes)
        android.util.Log.w(
            "ArchiveCacheProxy",
            "tail pre-warmed: ${bytes.size / 1024}KB from $start (total=$total) " +
                "in ${System.currentTimeMillis() - t0}ms",
        )
    }

    /**
     * Answers [requested] with what's in [v], without touching the network. Returns false if it
     * wasn't enough and the origin has to be asked as usual.
     *
     * Only serves the stretch it can deliver WHOLE, and that's why the `Content-Length` it sends is
     * that stretch's and not the rest of the file's: a body shorter than the announced length
     * leaves the player waiting on bytes that will never arrive, with no visible error -- the same
     * care [HotTail] already documents. If the player wants more, it asks with another range,
     * which is exactly what it does while bisecting.
     *
     * WATCH who's asking. That last part holds for libVLC, which bisected and asked again;
     * ExoPlayer does NOT: it asks for `bytes=N-` -from there to the end- and eats a shorter body as
     * the end of the data. Its ProgressiveMediaPeriod calls the load done and stops asking, what it
     * had queued runs out -measured at 512000 audio frames, exactly 10.7 s, precisely the stretch
     * served-, the AudioTrack pauses and the renderers stop without declaring BUFFERING: the picture
     * freezes and the clock keeps running on its own. That's why an open range is served from
     * memory and THEN CONTINUES over the network in the same response, instead of cutting off.
     * Measured: stretches served from the network never hung; the ones from memory always did.
     */
    private fun serveFromWindow(
        v: SeekWindow,
        requested: Long,
        total: Long,
        out: java.io.OutputStream,
        rangeHeader: String?,
        origin: String,
        extraHeaders: Map<String, String>,
        uniqueKey: String?,
        profile: OriginPolicy.Profile,
    ): Boolean {
        val from = (requested - v.start).toInt()
        val chunk = runCatching { v.buffer.slice(from) }.getOrNull() ?: return false
        if (chunk.isEmpty()) return false

        // Open range (`bytes=N-`): the rest of the file has to be covered. That length is
        // announced and the network takes over after memory, so a client that isn't going to ask
        // again doesn't get its body cut short.
        val open = RangeHeader.parse(rangeHeader)?.let { it.end == null } ?: false
        val until = if (open) total - 1 else requested + chunk.size - 1
        val length = until - requested + 1

        // NO GOING BACK FROM HERE. As soon as the header goes out over the socket, the response is
        // committed: returning false would make passthrough write ANOTHER HTTP response on top of
        // this one, over the same connection. Found by testing -one probe served fine and the one
        // right next to it didn't, with no pattern- and the reason was exactly this: the player cuts
        // off mid-body (reads what it wants and leaves), the `write` failed and this fell through to
        // the origin having already answered. The client leaving isn't a failure: it's normal while bisecting.
        val headerWentOut = runCatching {
            out.write(
                (
                    "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                        "Content-Length: $length\r\n" +
                        "Content-Range: bytes $requested-$until/$total\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(chunk)
            out.flush()
        }.isSuccess
        android.util.Log.w(
            "ArchiveCacheProxy",
            "seek window: $rangeHeader served from memory (${chunk.size / 1024}KB, no network)" +
                if (open) " · continuing over the network from ${requested + chunk.size}" else "",
        )
        if (!open || !headerWentOut) return true

        // The rest of the body, from where memory ran out. If this fails there's nothing more to
        // do: the header already went out and the client will see a short body, same as before this
        // change. Always returns true so nobody writes another response on top.
        val remaining = length - chunk.size
        if (remaining <= 0L) return true
        val (conn, closer) = openAtOrigin(
            origin,
            "bytes=${requested + chunk.size}-$until",
            extraHeaders,
            uniqueKey,
            profile,
        ) ?: return true
        var written = 0L
        val t0 = System.currentTimeMillis()
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    if (runCatching { out.write(buf, 0, n) }.isFailure) break
                    written += n
                }
            }
            runCatching { out.flush() }
        } catch (_: Throwable) {
            // The client cut off or the origin dropped: the log below says so and there's nothing
            // more to do.
        } finally {
            uniqueKey?.let { releaseLive(it) }
            liveConnections.release(closer)
            runCatching { conn.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "seek window: tail from the network ${written / 1024}KB of ${remaining / 1024}KB " +
                    "in ${System.currentTimeMillis() - t0}ms",
            )
        }
        return true
    }

    /**
     * Prepares the area the player is going to SEEK to on resume, before it asks for it.
     *
     * The why, measured on the Fire TV on 2026-08-13 resuming a film at 13:29: libVLC ALWAYS
     * opened at byte 0 and only afterward looked for the saved minute. Between one thing and the
     * other **2.5 MB of the start of the film that then got thrown away** got downloaded, and that
     * cost 3.4 s with the player stuck at `pos=0` -- almost a third of the 10.8 s it took to start.
     *
     * It's the piece our copy of the original app's design was missing. It sends its download
     * engine `Seek {moment}` **before** seeking, with a callback, for exactly this reason: so the
     * area is ready by the time the player gets there (see `yc/C6280e.java` in the decompiled app).
     *
     * The byte is estimated assuming a constant rate, and that is NOT a liberty taken: it was
     * checked against two real resumes before writing it. Estimated 119.4 MB → requested at 116.7
     * and 120.5 MB; estimated 76.1 MB → requested between 76.2 and 79.8 MB. So a gap of -2.7 to
     * +3.7 MB, which is where [SEEK_MARGIN] and this window's size come from: start before the
     * estimate and cover both sides. If it still misses, nothing is lost -- the usual reactive
     * window is still there.
     *
     * Blocks nobody: it goes to a thread and startup continues. If it doesn't arrive in time, the
     * player requests over the network like it used to.
     */
    fun preWarmSeek(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        fraction: Float,
        profile: OriginPolicy.Profile = OriginPolicy.Profile.MAGIS,
    ) {
        if (fraction <= 0f || fraction >= 1f) return
        val key = keyFor(originUrl)
        Thread {
            // The size comes from the tail, which is downloading in parallel. It's waited on here
            // -on a thread that holds nothing up- instead of asking for the size separately, which
            // would be yet another request to the same CDN we're trying not to bother.
            val deadline = System.currentTimeMillis() + TAIL_IN_FLIGHT_WAIT_MS
            var total = totals[originUrl] ?: 0L
            while (total <= 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                total = totals[originUrl] ?: 0L
            }
            if (total <= 0L) {
                // The tail is the normal way to learn the size, but it might not have been
                // requested (a container that doesn't need it) or might not have arrived. A
                // one-byte range resolves it on its own; without this the seek silently went
                // un-pre-warmed.
                total = runCatching { totalOfOrigin(originUrl, headers, profile) }.getOrDefault(0L)
            }
            if (total <= 0L) {
                android.util.Log.w("ArchiveCacheProxy", "seek: no file size, not pre-warming")
                return@Thread
            }
            val target = (total * fraction.toDouble()).toLong()
            val start = (target - SEEK_MARGIN).coerceAtLeast(0L)
            val t0 = System.currentTimeMillis()
            val buffer = GrowingBuffer(PREWARMED_SEEK_WINDOW_SIZE)
            registerWindow(key, start, buffer)
            val opened = openAtOrigin(originUrl, "bytes=$start-", headers, null, profile)
            if (opened == null) {
                buffer.close()
                android.util.Log.w("ArchiveCacheProxy", "seek: the origin didn't give the range at $start")
                return@Thread
            }
            runCatching {
                opened.first.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (buffer.available < PREWARMED_SEEK_WINDOW_SIZE) {
                        val n = ins.read(buf); if (n < 0) break
                        buffer.write(buf, n)
                    }
                }
            }
            buffer.close()
            runCatching { opened.first.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "seek pre-warmed: ${buffer.available / 1024}KB from $start " +
                    "(estimated target $target) in ${System.currentTimeMillis() - t0}ms",
            )
        }.apply { isDaemon = true; name = "arkiv-prewarm-seek" }.start()
    }

    /**
     * Saves the file's size by reading it off a response already in hand.
     *
     * From the `Content-Range` if it came with one (it carries the explicit total) and otherwise
     * from `Content-Length` added to the byte the stretch started at. Asks for nothing: the goal is
     * precisely not to spend one more request against this CDN.
     */
    private fun recordTotal(originUrl: String, conn: HttpURLConnection, start: Long) {
        if ((totals[originUrl] ?: 0L) > 0L) return
        val total = runCatching {
            val cr = conn.getHeaderField("Content-Range")
            val m = Regex("""/(\d+)""").find(cr.orEmpty())
            if (m != null) {
                m.groupValues[1].toLong()
            } else {
                val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                if (len > 0L) start + len else 0L
            }
        }.getOrDefault(0L)
        if (total > 0L) totals[originUrl] = total
    }

    /** Records a new window for [key], dropping the oldest one if there are already too many. */
    private fun registerWindow(key: String, start: Long, buffer: GrowingBuffer) {
        val list = seekWindows.computeIfAbsent(key) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.add(SeekWindow(start, buffer))
            while (list.size > WINDOWS_PER_FILE) list.removeAt(0)
        }
    }

    /** Direct origin→player passthrough (no caching), last resort if the download couldn't be started. */
    private fun passthrough(
        origin: String,
        rangeHeader: String?,
        out: java.io.OutputStream,
        extraHeaders: Map<String, String> = emptyMap(),
        uniqueKey: String? = null,
        fraction: Float = 0f,
        profile: OriginPolicy.Profile = OriginPolicy.Profile.ARCHIVE,
    ): Boolean {
        // Window: the player asks in the coordinates of a file that starts at 0, and here they get
        // translated to the real file's. If the size couldn't be found, `start` stays at 0 and this
        // behaves like the usual passthrough: worse without a duration, but it plays.
        val start = if (fraction > 0f) {
            FileWindow.start(totalOfOrigin(origin, extraHeaders, profile), fraction)
        } else 0L
        val clientRange = RangeHeader.parse(rangeHeader)
        val rangeToOrigin = if (start > 0L) {
            FileWindow.rangeToOrigin(clientRange, start)
        } else rangeHeader
        // END PROBE: answered from memory, without touching the network. It's the request that used
        // to swallow the startup — see HotTail for the measurement. Only applies without a
        // window: with `f=` the bytes the player sees are shifted and these are NOT its bytes.
        if (start == 0L && uniqueKey != null) {
            // If the tail is STILL downloading, it gets waited on instead of opening a connection
            // that competes for the same bytes (see [tailsInFlight] for the measurement). Only for
            // ranges that don't start at 0: `bytes=0-` is the player's first read -the head- and
            // that one gets answered by the hot startup, not the tail.
            val inFlight = tailsInFlight[uniqueKey]
            if (inFlight != null && tails[uniqueKey] == null && (clientRange?.start ?: 0L) > 0L) {
                val t = System.currentTimeMillis()
                val arrived = inFlight.await(TAIL_IN_FLIGHT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "tail in flight: $rangeHeader waited ${System.currentTimeMillis() - t}ms " +
                        "(${if (arrived) "arrived" else "deadline expired, going to the origin"})",
                )
            }
            // ALREADY-SAVED SEEK: if this range falls inside a previous seek's window, it's
            // answered from memory. This is the case for the nine ranges that follow the first one
            // of a bisection.
            val requested = clientRange?.start ?: 0L
            val knownTotal = totals[origin] ?: 0L
            if (requested > 0L && knownTotal > 0L) {
                // `synchronized` and not a bare `firstOrNull`: the list is written by whichever
                // thread is handling another range of the same file, and walking it without the
                // lock is exactly the race that makes one seek serve fine and the one right next to
                // it fail.
                val list = seekWindows[uniqueKey]
                val v = if (list != null) synchronized(list) { list.firstOrNull { it.covers(requested) } } else null
                if (v != null && serveFromWindow(
                        v, requested, knownTotal, out, rangeHeader,
                        origin, extraHeaders, uniqueKey, profile,
                    )
                ) return true
            }
            val saved = tails[uniqueKey]
            val total = totals[origin] ?: 0L
            val chunk = saved?.let { (from, tail) ->
                HotTail.serve(from, tail, clientRange, total)
            }
            if (chunk != null) {
                val end = clientRange!!.start + chunk.size - 1
                out.write(
                    (
                        "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                            "Content-Length: ${chunk.size}\r\n" +
                            "Content-Range: bytes ${clientRange.start}-$end/$total\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
                        ).toByteArray(),
                )
                out.write(chunk)
                out.flush()
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "hot tail: $rangeHeader served from memory (${chunk.size}B, no network)",
                )
                return true
            }
        }
        // The pre-warmed startup serves ONCE and only for the request that starts at byte 0 (the
        // first one the player makes on opening): that's where identifying the stream is at stake.
        // It's consumed from the map so a later seek doesn't receive bytes from the start.
        // The key includes the STARTING BYTE, not just the file. Without that, a pre-warm done for
        // a different point would get glued to the start of the stream regardless: 2 MB from
        // somewhere else in the film spliced onto the header, which is garbage to the demuxer and
        // leaves the player with no tracks -- causing exactly the failure this pre-warm exists to
        // prevent. The offsets do NOT always match: the saved position keeps advancing between when
        // it's pre-warmed and when the player opens.
        val hot = if ((clientRange?.start ?: 0L) == 0L && uniqueKey != null) {
            hotBuffers.remove("$uniqueKey@$start").also {
                if (it == null && hotBuffers.isNotEmpty()) {
                    // There WAS a pre-warmed startup but for ANOTHER point: the player opened at a
                    // different spot than the one that was prepared. It's not fatal (it's served
                    // from the origin), but it's a wasted pre-warm and needs to be visible: it used
                    // to be the silent failure.
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "hot startup DOESN'T MATCH: opened at $start and had ${hotBuffers.keys}",
                    )
                }
            }
        } else null
        // Every attempt kills the previous stretch of THIS SAME file before talking to the origin:
        // if the old connection is still alive, the new one hangs until the timeout.
        val (conn, closer) =
            openAtOrigin(origin, rangeToOrigin, extraHeaders, uniqueKey, profile)
            ?: return false
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val contentLength = conn.getHeaderField("Content-Length")
        // With a window the origin's Content-Range comes in REAL coordinates: sending it to the
        // player as-is would make it think its file starts at a byte that, for it, doesn't exist.
        // Content-Length is left untouched: the body being relayed is the same.
        val contentRange = if (start > 0L) {
            FileWindow.visibleContentRange(conn.getHeaderField("Content-Range"), start)
        } else conn.getHeaderField("Content-Range")
        // 206 only if the PLAYER asked for a range: with a window the origin is always asked for
        // one, but for whoever opened the whole file that's a normal 200.
        val isPartial = rangeHeader != null && code == HttpURLConnection.HTTP_PARTIAL
        val statusLine = if (isPartial) "206 Partial Content" else "200 OK"
        val resp = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            if (isPartial && contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(resp.toByteArray())
        // A window is only saved for SEEKS: `bytes=0-` is the main read, the player never cuts that
        // one off, and saving it would be 4 MB of RAM for nothing. Same if the stretch was already
        // being served by the hot startup, which has its own buffer.
        val window = if (
            uniqueKey != null && start == 0L && hot == null && (clientRange?.start ?: 0L) > 0L
        ) {
            GrowingBuffer(SEEK_WINDOW_SIZE).also { registerWindow(uniqueKey, clientRange!!.start, it) }
        } else null
        var written = 0L
        val t0 = System.currentTimeMillis()
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                // HOT STARTUP: if this stretch starts exactly where the pre-warm did, the bytes are
                // handed to the player AS THEY ARRIVE from the pre-warm, without waiting for the
                // full 2 MB. That was the only thing libVLC cared about to avoid giving up on
                // identifying the stream (see preWarm), and it's what lets the startup stop
                // blocking: it used to need the whole block before publishing the playlist, and that
                // cost 0.5-5 s of spinner per playback.
                //
                // The same bytes are then discarded from the origin so the headers already sent
                // don't need touching: 2 extra MB once per playback, in exchange for it never going
                // black.
                if (hot != null) {
                    var served = 0
                    while (true) {
                        val chunk = hot.slice(served)
                        if (chunk.isNotEmpty()) {
                            out.write(chunk); out.flush()
                            served += chunk.size; written += chunk.size
                        } else if (hot.closed) {
                            break
                        } else if (!hot.waitUntil(served + 1, STARTUP_WAIT_MS)) {
                            // It closed or stopped flowing: whatever's missing keeps getting read
                            // from the origin, which is where it all came from before this existed.
                            break
                        }
                    }
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "hot startup: ${served / 1024}KB served while they were downloading",
                    )
                    var toDiscard = served
                    while (toDiscard > 0) {
                        val n = ins.read(buf, 0, minOf(toDiscard, buf.size))
                        if (n < 0) break
                        toDiscard -= n
                    }
                }
                // SEEK WINDOW. This stretch gets copied to memory WHILE it's sent to the player, and
                // if it cuts off -which is what happens while bisecting a seek- reading continues
                // until it's full. The bytes saved are the ones this connection was going to fetch
                // anyway: they used to be thrown away by the `disconnect()` further below. See
                // [SeekWindow] for the ten-ranges measurement.
                var clientCutOff = false
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    window?.write(buf, n)
                    if (!clientCutOff) {
                        val delivered = runCatching { out.write(buf, 0, n) }.isSuccess
                        if (delivered) written += n else clientCutOff = true
                    }
                    // With no window there's nothing to gain reading a file nobody's watching.
                    if (clientCutOff && window == null) break
                    if (clientCutOff && (window?.available ?: 0) >= SEEK_WINDOW_SIZE) break
                }
                // Only if there really was a window: cutting off with no window is normal on the
                // main read, and reporting it as "0KB saved" made it look like the window had
                // failed when opening one wasn't even called for.
                if (clientCutOff && window != null) {
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "seek window at ${clientRange?.start}: " +
                            "${window.available / 1024}KB saved after the player cut off",
                    )
                }
            }
            out.flush()
        } finally {
            window?.close()
            uniqueKey?.let { releaseLive(it) }
            liveConnections.release(closer)
            // disconnect() ALWAYS, even when the player cuts the connection off mid-way (seek →
            // "broken pipe"). Used to skip this line on exception and the CDN connection stayed
            // alive in HttpURLConnection's pool draining the rest of the file: the origin saw two
            // connections at once and the NEW one (for the point seeked to) was starved of data.
            runCatching { conn.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "direct ${rangeHeader ?: "(all)"}" +
                    (if (start > 0L) " [window from $start → requested $rangeToOrigin]" else "") +
                    " → code=$code ${written / 1024}KB in ${System.currentTimeMillis() - t0}ms",
            )
        }
        return true
    }
}
