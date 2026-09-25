package com.arkiv.player.dlna

import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy: serves the TV plain http for what it downloads over https from the real
 * source (Magis or Caracol today; originally archive.org, hence `DlnaController.setUrlAndPlay`'s
 * `archiveUrl` parameter). Many DLNA renderers (LG webOS, etc.) don't support HTTPS or don't
 * follow redirects, so the phone acts as a middleman and adds the DLNA headers the TV needs.
 *
 * Instrumented (see [DlnaLog]): who asked for what, what the source answered, and how much reached the TV
 * before it closed the connection. That last number is the tell for a rejected stream: a renderer that
 * opens the connection and drops it after a few KB is refusing the container or codec.
 */
class DlnaProxyServer {

    @Volatile
    private var target: String = ""
    private var serverSocket: ServerSocket? = null
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // No connection reuse toward the source. The internal Magis proxy (ArchiveCacheProxy) answers a HEAD
        // as if it were a GET and keeps streaming the body on that socket; OkHttp, which correctly considers a
        // HEAD finished, put the connection back in its pool, and the NEXT request (the TV's real GET) read
        // the leftover video bytes as its status line: "Unexpected status line: G@...". Found on a real LG TV.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    private val dlnaContentFeatures =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    fun setTarget(url: String) { target = url }

    /** Starts the server if it isn't running yet and returns the port. */
    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) return existing.localPort
        val ss = ServerSocket(0) // free port
        serverSocket = ss
        DlnaLog.i("proxy: listening on port ${ss.localPort}")
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun handle(socket: Socket) {
        val startedAt = SystemClock.elapsedRealtime()
        var sent = 0L
        var isLan = false
        try {
            socket.use { s ->
                val input = s.getInputStream()
                // Read request line + headers (no body: GET/HEAD).
                val header = StringBuilder()
                val buf = ByteArray(1)
                while (input.read(buf) == 1) {
                    header.append(buf[0].toInt().toChar())
                    if (header.endsWith("\r\n\r\n")) break
                    if (header.length > 8192) break
                }
                val lines = header.toString().split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val method = requestLine.substringBefore(' ').ifEmpty { "GET" }
                val range = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()
                val userAgent = lines.firstOrNull { it.startsWith("User-Agent:", true) }
                    ?.substringAfter(':')?.trim()
                // The line that splits a failed cast in two: the TV never shows up here (reachability, or it
                // never tried the URL) or it shows up and then gives up (what we serve is what it dislikes).
                isLan = DlnaLog.lanHit("proxy", s.inetAddress?.hostAddress, requestLine, range, userAgent)

                val source = target
                val isHead = method.equals("HEAD", true)
                // The TV's HEAD is answered from a 1-byte ranged GET, never forwarded as an upstream HEAD: the
                // internal proxy treats a HEAD like a GET and starts streaming the whole file for it, and the
                // CDN may not support HEAD at all. The total size comes back in Content-Range. `Connection:
                // close` so no socket is ever reused (see the client's pool above).
                val reqBuilder = Request.Builder().url(source).header("User-Agent", "Arkiv").header("Connection", "close")
                if (isHead) reqBuilder.header("Range", "bytes=0-0") else if (range != null) reqBuilder.header("Range", range)

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val out = s.getOutputStream()
                    val upstreamType = resp.header("Content-Type")
                    DlnaLog.i(
                        "proxy: source answered ${resp.code} type=$upstreamType len=${resp.header("Content-Length")} " +
                            "range=${resp.header("Content-Range")} · ${DlnaXml.safeUrl(source)}",
                    )
                    // A source that refuses (an expired CDN token, a missing auth header: 401/403/404) used to be
                    // relayed as "200 OK" + video/mp4 + an empty or HTML body. The TV then believed it had a
                    // video and failed with no explanation. Pass the error on, so the renderer reports it too.
                    if (!resp.isSuccessful) {
                        DlnaLog.w(
                            "proxy: the source REFUSED (${resp.code} '${resp.message}'): answering the TV with the same " +
                                "error. If this is a 401/403 the source needs headers this proxy doesn't send.",
                        )
                        out.write(
                            "HTTP/1.1 ${resp.code} ${resp.message.ifEmpty { "Error" }}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        out.flush()
                        return@use
                    }
                    if (upstreamType != null && !upstreamType.contains("mp4", ignoreCase = true)) {
                        DlnaLog.w(
                            "proxy: declaring video/mp4 to the TV but the source is '$upstreamType'. A renderer that " +
                                "trusts the declared type will choke on a different container (e.g. MPEG-TS).",
                        )
                    }
                    if (isHead) {
                        // Size of the whole file: `Content-Range: bytes 0-0/TOTAL` from the 1-byte GET, or the
                        // Content-Length if the source ignored the Range and answered 200.
                        val total = resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                            ?: resp.header("Content-Length")?.toLongOrNull()
                        DlnaLog.i("proxy: answering the TV's HEAD from a ranged GET · total=${total ?: "unknown"}")
                        val head = StringBuilder("HTTP/1.1 200 OK\r\n")
                        head.append("Content-Type: video/mp4\r\n")
                        total?.let { head.append("Content-Length: ").append(it).append("\r\n") }
                        head.append("Accept-Ranges: bytes\r\n")
                        head.append("contentFeatures.dlna.org: ").append(dlnaContentFeatures).append("\r\n")
                        head.append("transferMode.dlna.org: Streaming\r\n")
                        head.append("Connection: close\r\n\r\n")
                        out.write(head.toString().toByteArray(Charsets.US_ASCII))
                        out.flush()
                        return@use
                    }
                    val statusLine = if (resp.code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                    val sb = StringBuilder()
                    sb.append(statusLine).append("\r\n")
                    sb.append("Content-Type: video/mp4\r\n")
                    resp.header("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
                    resp.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
                    sb.append("Accept-Ranges: bytes\r\n")
                    sb.append("contentFeatures.dlna.org: ").append(dlnaContentFeatures).append("\r\n")
                    sb.append("transferMode.dlna.org: Streaming\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray(Charsets.US_ASCII))
                    if (!method.equals("HEAD", true)) {
                        // Copied by hand (not copyTo) to know how many bytes reached the TV when it hangs up.
                        resp.body?.byteStream()?.use { body ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val n = body.read(chunk)
                                if (n < 0) break
                                out.write(chunk, 0, n)
                                sent += n
                                if (isLan) DlnaLog.lanBytes.addAndGet(n.toLong())
                            }
                        }
                    }
                    out.flush()
                }
            }
            DlnaLog.i("proxy: request finished · $sent bytes to the TV in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (e: Exception) {
            val closedByTv = e is SocketException ||
                e.message?.contains("Broken pipe", ignoreCase = true) == true ||
                e.message?.contains("reset", ignoreCase = true) == true
            if (closedByTv) {
                // Normal on a seek or Stop. But a hang-up within seconds of Play, after only a few KB, is a
                // renderer refusing the stream, which is exactly what the diagnosis is looking for.
                DlnaLog.i("proxy: TV closed the connection after $sent bytes (${e.javaClass.simpleName}: ${e.message}) in ${SystemClock.elapsedRealtime() - startedAt}ms")
            } else {
                DlnaLog.w("proxy: request FAILED after $sent bytes: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    @Synchronized
    fun stop() {
        DlnaLog.i("proxy: stopping")
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
