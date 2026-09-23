package com.arkiv.player.playback

import com.arkiv.player.data.gateway.ChannelCdn
import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Local HLS proxy for Magis live TV.
 *
 * Exists because the live CDN requires `Content-Auth` and `Content-License` headers, and
 * `Content-Auth` **expires in seconds**: every segment needs a fresh signature, so handing the
 * player a static m3u8 was never going to be enough (this was originally built because libVLC
 * could only pass through `:http-referrer` and `:http-user-agent`; ExoPlayer, today's player,
 * can set arbitrary headers itself, but the proxy stays because the per-segment fresh signing
 * still has to happen somewhere).
 *
 * The proxy downloads the playlist, rewrites the `.ts` absolute URLs to point at itself, and sets
 * the headers on every request to the origin. The local player sees `127.0.0.1`; Chromecast/DLNA
 * see the phone's LAN IP (see [lanUrl]) because, from the first channel that opens, the socket
 * listens on ALL interfaces, not just loopback (see the KDoc of [start]/[urlFor]). That alone,
 * with nothing else, would leave the channel -- Magis's paid content -- visible to any device on
 * the same WiFi that scans the ephemeral port: that's why every URL the proxy hands out
 * ([urlFor], [lanUrl], and the segment URLs the proxy itself rewrites inside the m3u8) carries
 * [generateToken]'s random token as a query param, and [handle] requires it before resolving any
 * route.
 *
 * [onSessionDead] fires when a request gives up after two 403s in a row ([requestFromOrigin]): that
 * means the channel's session (token/license) expired, not the signature -- see the KDoc of
 * [requestFromOrigin] -- so whoever resolved it (`LiveController`) must ask the gateway for it again
 * next time, instead of serving the cached copy that's already known to be dead until it expires
 * on its own (up to 300s; see `LiveController.valid`). Without this notice the channel stays
 * broken that whole time even if the user zaps away and back (a finding from "the same wave" of
 * the final review).
 */
class LiveHlsProxy(
    private val signatures: SegmentSignature,
    private val onSessionDead: (channel: String) -> Unit = {},
) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var session: LiveSession? = null

    /**
     * Random token for the current playback session (see [generateToken]). Lives as long as the
     * `ServerSocket`: born in [start], dies in [stop], NOT on every [urlFor] -- if it changed on
     * every channel zap, the URL already recorded in the local player or in the media loaded on
     * Chromecast (same port, see [urlFor]'s KDoc) would start returning 403 mid-playback.
     */
    @Volatile private var token: String? = null

    val port: Int get() = server?.localPort ?: -1

    /**
     * The CDN currently serving this channel. Changes when the first one rejects and it falls
     * back to the next (see [servePlaylist]).
     *
     * The SEGMENTS need it as much as the playlist does: their urls come from the playlist, so
     * they point at the host that served it, and signing them with another CDN's `authBase` would
     * be the same crossed pair -one's token, another's host- that the CDN rejects with 401.
     */
    @Volatile private var activeCdn: ChannelCdn? = null

    /**
     * Segment names of the last playlist served, in order. Exists ONLY for the log.
     *
     * Without this, a `segment -> 404` doesn't say where in the window it was, and that's exactly
     * the question that couldn't be answered all through the night of 2026-08-14: whether the
     * missing one is always the last -the edge the origin announces before writing- or whether
     * they're scattered. A repeated "position 6/6" means one thing and a "3/6" a completely
     * different one.
     */
    @Volatile private var lastSegments: List<String> = emptyList()

    /** "4/6" if the segment is in the last playlist served, "?/N" if it no longer is. For the log. */
    private fun positionInPlaylist(name: String): String {
        val list = lastSegments
        val i = list.indexOf(name)
        return if (i >= 0) "${i + 1}/${list.size}" else "?/${list.size}"
    }

    private fun cdnFor(s: LiveSession): ChannelCdn =
        activeCdn ?: s.cdns.firstOrNull() ?: ChannelCdn(s.cflHost, s.authBase)

    private suspend fun contentAuth(s: LiveSession, cdn: ChannelCdn = cdnFor(s)): String {
        val f = signatures.sign(cdn.token)
        return "${cdn.authBase}&sign2_method=sign_o3&instance=0" +
            "&start_moment=${f.moment}&sign2=${f.sign2}"
    }

    /** Idempotent, same as [ArchiveCacheProxy.start]. [bindLan] is for Chromecast. */
    @Synchronized
    fun start(bindLan: Boolean = false): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = if (bindLan) ServerSocket(0) else ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = sock
        running = true
        token = generateToken()
        Thread {
            while (running && !sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread { handle(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return sock.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        session = null
        token = null
    }

    /**
     * Random per-playback-session token, from a cryptographically secure source -not fixed, not
     * derived from anything predictable (not the channel, not the port, not the time)-. It's the
     * only access control since [start] started listening on the whole LAN (`0.0.0.0`) instead of
     * loopback only: without this, any device on the same WiFi that scans the ephemeral port
     * watches the channel -Magis's paid content- with nothing else needed.
     *
     * 24 bytes of hex from [java.security.SecureRandom] instead of `java.util.Base64`/
     * `android.util.Base64`: doesn't depend on desugaring for the first, and doesn't blow up in
     * pure JVM tests (which don't mock `android.util.*` unless something traps it, like `Log.w`
     * does in [handle]) for the second.
     */
    private fun generateToken(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Sets the channel session and returns the URL handed to the player.
     *
     * `bindLan = true`: the proxy becomes reachable over the LAN as soon as the FIRST channel
     * opens, not only when casting -- same criterion the torrent HTTP server used (source removed
     * in this branch's pruning), with `ServerSocket(0)` and no IP = all interfaces. The
     * alternative -- opening on loopback and "widening" to LAN only when casting starts -- would
     * change the PORT mid-playback: this URL (with TODAY's port) is already recorded as the
     * player's local media, and in the media loaded for Chromecast/DLNA (see
     * `LiveHlsProxy.lanUrl`); reopening the socket on another port would break both. 127.0.0.1
     * keeps working the same with the socket on all interfaces, so this changes nothing for local
     * playback.
     */
    fun urlFor(newSession: LiveSession): String {
        // ZAPPING starts here. It's the mark everything about live playback gets measured against:
        // from this instant to the first frame actually painted is what the user waits looking at
        // black when switching channels, and without this line the log would only start once the
        // player requests the playlist.
        val previous = session?.channel
        android.util.Log.w(
            "LiveHlsProxy",
            "channel → ${newSession.channel}" + (if (previous != null && previous != newSession.channel) " (came from $previous)" else "") +
                " cdn=${newSession.cflHost}" + (if (newSession.cdns.size > 1) " (+${newSession.cdns.size - 1} backup)" else ""),
        )
        session = newSession
        // The chosen CDN is from the PREVIOUS session: its tokens don't work for this channel, and
        // worse, the host might not even serve it. Every channel picks again from the start.
        activeCdn = null
        if (port <= 0) start(bindLan = true)
        return "http://127.0.0.1:$port/live.m3u8?t=$token"
    }

    /**
     * URL of the channel the proxy is serving RIGHT NOW, reachable over the LAN (Chromecast/DLNA)
     * -- same host:port the player already uses locally, just with the phone's IP instead of
     * loopback (see [urlFor]'s KDoc: the socket listens on every interface from the first channel
     * opened, so there's nothing to "widen" here). `null` if no channel has been opened yet -there
     * is nothing to cast-.
     */
    fun lanUrl(ip: String): String? {
        if (port <= 0) return null
        val t = token ?: return null
        return "http://$ip:$port/live.m3u8?t=$t"
    }

    private fun handle(socket: Socket) = socket.use { s ->
        // Same as ArchiveCacheProxy.serve() (same package, same pattern): ANY exception here
        // -network, or a session that disappears mid-request because the user left the player
        // right as a segment was halfway through downloading (see stop())- is swallowed and
        // logged instead of left to escape. On Android an uncaught exception on ANY thread kills
        // the WHOLE process, not just this connection.
        runCatching {
            // Host through which THIS client reached the proxy: the already-accepted socket's own
            // local address, not the request's `Host` header. Preferred over parsing `Host`
            // because `socket.localAddress` is a fact of the TCP connection -which interface
            // received the packet-, not something the client declares: no need to validate or
            // sanitize it before putting it into a response URL, and it doesn't depend on the
            // local player, Chromecast or the DLNA client sending a well-formed Host header (some
            // HLS players don't send one). It's the same thing manually resolving headers would
            // get, without the header-injection risk or the extra parsing.
            // `hostAddress` is a platform type (Java's String!): in practice it's never null for
            // an already-resolved InetAddress like this one, but the fallback leaves the local
            // path intact against any corner case instead of blowing up the connection.
            val myHost = s.localAddress.hostAddress ?: "127.0.0.1"
            val reader = s.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return@runCatching
            val path = line.split(" ").getOrNull(1) ?: return@runCatching
            val output = s.getOutputStream()
            // Access control: since start() started listening on the whole LAN (see its KDoc), ANY
            // route -playlist or segment- must carry this session's token before anything gets
            // resolved. Clean, generic rejection (403, no body): whoever is scanning the port
            // shouldn't get a single hint about what routes exist or why it failed.
            if (!isTokenValid(path)) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return@runCatching
            }
            when {
                path.startsWith("/live.m3u8") -> servePlaylist(output, myHost)
                path.startsWith("/seg?") -> serveSegment(path, output)
                else -> output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }.onFailure { e ->
            runCatching { android.util.Log.w("LiveHlsProxy", "handle() failed: ${e.message}") }
        }
    }

    /** Extracts the value of a query param from a path like `/live.m3u8?t=...&other=...`. */
    private fun queryValue(path: String, key: String): String? {
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
    }

    /**
     * Compares the received token against the current session's. `MessageDigest.isEqual` instead
     * of `==`: constant-time comparison, so timing doesn't leak how much of the token a blind
     * guesser got right.
     */
    private fun isTokenValid(path: String): Boolean {
        val expected = token ?: return false
        val received = queryValue(path, "t") ?: return false
        return java.security.MessageDigest.isEqual(received.toByteArray(), expected.toByteArray())
    }

    /**
     * Asks the origin with the current signature and, on a 403, refreshes the signature and
     * retries **once**. A 403 that survives the retry means the channel's session expired, not the
     * signature: [onSessionDead] fires so whoever is playing re-resolves it.
     *
     * Receives [s] already resolved (doesn't re-read the `session` field): that way the whole
     * request uses the SAME session end to end even if [stop] (or another [urlFor]) changes it from
     * another thread partway through -- that's what closes the race window from finding C2
     * (`session!!.license` with `session` already null).
     *
     * A single 403 counts as ONE rejection, no matter how many HTTP attempts this function makes
     * to resolve it: it used to call `signatures.rejected()` once PER ATTEMPT (up to two, in here),
     * so a single "real" 403 -e.g. the "session expired, not the signature" case- was already
     * pushing [FirmaConRespaldo]'s counter two steps at once, triggering the fallback with only
     * HALF the real rejections it should take to see (finding F1 from the final review).
     * [notified] avoids that.
     */
    private fun requestFromOrigin(url: String, s: LiveSession, cdn: ChannelCdn = cdnFor(s)): HttpURLConnection? {
        var notified = false
        // The WHAT of the log: we don't know anything about this CDN yet (the VOD one takes
        // between 0.2s and 20s per range, measured; the live one was never measured). Without the
        // per-request latency there's no way to tell whether a cutoff is the CDN's, the proxy's or
        // the player's -- all three look the same.
        val kind = if (url.endsWith(".m3u8")) "playlist" else "segment"
        repeat(2) { attempt ->
            val t0 = System.currentTimeMillis()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Auth", runBlocking { contentAuth(s, cdn) })
                setRequestProperty("Content-License", s.license)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("App", APP)
                setRequestProperty("App-Version", APP_VERSION)
                setRequestProperty("X-Buffer", "0")
            }
            val code = c.responseCode
            val ms = System.currentTimeMillis() - t0
            // Both 401 and 403: this CDN uses 401 and only checking 403 left the signature
            // considered good, the backup never switching, and the channel dead with a 502. See
            // [isSignatureRejection].
            if (!isSignatureRejection(code)) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "$kind → $code in ${ms}ms" + (if (attempt > 0) " (2nd attempt)" else "") +
                        // The WHY of the rejection, which used to get thrown away. A bare "→ 409"
                        // doesn't tell apart "the signal doesn't exist" from "this session is no
                        // longer valid", and without that there's nothing to do but guess: on
                        // 2026-08-14 one channel gave 409 on the playlist and 404 on the segments
                        // while the other four were running fine, and the log wasn't enough to say
                        // why.
                        // Nobody else reads the body of a non-200 (the playlist cuts with
                        // error502 and [requestOk] discards the connection), so consuming it here
                        // doesn't take anything away from anyone.
                        (if (code != 200) " · ${originReason(c)}" else ""),
                )
                // Reports that the signature used in THIS request was accepted: it's the signal
                // FirmaConRespaldo needs to reset its consecutive-rejections counter.
                signatures.accepted()
                return c
            }
            // 401/403 = the signature didn't work. Logged separately because it's the EXPENSIVE
            // failure: two attempts and then the session is given up for dead, meaning the channel
            // cuts out.
            android.util.Log.w("LiveHlsProxy", "$kind → $code SIGNATURE REJECTED in ${ms}ms (attempt ${attempt + 1}/2)")
            // The report is what lets FirmaConRespaldo detect that the algorithm stopped working
            // and switch to the gateway. Without this, the backup never kicks in.
            if (!notified) { signatures.rejected(); notified = true }
            c.disconnect()
        }
        android.util.Log.w(
            "LiveHlsProxy",
            "$kind: two rejections in a row on ${cdn.cflHost} (channel=${s.channel})",
        )
        return null
    }

    /**
     * What the CDN said when rejecting, trimmed for the log.
     *
     * Keeps the error body if there is one (the portal answers with JSON carrying its own error
     * code, which is the useful part) and otherwise the first header that explains something.
     * Never throws: this runs on the rejection path, and breaking here would turn a channel that
     * merely fails into one that also loses the connection.
     */
    private fun originReason(c: HttpURLConnection): String = runCatching {
        val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
        val says = if (body.isNotEmpty()) {
            body.take(300).replace('\n', ' ')
        } else {
            c.responseMessage ?: "no body"
        }
        // And WHO it came from. Both live CDNs sit behind Cloudflare (verified on 2026-08-14:
        // 104.18.x.x, `Server: cloudflare`), so a rejection can come from the origin or from the
        // edge. The difference decides everything: if a 404 arrives `HIT`, it's a CACHED negative
        // response and retrying the same URL can't work no matter how many times it's asked --
        // you'd have to dodge the cache, not insist.
        "$says [cf-cache=${c.getHeaderField("cf-cache-status") ?: "-"}" +
            " age=${c.getHeaderField("age") ?: "-"} ray=${c.getHeaderField("cf-ray") ?: "-"}]"
    }.getOrDefault("couldn't read the reason")

    private fun error502(output: java.io.OutputStream, reason: String = "") {
        // The 502 is the ONLY thing the player sees no matter what happens in here, so the reason
        // has to stay on the proxy's side or it's lost. It's the same problem ArchiveCacheProxy
        // solved by recording the last HTTP code per origin.
        if (reason.isNotEmpty()) android.util.Log.w("LiveHlsProxy", "502 to the player: $reason")
        output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    private fun servePlaylist(output: java.io.OutputStream, myHost: String) {
        // A single read of the volatile fields for the WHOLE request: if stop() (or a new
        // urlFor()) changes `session`/`server` from another thread partway through, this request
        // keeps going with whatever values it had at the start. See the note on [requestFromOrigin].
        val t0 = System.currentTimeMillis()
        val s = session ?: return error502(output, "no channel session")
        val myPort = port
        val myToken = token ?: return error502(output, "no proxy token")
        // `playCode`, NOT `channel`: that's what the signal is called on the CDN, and they aren't
        // always the same (see [LiveSession.playCode]'s KDoc -- `cyx-RCNHD` is served under
        // another name). With the channel's code here, the CDN received a request for a different
        // signal than the one the license we sent authorizes, and answered 401: the channel loaded
        // forever.
        // The CDNs are tried in order until one serves. Measured on 2026-08-14: the portal gives
        // THREE live hosts and only the first was being used; that day it answered 401 twice and
        // the channel ended up having another one available in the same response. The winner is
        // kept as [activeCdn] so the segments -whose urls come from THIS playlist- get signed with
        // its same `authBase`.
        //
        // Starts with whichever was already working, if any: reordering on every request would
        // send the whole channel back to the first one's hiccups on the very next refresh.
        // And it's RETRIED, same as the segments. A playlist that doesn't arrive isn't a dead
        // session: on 2026-08-14 `cyx-RCNHD`'s origin answered 404 to the playlist four times in a
        // row over 9s -with the channel playing perfectly until second 39- and came back on its
        // own. The original app does exactly this: its ffmpeg reloads an insufficient playlist up
        // to `max_reload` times (1000 by default; the literal is in `libijkffmpeg.so`) instead of
        // giving up on the channel at the first stumble.
        //
        // And a code != 200 now also moves on to the next CDN. It didn't before: `requestFromOrigin`
        // returns the connection on any code that isn't a signature rejection, so a 404 cut the
        // loop at the first CDN and answered 502 without ever trying the backup -the same hole the
        // segments had-.
        val inOrder = (listOfNotNull(activeCdn) + s.cdns).distinctBy { it.cflHost }
        var c: java.net.HttpURLConnection? = null
        var chosen: ChannelCdn? = null
        var anyAnswered = false
        var lastCode = -1
        loop@ for (round in 0 until PLAYLIST_ATTEMPTS) {
            var answeredThisRound = false
            for (cdn in inOrder) {
                val r = requestFromOrigin("http://${cdn.cflHost}/live/${s.playCode}.m3u8", s, cdn)
                if (r == null) {
                    if (cdn !== inOrder.last()) {
                        android.util.Log.w("LiveHlsProxy", "playlist: ${cdn.cflHost} rejected → trying the next CDN")
                    }
                    continue
                }
                answeredThisRound = true
                anyAnswered = true
                lastCode = r.responseCode
                if (lastCode == 200) { c = r; chosen = cdn; break@loop }
                runCatching { r.disconnect() }
            }
            // If NOBODY answered, every CDN rejected the signature: retrying won't fix that -the
            // credential doesn't improve on its own- and would also inflate [FirmaConRespaldo]'s
            // rejection counter, which counts ONE rejection per proxy request, not per HTTP attempt
            // (finding F1; see [requestFromOrigin]'s KDoc). Falls through to the usual behaviour:
            // dead session.
            if (!answeredThisRound) break@loop
            if (round < PLAYLIST_ATTEMPTS - 1) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "playlist for ${s.channel} not served (last $lastCode) → " +
                        "retry ${round + 2}/$PLAYLIST_ATTEMPTS in ${SEGMENT_WAIT_MS}ms",
                )
                Thread.sleep(SEGMENT_WAIT_MS)
            }
        }
        if (c == null || chosen == null) {
            // The session is given up for dead ONLY if nobody ever answered: that's a signature
            // rejection on every CDN. A 404 is something else -the CDN spoke, and said not right
            // now- and asking the gateway for the session again over that would treat a pothole
            // like an expired credential.
            if (!anyAnswered) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "playlist: all ${inOrder.size} CDNs rejected → giving up the session for dead (channel=${s.channel})",
                )
                onSessionDead(s.channel)
            }
            return error502(output, "no CDN served the playlist for ${s.channel} (last code $lastCode)")
        }
        if (activeCdn?.cflHost != chosen.cflHost) {
            android.util.Log.w("LiveHlsProxy", "active CDN → ${chosen.cflHost} (channel=${s.channel})")
        }
        activeCdn = chosen
        val playlistUrl = "http://${chosen.cflHost}/live/${s.playCode}.m3u8"
        val base = URL(playlistUrl)
        val raw = c.inputStream.bufferedReader().readText()
        val body = raw.lineSequence()
            .joinToString("\n") { ln -> rewriteLine(ln, base, myHost, myPort, myToken) } + "\n"
        val bytes = body.toByteArray()
        // How many segments the playlist announces IS the live data point: it defines how much
        // cushion there is before the player reaches the edge. Below 2-3, any CDN hiccup cuts it.
        // `MEDIA-SEQUENCE` says whether the window is advancing or we're re-reading the same one.
        val names = raw.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringAfterLast('/').substringBefore('?') }.toList()
        lastSegments = names
        val segmentCount = names.size
        val sequence = raw.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }?.substringAfter(':') ?: "?"
        android.util.Log.w(
            "LiveHlsProxy",
            "playlist served channel=${s.channel} segments=$segmentCount seq=$sequence " +
                "${bytes.size}B in ${System.currentTimeMillis() - t0}ms",
        )
        output.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        output.write(bytes)
    }

    /**
     * One live segment.
     *
     * **Never answers an empty body behind a success header.** It used to write the header with
     * the CDN's code as-is (`HTTP/1.1 404 OK`) and only then try to copy `inputStream`, which
     * throws on a 404: the player received ZERO bytes, which looks exactly like the end of a
     * stream. Measured on the Fire TV on 2026-08-14 with RCN FHD: a single 404 and VLC drained the
     * decoder and fired `EndReached` at 19s, with the channel perfectly alive (the playlist kept
     * refreshing, seq 2080 → 2082). A 502, on the other hand, is an ERROR, and the player retries
     * an error.
     *
     * It's the resilience the original app has for free: it goes STRAIGHT to the CDN, so a 404
     * reaches it as an error and ffmpeg reconnects (`reconnect=1`, `reconnect_delay_max=5` in its
     * ijkplayer config). With a proxy in the middle, that has to be put back by hand.
     */
    private fun serveSegment(path: String, output: java.io.OutputStream) {
        val t0 = System.currentTimeMillis()
        val s = session ?: return error502(output, "segment with no channel session")
        val u = URLDecoder.decode(path.substringAfter("u=").substringBefore("&"), "UTF-8")
        val name = u.substringAfterLast('/')
        val c = getSegment(u, s)
            ?: return error502(output, "no CDN served the segment $name (position ${positionInPlaylist(name)})")
        // The header is written ONLY HERE, with a 200 in hand. Once written it can't be turned
        // into an error: that's why this can't happen before knowing there's a body.
        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        // Bytes are counted while copying, not from Content-Length: the CDN can cut off partway
        // and that looks like a short segment, which is exactly what leaves the player starved.
        val copied = runCatching { c.inputStream.copyTo(output, 64 * 1024) }.getOrDefault(-1L)
        android.util.Log.w(
            "LiveHlsProxy",
            "segment served ${copied / 1024}KB in ${System.currentTimeMillis() - t0}ms" +
                (if (copied < 0) " (CUT OFF)" else "") + " $name",
        )
    }

    /**
     * The requested segment, already with a 200, or `null` if it's truly nowhere to be found.
     *
     * Two tiers, in this order:
     *  1. **retries against the active CDN**, the normal case: at the live edge the player asks
     *     for the segment BEFORE the CDN publishes it. On 2026-08-14 VLC requested three ~5s video
     *     segments 1.3s apart -it was running toward the edge- and the third gave 404 simply
     *     because it didn't exist yet;
     *  2. **the channel's other CDNs**, in case the one being used fell behind. The playlist path
     *     already walks them this way ([servePlaylist]); the segment path didn't, and the backup
     *     sat there unused.
     *
     * The waits come from the segment's size: they last ~5s and the playlist announces 6, i.e.
     * ~30s of cushion. Spending up to ~1s waiting for it to be published doesn't empty anything;
     * giving up on it right away does.
     */
    private fun getSegment(url: String, s: LiveSession): HttpURLConnection? {
        val name = url.substringAfterLast('/')
        repeat(SEGMENT_ATTEMPTS) { attempt ->
            val c = requestOk(url, s)
            if (c != null) return c
            if (attempt < SEGMENT_ATTEMPTS - 1) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "segment $name (position ${positionInPlaylist(name)} in the playlist) isn't " +
                        "there yet → retry ${attempt + 2}/$SEGMENT_ATTEMPTS in ${SEGMENT_WAIT_MS}ms",
                )
                Thread.sleep(SEGMENT_WAIT_MS)
            }
        }
        // The active CDN doesn't have it. Segments come from THAT host's playlist, so asking
        // another one means swapping the host in the url (the signature already goes per CDN,
        // which is [requestFromOrigin]'s `cdn` parameter).
        //
        // The whole AUTHORITY is replaced, not just the host: `cflHost` carries the port glued on
        // when there is one (that's why the playlist is built as "http://${cdn.cflHost}/live/..."),
        // so passing it as `host` to URL's constructor would leave a url with two ports and fail
        // to resolve.
        val others = s.cdns.filter { it.cflHost != cdnFor(s).cflHost }
        for (cdn in others) {
            val alternate = runCatching {
                url.replaceFirst("://${URL(url).authority}", "://${cdn.cflHost}")
            }.getOrNull() ?: continue
            android.util.Log.w("LiveHlsProxy", "segment $name → trying CDN ${cdn.cflHost}")
            val c = requestOk(alternate, s, cdn)
            if (c != null) return c
        }
        return null
    }

    /**
     * [requestFromOrigin] but only returning servable 200 responses, without letting exceptions
     * escape.
     *
     * Both things point at the same thing: a CDN failure has to reach the player as an ERROR and
     * never as the end of the stream. `requestFromOrigin` returns the connection on ANY code that
     * isn't a signature rejection -404 included-, and a CDN that's down throws on connect, which
     * without this `runCatching` takes the whole response down with it and the player sees the
     * connection cut.
     */
    private fun requestOk(url: String, s: LiveSession, cdn: ChannelCdn = cdnFor(s)): HttpURLConnection? {
        val c = runCatching { requestFromOrigin(url, s, cdn) }.getOrNull() ?: return null
        if (runCatching { c.responseCode }.getOrDefault(-1) == 200) return c
        runCatching { c.disconnect() }
        return null
    }

    /**
     * Rewrites one playlist line so any URI it carries (a segment or an encryption key) goes
     * through the proxy instead of straight to the CDN.
     *
     * It used to only touch lines that literally started with `"http"` and contained `".ts"`.
     * That left out (finding I1):
     * - RELATIVE segments (`c_1.ts`): the player resolves them against `127.0.0.1`, a path the
     *   proxy doesn't handle -> 404 and playback cuts out;
     * - protocol-relative segments (`//cdn.host/c_1.ts`): resolved DIRECTLY against the CDN, with
     *   no signature;
     * - the `#EXT-X-KEY` URI (if the stream is encrypted): the line starts with `#`, never
     *   matched, and the key would have been requested from the CDN unsigned.
     *
     * `URL(base, spec)` resolves all three URI forms (absolute, protocol-relative, relative)
     * exactly like a browser would, so there's no need to reinvent that logic by hand.
     *
     * [myHost] is the host THIS client used to request the playlist (see [handle]), not a fixed
     * `127.0.0.1` (a finding from a previous agent, Task 20): if segment URLs ALWAYS stayed on
     * loopback, Chromecast/DLNA -which request the playlist over the phone's LAN IP, see
     * [lanUrl]- would receive segments pointing at `127.0.0.1`, which for THEM is their own
     * device, not the phone. Black screen with no error at all. The local player keeps being
     * served from `127.0.0.1` same as before because it requests the playlist over loopback (see
     * [urlFor]), so `myHost` arrives as `"127.0.0.1"` for it, changing nothing.
     */
    private fun rewriteLine(ln: String, base: URL, myHost: String, myPort: Int, myToken: String): String {
        val t = ln.trim()
        if (t.isEmpty()) return ln
        if (t.startsWith("#EXT-X-KEY") && t.contains("URI=")) {
            return rewriteUriInTag(ln, base, myHost, myPort, myToken)
        }
        if (t.startsWith("#")) return ln  // the rest of the tags don't carry their own URI
        val absolute = runCatching { URL(base, t) }.getOrNull() ?: return ln
        // The token goes AFTER u= (never before): serveSegment() extracts u with
        // `substringBefore("&")`, so any new parameter has to go after it.
        return "http://$myHost:$myPort/seg?u=${URLEncoder.encode(absolute.toString(), "UTF-8")}&t=$myToken"
    }

    /** Rewrites ONLY the quoted URI in an `#EXT-X-KEY:...,URI="..."` tag, leaving the rest untouched. */
    private fun rewriteUriInTag(ln: String, base: URL, myHost: String, myPort: Int, myToken: String): String {
        val m = Regex("URI=\"([^\"]*)\"").find(ln) ?: return ln
        val group = m.groups[1] ?: return ln
        val absolute = runCatching { URL(base, group.value) }.getOrNull() ?: return ln
        val rewritten = "http://$myHost:$myPort/seg?u=${URLEncoder.encode(absolute.toString(), "UTF-8")}&t=$myToken"
        return ln.replaceRange(group.range, rewritten)
    }

    companion object {
        const val UA = "Ranger/4.9.4-17294ac0"
        private const val APP = "com.android.msandroid"
        private const val APP_VERSION = "49902"

        /**
         * How many times the SAME segment is requested from the active CDN before trying another
         * one, and how long to wait between attempts.
         *
         * The cap comes from MEASURING, not from the original app's budget. It gives ffmpeg
         * `reconnect_delay_max=5` (see the decompiled `yc/C6276a.java`) and for a while this was
         * set to six attempts to match it -- but that number is for **reconnecting a network that
         * dropped**, not for waiting on bytes that don't exist, and here the failure is the second
         * kind.
         *
         * Measured on the Fire TV on 2026-08-14, with five segments that gave 404: **none**
         * arrived within the 5s window. Four never showed up at all -requested again 30s later
         * they were still 404- and the fifth took 13s, unreachable with any sane cap. So
         * stretching the window buys nothing: either the segment is there right away, or it isn't.
         *
         * Three attempts remain because they still cover the only thing the wait can fix -a
         * segment published with a blink's worth of delay- and because what actually rescues the
         * channel is something else: [PlayerViewModel.reopenLiveAfterCut], which reconnects at
         * the live edge in 2s. The sooner control is handed back to it, the sooner the picture
         * returns.
         */
        private const val SEGMENT_ATTEMPTS = 3
        private const val SEGMENT_WAIT_MS = 800L

        /**
         * Full rounds through ALL the CDNs looking for the playlist. Fewer than a segment's, on
         * purpose: the player retries the playlist on its own (VLC requested it every ~3s on
         * 2026-08-14), so here it's enough to cover the short gap and hand control back before it
         * gives up.
         */
        private const val PLAYLIST_ATTEMPTS = 3
    }
}
