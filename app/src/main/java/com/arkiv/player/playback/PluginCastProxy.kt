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
    /** How the origin is served: as it is ([FILE], byte ranges passed through). */
    enum class Shape { FILE }

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
                val (_, session) = resolved
                if (req.method == "OPTIONS") {
                    writeEmpty(out, 204, "No Content", cors = true)
                    return@runCatching
                }
                if (req.method != "GET" && req.method != "HEAD") {
                    writeEmpty(out, 405, "Method Not Allowed")
                    return@runCatching
                }
                val route = ArchiveCacheProxy.routeIn(req.path).substringBefore('?')
                if (!route.startsWith("/$MEDIA_ROUTE")) {
                    writeEmpty(out, 404, "Not Found")
                    return@runCatching
                }
                passthrough(session, session.origin, req, out, session.mime)
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

    /** A GET of [url] with the stream's headers (never anything the renderer sent but its Range). */
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

        const val IDLE_TTL_MS = 4L * 60 * 60 * 1000
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_SESSIONS = 8

        private const val MAX_HEADER_CHARS = 8192
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val COPY_CHUNK = 64 * 1024

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
        fun shapeFor(@Suppress("UNUSED_PARAMETER") mime: String): Shape = Shape.FILE

        private fun routeFor(shape: Shape, mime: String): String = when (shape) {
            Shape.FILE -> "$MEDIA_ROUTE.${extensionFor(mime)}"
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
