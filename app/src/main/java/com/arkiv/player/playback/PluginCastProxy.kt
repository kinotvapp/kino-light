package com.arkiv.player.playback

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.HostNotAllowedException
import com.arkiv.player.data.plugin.PluginFetchException
import com.arkiv.player.data.plugin.PrivateAddressException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a PLUGIN stream to a Chromecast or a DLNA TV on the LAN, fetching it the way the phone's
 * own player does: through the plugin's gated client ([clientFor] = `AppGraph.pluginStreamClient`),
 * so every request and every redirect hop meets the plugin's host rules (declared hosts, typed
 * servers, `streamHosts` "any"/the broad video permission, https-only, no IP literal or local name)
 * and [com.arkiv.player.data.plugin.PluginDns] refuses a name that resolves into the home network.
 * The plugin's headers go on every upstream request and nowhere else.
 *
 * It is NOT [ArchiveCacheProxy] (Xuper/Magis): that one fetches with a plain `HttpURLConnection`
 * and knows nothing about a plugin's hosts. A cast must never ask the person about a host either:
 * the client is built with no `askAboutFor`, so an undeclared host simply fails.
 *
 * Access: the URL carries only a 128-bit random token (`/t/<token>/…`, in the PATH because neither
 * a DLNA renderer nor the Cast receiver can send a header). A token names exactly ONE stream: its
 * origin, its headers, its hosts. A request can never name an upstream URL; one without a live
 * token is a 403. Tokens die after [IDLE_TTL_MS] without a request, after [MAX_AGE_MS], on [stop],
 * and past [MAX_SESSIONS] the least recently used one makes room.
 *
 * HLS ([Shape.HLS]): the origin is served at `/t/<token>/index.m3u8`, REWRITTEN by
 * [HlsPlaylistRewriter] so that every variant, segment, key and init section it names points back
 * here as `/t/<token>/r/<n>.<ext>`. `n` indexes the urls THIS proxy found in a playlist it fetched
 * for that token: a token reaches its stream and what the stream's own playlists name, never an
 * arbitrary url. Each of those fetches goes through the same gated client with the same headers,
 * so a segment on another host is fetched only when the plugin's host rules allow that host.
 *
 * One title = one token ([register] is keyed by the episode): registering it again with a NEW url
 * (the plugin re-resolved an expiring link) swaps the origin under the same token, so a renderer
 * that is already pulling it picks the new link up on its next request.
 *
 * Logs name a host at most, never a path, a query, a header or a token.
 */
class PluginCastProxy(
    private val clientFor: (EffectiveHosts) -> OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { runCatching { android.util.Log.w(TAG, it) } },
) {
    /** How the origin is served: as it is ([FILE], byte ranges passed through) or as a rewritten [HLS] playlist. */
    enum class Shape { FILE, HLS }

    /**
     * The urls a token's playlists named, by index: the only upstream urls besides the origin that
     * token can reach. Bounded: a live channel keeps adding segments, the oldest are forgotten.
     */
    internal class Derived {
        private val byUrl = LinkedHashMap<String, Int>()
        private val byIndex = HashMap<Int, Pair<String, HlsPlaylistRewriter.Kind>>()
        private var next = 0

        @Synchronized
        fun add(url: String, kind: HlsPlaylistRewriter.Kind): Int {
            byUrl[url]?.let { return it }
            val n = next++
            byUrl[url] = n
            byIndex[n] = url to kind
            while (byUrl.size > MAX_DERIVED) {
                val oldest = byUrl.entries.iterator().next()
                byIndex.remove(oldest.value)
                byUrl.remove(oldest.key)
            }
            return n
        }

        @Synchronized
        fun get(n: Int): Pair<String, HlsPlaylistRewriter.Kind>? = byIndex[n]

        @Synchronized
        fun clear() {
            byUrl.clear()
            byIndex.clear()
        }
    }

    internal class Session(
        @Volatile var origin: String,
        @Volatile var headers: Map<String, String>,
        @Volatile var hosts: EffectiveHosts,
        @Volatile var client: OkHttpClient,
        val shape: Shape,
        /** What the receiver is told the bytes are, when the origin only says `application/octet-stream`. */
        @Volatile var mime: String,
        val createdAt: Long,
    ) {
        @Volatile var lastUsedAt: Long = createdAt
        val derived = Derived()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val tokenByKey = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    @Volatile private var server: ServerSocket? = null

    /** The port, or 0 while stopped. */
    val port: Int get() = server?.takeIf { !it.isClosed }?.localPort ?: 0

    /**
     * Idempotent: the running port, or a new socket on every interface (a TV must reach it; the
     * token is what keeps everyone else out).
     */
    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val sock = ServerSocket(0)
        server = sock
        Thread({
            while (!sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread({ serve(s) }, "PluginCastProxy-conn").apply { isDaemon = true }.start()
            }
        }, "PluginCastProxy").apply { isDaemon = true }.start()
        return sock.localPort
    }

    /** Closes the socket and forgets every stream: their URLs answer nothing from now on. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        sessions.clear()
        tokenByKey.clear()
    }

    /**
     * The loopback URL ([lanUrl]-respellable, see [ArchiveCacheProxy.lanUrl]) a renderer can pull
     * [origin] from, or null while the proxy is not running. [key] is the title (its episode id);
     * [hosts] MUST be the installed record's (`PlayerData.pluginHosts`), never plugin output.
     */
    @Synchronized
    fun register(
        key: String,
        origin: String,
        headers: Map<String, String>,
        hosts: EffectiveHosts,
        shape: Shape,
        mime: String,
    ): String? {
        val port = port.takeIf { it > 0 } ?: return null
        val now = clock()
        val existing = tokenByKey[key]?.let { token -> live(token, now)?.let { token to it } }
        val token = if (existing != null && existing.second.shape == shape) {
            val (token, session) = existing
            if (session.origin != origin || session.headers != headers || session.hosts != hosts) {
                // Same title, new link (an expiring URL resolved again): same token, new origin.
                if (session.hosts != hosts) session.client = clientFor(hosts)
                session.origin = origin
                session.headers = headers.toMap()
                session.hosts = hosts
                // The old link's playlists named urls that may have expired with it.
                session.derived.clear()
            }
            session.mime = mime
            token
        } else {
            existing?.first?.let(::revoke)
            newToken().also { token ->
                sessions[token] = Session(origin, headers.toMap(), hosts, clientFor(hosts), shape, mime, now)
                tokenByKey[key] = token
                evictOverflow()
            }
        }
        return "http://127.0.0.1:$port$TOKEN_PREFIX$token/${routeFor(shape, mime)}"
    }

    /** Forgets [key]'s stream; its URL answers 403 from now on. */
    @Synchronized
    fun forget(key: String) {
        tokenByKey[key]?.let(::revoke)
    }

    /**
     * Is [url] one of this proxy's stream URLs whose token no longer answers (forgotten, expired)?
     * False for a URL on any other port: the same `/t/` shape is used by other proxies, not ours
     * to judge. A cast reconnect uses it never to hand the receiver a dead URL again. Not a use.
     */
    fun revoked(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val p = port
        if (p <= 0 || uri.port != p) return false
        val token = ArchiveCacheProxy.tokenIn(uri.rawPath ?: return false) ?: return true
        return live(token, clock()) == null
    }

    /** How many streams are registered (tests, diagnostics). */
    val size: Int get() = sessions.size

    @Synchronized
    private fun revoke(token: String) {
        sessions.remove(token)
        tokenByKey.entries.removeAll { it.value == token }
    }

    private fun live(token: String, now: Long): Session? {
        val s = sessions[token] ?: return null
        if (now - s.lastUsedAt > IDLE_TTL_MS || now - s.createdAt > MAX_AGE_MS) {
            revoke(token)
            return null
        }
        return s
    }

    /** The session a request [path] names, or null (no token, unknown, expired). Counts as a use. */
    private fun resolve(path: String): Pair<String, Session>? {
        val token = ArchiveCacheProxy.tokenIn(path) ?: return null
        if (token.length != TOKEN_HEX_CHARS) return null
        val now = clock()
        val session = live(token, now) ?: return null
        session.lastUsedAt = now
        return token to session
    }

    private fun evictOverflow() {
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastUsedAt }?.key ?: return
            revoke(oldest)
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // Serving
    // ---------------------------------------------------------------------------------------------

    private class Incoming(val method: String, val path: String, val range: String?)

    private fun readRequest(socket: Socket): Incoming? {
        val input = socket.getInputStream()
        val header = StringBuilder()
        val one = ByteArray(1)
        while (input.read(one) == 1) {
            header.append(one[0].toInt().toChar())
            if (header.endsWith("\r\n\r\n") || header.length > MAX_HEADER_CHARS) break
        }
        val lines = header.toString().split("\r\n")
        val parts = lines.firstOrNull().orEmpty().split(' ')
        if (parts.size < 2) return null
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        return Incoming(parts[0].uppercase(), parts[1], range)
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = SOCKET_TIMEOUT_MS
                val req = readRequest(s) ?: return@runCatching
                val out = s.getOutputStream()
                val resolved = resolve(req.path)
                if (resolved == null) {
                    log("403: no valid token (from ${s.inetAddress?.hostAddress ?: "?"})")
                    writeEmpty(out, 403, "Forbidden")
                    return@runCatching
                }
                val (token, session) = resolved
                if (req.method == "OPTIONS") {
                    writeEmpty(out, 204, "No Content", cors = true)
                    return@runCatching
                }
                if (req.method != "GET" && req.method != "HEAD") {
                    writeEmpty(out, 405, "Method Not Allowed")
                    return@runCatching
                }
                val route = ArchiveCacheProxy.routeIn(req.path).substringBefore('?')
                when {
                    session.shape == Shape.FILE && route.startsWith("/$MEDIA_ROUTE") ->
                        passthrough(session, session.origin, req, out, session.mime)
                    session.shape == Shape.HLS && route == "/$HLS_ROUTE" ->
                        servePlaylist(token, session, session.origin, req, out)
                    session.shape == Shape.HLS && route == "/$CONTINUOUS_ROUTE" -> serveContinuous(s, token, session, req, out)
                    session.shape == Shape.HLS && route.startsWith("/$DERIVED_ROUTE/") -> {
                        val n = route.removePrefix("/$DERIVED_ROUTE/").substringBefore('.').toIntOrNull()
                        val target = n?.let(session.derived::get)
                        when {
                            target == null -> writeEmpty(out, 404, "Not Found")
                            target.second == HlsPlaylistRewriter.Kind.PLAYLIST -> servePlaylist(token, session, target.first, req, out)
                            else -> passthrough(session, target.first, req, out, mimeForSegment(target.first))
                        }
                    }
                    else -> writeEmpty(out, 404, "Not Found")
                }
            }.onFailure { e -> log("serve failed: ${e.javaClass.simpleName}") }
        }
    }

    /**
     * [url] through the session's gated client, its bytes relayed as they come, with the Range the
     * renderer asked for. [fallbackMime] replaces a generic upstream `Content-Type`.
     */
    private fun passthrough(session: Session, url: String, req: Incoming, out: OutputStream, fallbackMime: String) {
        val head = req.method == "HEAD"
        val request = upstream(session, url)
            // Identity: OkHttp would otherwise ask for gzip and inflate it, and the length the
            // renderer is told would no longer be the length it gets.
            .header("Accept-Encoding", "identity")
            .apply { req.range?.let { header("Range", it) } }
            .apply { if (head) head() }
            .build()
        val response = try {
            session.client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            refusedOrFailed(e, url, out)
            return
        }
        response.use { resp ->
            if (resp.code !in 200..299) {
                log("upstream answered ${resp.code} (${hostOf(url)})")
                writeEmpty(out, resp.code, resp.message.ifEmpty { "Error" })
                return
            }
            val upstreamType = resp.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val type = if (upstreamType.isBlank() || upstreamType.equals("application/octet-stream", true) ||
                upstreamType.equals("binary/octet-stream", true)
            ) fallbackMime else upstreamType
            val sb = StringBuilder()
            sb.append(if (resp.code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: ").append(type).append("\r\n")
            resp.header("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
            resp.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append(CORS_HEADERS)
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (!head) {
                resp.body?.byteStream()?.use { body ->
                    val chunk = ByteArray(COPY_CHUNK)
                    while (true) {
                        val n = body.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                }
            }
            out.flush()
        }
    }

    /**
     * [url] (the origin or a playlist one of its playlists named), fetched through the gated client
     * and rewritten so everything it names comes back here under [token].
     */
    private fun servePlaylist(token: String, session: Session, url: String, req: Incoming, out: OutputStream) {
        val response = try {
            session.client.newCall(upstream(session, url).build()).execute()
        } catch (e: java.io.IOException) {
            refusedOrFailed(e, url, out)
            return
        }
        val (finalUrl, body) = response.use { resp ->
            if (resp.code !in 200..299) {
                log("upstream playlist answered ${resp.code} (${hostOf(url)})")
                writeEmpty(out, resp.code, resp.message.ifEmpty { "Error" })
                return
            }
            val source = resp.body?.source()
            if (source == null || source.request(MAX_PLAYLIST_BYTES + 1L)) {
                log("upstream playlist missing or larger than $MAX_PLAYLIST_BYTES bytes (${hostOf(url)})")
                writeEmpty(out, 502, "Bad Gateway")
                return
            }
            resp.request.url.toString() to source.readUtf8()
        }
        if (!HlsPlaylistRewriter.isPlaylist(body)) {
            log("upstream answered something that isn't an HLS playlist (${hostOf(url)})")
            writeEmpty(out, 502, "Bad Gateway")
            return
        }
        val rewritten = HlsPlaylistRewriter.rewrite(body, finalUrl) { absolute, kind ->
            val n = session.derived.add(absolute, kind)
            "$TOKEN_PREFIX$token/$DERIVED_ROUTE/$n${derivedExtension(absolute, kind)}"
        }
        if (rewritten == null) {
            writeEmpty(out, 502, "Bad Gateway")
            return
        }
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $MIME_HLS\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            CORS_HEADERS +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (req.method != "HEAD") out.write(bytes)
        out.flush()
    }

    /**
     * The HLS origin as ONE continuous MPEG-TS body ([ContinuousTsAssembler]) for a DLNA renderer
     * that lists a TS type and no HLS: `/t/<token>/live.ts?m=<type index>&s=<ms>`. Same token, same
     * gated client and headers as the playlist route; a master playlist follows one variant
     * ([ContinuousTs.parse]); a live playlist starts near its edge, a finite one at `s`. Anything
     * one TS body cannot carry (fMP4, encryption, a separate audio rendition) is a 502 before any
     * header. Ends with the cast ([ContinuousTsStreams.stopAll]), the token or the proxy.
     */
    private fun serveContinuous(socket: Socket, token: String, session: Session, req: Incoming, out: OutputStream) {
        val query = req.path.substringAfter('?', "").split('&')
        fun param(name: String) = query.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
        val mime = ContinuousTs.mimeAt(param("m")?.toIntOrNull())
        if (req.method == "HEAD") {
            out.write(ContinuousTs.responseHead(mime).toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }
        com.arkiv.player.dlna.DlnaLog.lanHit("plugin-proxy", socket.inetAddress?.hostAddress, "GET /$CONTINUOUS_ROUTE", req.range, null)
        var mediaUrl = session.origin
        var followedMaster = false
        val assembler = ContinuousTsAssembler(
            window = window@{
                session.lastUsedAt = clock()
                val (url, text) = fetchPlaylistText(session, mediaUrl) ?: return@window null
                when (val p = ContinuousTs.parse(text, url)) {
                    is ContinuousTs.Playlist.Media -> p
                    is ContinuousTs.Playlist.Master -> {
                        if (followedMaster) return@window null
                        followedMaster = true
                        mediaUrl = p.variantUrl
                        val (vUrl, vText) = fetchPlaylistText(session, mediaUrl) ?: return@window null
                        when (val v = ContinuousTs.parse(vText, vUrl)) {
                            is ContinuousTs.Playlist.Media -> v
                            is ContinuousTs.Playlist.Unusable -> null.also { log("continuous: ${v.reason} (${hostOf(vUrl)})") }
                            is ContinuousTs.Playlist.Master -> null
                        }
                    }
                    is ContinuousTs.Playlist.Unusable -> null.also { log("continuous: ${p.reason} (${hostOf(url)})") }
                }
            },
            openSegment = { url -> openSegment(session, url) },
            fromMs = param("s")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            log = log,
        )
        val outcome = ContinuousTsStreams.serve(socket, out, mime, assembler) { server != null && sessions[token] === session }
        if (outcome.end == ContinuousTsAssembler.End.NO_PLAYLIST) {
            writeEmpty(out, 502, "Bad Gateway")
            return
        }
        log("continuous: ended ${outcome.end} · ${outcome.written} segments, ${outcome.skipped} skipped, ${outcome.bytes / 1024}KB")
    }

    /** [url]'s playlist text and the url it finally came from, through the gated client; null when not served. */
    private fun fetchPlaylistText(session: Session, url: String): Pair<String, String>? = try {
        session.client.newCall(upstream(session, url).build()).execute().use { resp ->
            val source = resp.body?.source()
            when {
                resp.code !in 200..299 -> null.also { log("continuous: playlist answered ${resp.code} (${hostOf(url)})") }
                source == null || source.request(MAX_PLAYLIST_BYTES + 1L) -> null
                else -> resp.request.url.toString() to source.readUtf8()
            }
        }
    } catch (e: java.io.IOException) {
        log("continuous: playlist failed: ${e.javaClass.simpleName} (${hostOf(url)})")
        null
    }

    /** One segment's bytes through the gated client (closing them closes the response), retried once; null when not served. */
    private fun openSegment(session: Session, url: String): java.io.InputStream? {
        repeat(SEGMENT_ATTEMPTS) { attempt ->
            val resp = runCatching {
                session.client.newCall(upstream(session, url).header("Accept-Encoding", "identity").build()).execute()
            }.getOrNull()
            if (resp != null && resp.code in 200..299) {
                val body = resp.body ?: return null.also { resp.close() }
                return object : java.io.FilterInputStream(body.byteStream()) {
                    override fun close() {
                        runCatching { super.close() }
                        resp.close()
                    }
                }
            }
            resp?.close()
            if (attempt < SEGMENT_ATTEMPTS - 1) Thread.sleep(SEGMENT_RETRY_MS)
        }
        return null
    }

    /** A GET of [url] with the stream's headers (never anything the renderer sent but its Range). */
    /**
     * What [url] is, from its first bytes, for a cast decision that has nothing else to go on
     * ([com.arkiv.player.cast.CastStrategy.mimeFromSignature]): its MIME, or null. Fetched like
     * everything here, through the plugin's gated client for [hosts] with the plugin's [headers],
     * one ranged request of [PROBE_BYTES], bounded by [PROBE_TIMEOUT_MS]. Never throws.
     */
    fun probeMime(url: String, headers: Map<String, String>, hosts: EffectiveHosts): String? = runCatching {
        val client = clientFor(hosts).newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> if (name.lowercase() !in HOP_BY_HOP) header(name, value) }
        }
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) return@runCatching null
            val body = resp.body?.byteStream() ?: return@runCatching null
            val head = ByteArray(PROBE_BYTES)
            var n = 0
            while (n < head.size) {
                val r = body.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            com.arkiv.player.cast.CastStrategy.mimeFromSignature(head.copyOf(n))
        }
    }.onFailure { log("probe failed (${hostOf(url)}): ${it.javaClass.simpleName}") }.getOrNull()

    private fun upstream(session: Session, url: String): Request.Builder =
        Request.Builder().url(url).apply {
            session.headers.forEach { (name, value) ->
                if (name.lowercase() !in HOP_BY_HOP) header(name, value)
            }
        }

    private fun refusedOrFailed(e: java.io.IOException, url: String, out: OutputStream) {
        val refused = e is HostNotAllowedException || e is PrivateAddressException ||
            (e is PluginFetchException && e.code == "host_not_allowed")
        if (refused) {
            log("refused by the plugin's host rules (${hostOf(url)})")
            writeEmpty(out, 403, "Forbidden")
        } else {
            log("upstream failed: ${e.javaClass.simpleName} (${hostOf(url)})")
            writeEmpty(out, 502, "Bad Gateway")
        }
    }

    private fun writeEmpty(out: OutputStream, code: Int, message: String, cors: Boolean = false) {
        val text = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\n" + (if (cors) CORS_HEADERS else "") + "Connection: close\r\n\r\n"
        runCatching {
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    companion object {
        private const val TAG = "PluginCastProxy"
        private const val TOKEN_PREFIX = "/t/"
        private const val TOKEN_BYTES = 16
        const val TOKEN_HEX_CHARS = TOKEN_BYTES * 2

        /** The origin's route: `/t/<token>/media.<ext>` (an extension, so a renderer that sniffs names gets it right). */
        private const val MEDIA_ROUTE = "media"

        /** An HLS origin's route: `/t/<token>/index.m3u8`. */
        private const val HLS_ROUTE = "index.m3u8"

        /** What an HLS origin's playlists named: `/t/<token>/r/<n>.<ext>`. */
        private const val DERIVED_ROUTE = "r"

        /** An HLS origin as one continuous MPEG-TS body: `/t/<token>/live.ts`. */
        private const val CONTINUOUS_ROUTE = "live.ts"

        /** A segment at the live edge may not be published yet: asked twice, this far apart. */
        private const val SEGMENT_ATTEMPTS = 2
        private const val SEGMENT_RETRY_MS = 800L

        /**
         * [hlsUrl] (this proxy's `/t/<token>/index.m3u8`, on loopback or the LAN) as the same
         * stream's continuous TS body, labelled [mime] and starting at [fromMs] when it is finite;
         * null for any other url.
         */
        fun continuousUrlOf(hlsUrl: String, mime: String, fromMs: Long): String? {
            val path = hlsUrl.substringBefore('?')
            if (!path.endsWith("/$HLS_ROUTE") || !path.contains(TOKEN_PREFIX)) return null
            return path.removeSuffix(HLS_ROUTE) + "$CONTINUOUS_ROUTE?m=${ContinuousTs.indexOf(mime)}&s=${fromMs.coerceAtLeast(0L)}"
        }

        const val MIME_HLS = "application/vnd.apple.mpegurl"

        /** A live channel names a few segments every few seconds: a day of them fits. */
        const val MAX_DERIVED = 50_000

        /** No playlist is anywhere near this; a body that is, isn't one. */
        private const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024

        const val IDLE_TTL_MS = 4L * 60 * 60 * 1000
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_SESSIONS = 8

        private const val MAX_HEADER_CHARS = 8192
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val COPY_CHUNK = 64 * 1024

        /** Bytes a format probe reads: enough for an m2ts signature or a manifest's first line. */
        const val PROBE_BYTES = 1024

        /** A probe delays the start of playback, so it gets little time. */
        const val PROBE_TIMEOUT_MS = 2_000L

        /**
         * The Default Media Receiver plays HLS (and checks some progressive loads) through
         * Chromium's fetch, which needs CORS; a DLNA renderer ignores these.
         */
        private const val CORS_HEADERS =
            "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n"

        /** Never forwarded from a plugin's header set: they belong to one connection. */
        private val HOP_BY_HOP = setOf("connection", "host", "content-length", "transfer-encoding", "range", "accept-encoding")

        /** How a stream the receiver is told is [mime] gets served. */
        fun shapeFor(mime: String): Shape = if (mime.equals(MIME_HLS, ignoreCase = true)) Shape.HLS else Shape.FILE

        private fun routeFor(shape: Shape, mime: String): String = when (shape) {
            Shape.FILE -> "$MEDIA_ROUTE.${extensionFor(mime)}"
            Shape.HLS -> HLS_ROUTE
        }

        private val SIMPLE_EXTENSION = Regex("[a-z0-9]{1,5}")

        /** `.m3u8` for a playlist, the url's own extension for media when it has a plain one, else none. */
        private fun derivedExtension(url: String, kind: HlsPlaylistRewriter.Kind): String {
            if (kind == HlsPlaylistRewriter.Kind.PLAYLIST) return ".m3u8"
            val ext = url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return if (SIMPLE_EXTENSION.matches(ext)) ".$ext" else ""
        }

        /** A segment's type when its server only says `application/octet-stream`. */
        private fun mimeForSegment(url: String): String =
            when (url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('.')?.lowercase()) {
                "ts" -> "video/mp2t"
                "m4s", "mp4", "m4v", "cmfv" -> "video/mp4"
                "aac" -> "audio/aac"
                "m4a", "cmfa" -> "audio/mp4"
                "vtt", "webvtt" -> "text/vtt"
                else -> "application/octet-stream"
            }

        private fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "video/webm" -> "webm"
            "video/x-matroska", "video/matroska" -> "mkv"
            "video/mp2t" -> "ts"
            "video/x-msvideo" -> "avi"
            "video/quicktime" -> "mov"
            else -> "mp4"
        }

        /** The host of [url] for a log line: never its path, query or credentials. */
        internal fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: "?"
    }
}
