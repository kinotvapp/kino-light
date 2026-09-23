package com.arkiv.player.playback

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Serves every file in one directory, over the LAN, at the same time.
 *
 * [LocalFileServer] cannot do this: it holds a single file and restarts its socket whenever that
 * file changes, which is right when one title is being cast and wrong here. A title cast as a
 * QUEUE of chunks needs several of them reachable at once -- the receiver holds a playlist of
 * URLs and fetches whichever it is about to play, including ones queued minutes ago.
 *
 * Range is supported because the receiver asks for one: a chunk is a complete mp4 and it seeks
 * inside it normally.
 *
 * Only files directly inside [folder] are served, and the name is taken as a bare filename with
 * no path separators -- a request is a URL from the network, and a media server that resolves
 * `../` reaches the whole app's storage.
 */
class ChunkServer(private val folder: File, private val lanIp: () -> String?) {

    @Volatile private var server: ServerSocket? = null

    /** The LAN url for [file], starting the server if needed. Null without an ip. */
    @Synchronized
    fun serve(file: File): String? {
        if (!file.exists()) return null
        if (server == null || server?.isClosed == true) start()
        val ip = lanIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/${file.name}"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun start() {
        // No bind address: every interface, the same convention LiveHlsProxy documents and
        // ArchiveCacheProxy relies on. The receiver reaches this over the LAN.
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(socket) }.onFailure { Log.w(TAG, "serve: $it") } }
                    .apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "chunk server up on port ${s.localPort}")
    }

    private fun handle(socket: Socket): Unit = socket.use { sock ->
        val input = sock.getInputStream()
        val header = StringBuilder()
        val one = ByteArray(1)
        while (input.read(one) == 1) {
            header.append(one[0].toInt().toChar())
            if (header.endsWith("\r\n\r\n") || header.length > 8192) break
        }
        val lines = header.toString().split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val method = requestLine.substringBefore(' ')
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val name = runCatching { URLDecoder.decode(path.trimStart('/'), "UTF-8") }
            .getOrDefault("")
            .substringAfterLast('/')   // a request is network input: no traversing out of here
        val file = File(folder, name)
        val out = sock.getOutputStream()

        if (name.isBlank() || !file.exists() || !file.isFile) {
            Log.w(TAG, "404 $name")
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val total = file.length()
        val rangeValue = lines.firstOrNull { it.startsWith("Range:", true) }
            ?.substringAfter(':')?.trim()
        val r = RangeHeader.parse(rangeValue)
        val start = (r?.start ?: 0L).coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val end = (r?.end ?: (total - 1)).coerceAtMost(total - 1)
        val length = (end - start + 1).coerceAtLeast(0L)

        val status = if (r != null) "206 Partial Content" else "200 OK"
        val contentRange = if (r != null) "Content-Range: bytes $start-$end/$total\r\n" else ""
        out.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    contentRange +
                    "Content-Length: $length\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        if (method == "HEAD") { out.flush(); return }

        var sent = 0L
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            while (sent < length) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                sent += n
            }
        }
        out.flush()
        Log.i(TAG, "-> $name $status ${sent}B of $total")
    }

    private companion object { const val TAG = "ArkivChunkServer" }
}
