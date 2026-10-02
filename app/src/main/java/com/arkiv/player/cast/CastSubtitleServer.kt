package com.arkiv.player.cast

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the title's external subtitles to a TV on the LAN: WebVTT for a Chromecast (whole file, or
 * one HLS segment's window of it), SRT for a DLNA renderer. Converted on the phone, see
 * [CastSubtitleText].
 *
 * Only what the phone was OFFERED is served ([offer]: the subtitles the source gave for the title on
 * screen), fetched by the phone's own client for that source -- the same headers and the same
 * plugin host gate its player uses. A request names a subtitle by its index, never by a URL, and
 * carries a random token: it is not a relay, and only who was handed a URL can read it. A new title
 * gets a new token, so the previous title's URLs stop answering.
 *
 * Its own small socket rather than a route on one of the video servers: the same URL has to serve a
 * Chromecast on any route (remux HLS, an mp4 through a proxy, a plugin's direct HLS) and a DLNA
 * renderer, whose video comes from yet other servers. The fetched file is cached for as long as the
 * offer stands (re-casts and reconnects of the same title reuse it).
 */
class CastSubtitleServer(
    private val lanIp: () -> String?,
    private val log: (String) -> Unit = {},
) {

    /** What is served now: the title's subtitles, how to fetch them, and the cues already fetched. */
    private class Offer(
        val episodeId: String,
        val sources: List<CastSubtitleSource>,
        @Volatile var fetch: (String) -> ByteArray?,
        val token: String,
    ) {
        val cues = java.util.concurrent.ConcurrentHashMap<Int, List<SubtitleCue>>()
    }

    @Volatile private var current: Offer? = null
    @Volatile private var server: ServerSocket? = null

    /**
     * Makes [sources] the subtitles served, fetched with [fetch]. The same title with the same list
     * keeps its token and its cache (a re-cast of it reuses both); anything else gets a new token.
     * An empty list stops serving.
     */
    @Synchronized
    fun offer(episodeId: String, sources: List<CastSubtitleSource>, fetch: (String) -> ByteArray?) {
        val now = current
        if (now != null && now.episodeId == episodeId && now.sources == sources) {
            now.fetch = fetch
            return
        }
        // The same title's list only grew (an online subtitle the person added): the TV's current
        // URLs keep answering -- same token, same cached cues -- and the new one joins them.
        if (now != null && extendsOffer(now.episodeId, now.sources, episodeId, sources)) {
            current = Offer(episodeId, sources, fetch, now.token).also { it.cues.putAll(now.cues) }
            log("offering ${sources.size} subtitle(s) for $episodeId (${sources.size - now.sources.size} added)")
            return
        }
        current = if (sources.isEmpty()) null else Offer(episodeId, sources, fetch, newToken())
        if (sources.isNotEmpty()) log("offering ${sources.size} subtitle(s) for $episodeId")
    }

    /** The title whose subtitles are offered, or null. */
    val episodeId: String? get() = current?.episodeId

    /** The subtitles offered for [episodeId]; empty for any other title. */
    fun sourcesFor(episodeId: String): List<CastSubtitleSource> =
        current?.takeIf { it.episodeId == episodeId }?.sources.orEmpty()

    /**
     * `http://<lan>:<port>/s/<token>/<offsetMs>` for [episodeId]'s subtitles on a timeline that starts
     * [offsetMs] into the title, or null (another title is offered, or there is no LAN address).
     */
    @Synchronized
    fun baseUrl(episodeId: String, offsetMs: Long): String? {
        val offer = current?.takeIf { it.episodeId == episodeId } ?: return null
        val ip = lanIp() ?: return null
        val socket = server ?: start()
        return CastSubtitleRoutes.base(ip, socket.localPort, offer.token, offsetMs)
    }

    /** Is [url] one of our subtitle URLs whose token no longer answers? False for anything else. */
    fun revoked(url: String): Boolean {
        val route = CastSubtitleRoutes.parse(runCatching { java.net.URI(url).path }.getOrNull() ?: return false) ?: return false
        return !CastSubtitleRoutes.authorized(route, current?.token)
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
        }.apply { isDaemon = true; name = "CastSubtitleServer" }.start()
        return s
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 15_000
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
            out.write(head("204 No Content", "text/plain", 0, "Access-Control-Allow-Methods: GET, HEAD\r\nAccess-Control-Allow-Headers: *\r\n"))
            out.flush()
            return
        }
        val route = CastSubtitleRoutes.parse(path)
        val offer = current
        if (route == null || offer == null || !CastSubtitleRoutes.authorized(route, offer.token) || route.index !in offer.sources.indices) {
            CastDiag.w("subtitle request refused: ${com.arkiv.player.dlna.DlnaXml.safeUrl(path)}")
            out.write(head("404 Not Found", "text/plain", 0))
            out.flush()
            return
        }
        val cues = cuesOf(offer, route.index)
        if (cues == null) {
            out.write(head("502 Bad Gateway", "text/plain", 0))
            out.flush()
            return
        }
        val (type, body) = CastSubtitleRoutes.render(route, cues)
        out.write(head("200 OK", type, body.size.toLong(), "Cache-Control: no-cache\r\n"))
        if (method != "HEAD") out.write(body)
        out.flush()
        if (route !is CastSubtitleRoutes.Route.Segment) {
            CastDiag.i("TV $method subtitle ${route.index} (${offer.sources[route.index].lang}) as ${route.ext} · ${cues.size} cues")
        }
    }

    /** Subtitle [index]'s cues, fetched and parsed once per offer; null when it could not be read. */
    private fun cuesOf(offer: Offer, index: Int): List<SubtitleCue>? {
        offer.cues[index]?.let { return it }
        synchronized(offer) {
            offer.cues[index]?.let { return it }
            val source = offer.sources[index]
            val bytes = runCatching { offer.fetch(source.url) }
                .onFailure { log("subtitle $index fetch failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > CastSubtitleText.MAX_BYTES) {
                CastDiag.w("subtitle $index (${source.lang}) could not be fetched (${bytes?.size ?: -1} bytes)")
                return null
            }
            val cues = CastSubtitleText.parse(CastSubtitleText.decode(bytes))
            log("subtitle $index (${source.lang}) fetched: ${bytes.size} bytes, ${cues.size} cues")
            offer.cues[index] = cues
            return cues
        }
    }

    private fun head(status: String, type: String, length: Long, extra: String = ""): ByteArray =
        ("HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: $length\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            extra +
            "Connection: close\r\n\r\n").toByteArray()

    private fun newToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }
    }
}

/** The pure half of [CastSubtitleServer]: URL shapes, the token check and what each route answers. */
object CastSubtitleRoutes {

    sealed interface Route {
        val token: String
        val offsetMs: Long
        val index: Int
        val ext: String

        /** `/s/<token>/<offsetMs>/<index>.vtt|.srt`: the whole file. */
        data class Whole(override val token: String, override val offsetMs: Long, override val index: Int, override val ext: String) : Route

        /** `/s/<token>/<offsetMs>/<index>/<fromMs>-<toMs>.vtt`: one HLS segment's window, in receiver time. */
        data class Segment(
            override val token: String,
            override val offsetMs: Long,
            override val index: Int,
            val fromMs: Long,
            val toMs: Long,
        ) : Route {
            override val ext: String get() = "vtt"
        }
    }

    fun base(ip: String, port: Int, token: String, offsetMs: Long): String =
        "http://$ip:$port/s/$token/${offsetMs.coerceAtLeast(0L)}"

    /** The whole file of subtitle [index] under [base], as WebVTT (`vtt`) or SRT (`srt`). */
    fun fileUrl(base: String, index: Int, ext: String): String = "$base/$index.$ext"

    /** The prefix an HLS subtitle playlist appends `<fromMs>-<toMs>.vtt` to. */
    fun segmentBase(base: String, index: Int): String = "$base/$index/"

    private val TOKEN = Regex("^[0-9a-f]{32}$")

    fun parse(path: String): Route? {
        val parts = path.trim('/').split('/')
        if (parts.size !in 4..5 || parts[0] != "s" || !TOKEN.matches(parts[1])) return null
        val offset = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        if (parts.size == 4) {
            val m = Regex("^(\\d{1,3})\\.(vtt|srt)$").find(parts[3]) ?: return null
            return Route.Whole(parts[1], offset, m.groupValues[1].toInt(), m.groupValues[2])
        }
        val index = parts[3].takeIf { it.length in 1..3 }?.toIntOrNull() ?: return null
        val m = Regex("^(\\d{1,10})-(\\d{1,10})\\.vtt$").find(parts[4]) ?: return null
        val from = m.groupValues[1].toLong()
        val to = m.groupValues[2].toLong()
        if (to <= from) return null
        return Route.Segment(parts[1], offset, index, from, to)
    }

    /** [route] carries the token being served now. Constant-time, and false with nothing served. */
    fun authorized(route: Route, token: String?): Boolean =
        token != null && java.security.MessageDigest.isEqual(route.token.toByteArray(), token.toByteArray())

    /** Content type and body for [route] over the title's [cues]. */
    fun render(route: Route, cues: List<SubtitleCue>): Pair<String, ByteArray> {
        val shifted = CastSubtitleText.shift(cues, route.offsetMs)
        return when (route) {
            is Route.Segment ->
                "text/vtt; charset=utf-8" to CastSubtitleText.toVtt(CastSubtitleText.window(shifted, route.fromMs, route.toMs)).toByteArray()
            is Route.Whole -> if (route.ext == "srt") {
                "text/srt; charset=utf-8" to CastSubtitleText.utf8SrtBytes(CastSubtitleText.toSrt(shifted))
            } else {
                "text/vtt; charset=utf-8" to CastSubtitleText.toVtt(shifted).toByteArray()
            }
        }
    }
}

/** Is [sources] for [episodeId] the offer [previousEpisode]/[previous] with more subtitles appended? */
internal fun extendsOffer(
    previousEpisode: String,
    previous: List<CastSubtitleSource>,
    episodeId: String,
    sources: List<CastSubtitleSource>,
): Boolean = previousEpisode == episodeId && previous.isNotEmpty() && sources.size > previous.size &&
    sources.subList(0, previous.size) == previous
