package com.arkiv.player.dlna

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy: serves the TV plain http for what it downloads over https from the real
 * source (Magis or Caracol today; originally archive.org, hence `DlnaController.setUrlAndPlay`'s
 * `archiveUrl` parameter). Many DLNA renderers (LG webOS, etc.) don't support HTTPS or don't
 * follow redirects, so the phone acts as a middleman and adds the DLNA headers the TV needs.
 */
class DlnaProxyServer {

    @Volatile
    private var target: String = ""
    private var serverSocket: ServerSocket? = null
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun handle(socket: Socket) {
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

                val reqBuilder = Request.Builder().url(target).header("User-Agent", "Arkiv")
                if (range != null) reqBuilder.header("Range", range)
                if (method.equals("HEAD", true)) reqBuilder.head()

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val out = s.getOutputStream()
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
                        resp.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // the client closed the connection / range change: normal in streaming
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
