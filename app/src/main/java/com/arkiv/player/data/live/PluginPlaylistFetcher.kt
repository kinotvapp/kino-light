package com.arkiv.player.data.live

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.sink

/** Downloads a declared playlist or guide, refusing (IOException) anything over [maxBytes]. */
fun interface LivePlaylistFetcher {
    suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray

    /**
     * Writes the download to [into] (a temp file the caller renames). On any failure [into] is
     * deleted. This default holds the body in memory; [PluginPlaylistFetcher] streams it instead.
     */
    suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        try {
            val bytes = fetch(url, headers, maxBytes)
            withContext(Dispatchers.IO) { into.writeBytes(bytes) }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }
}

/**
 * Downloads a declared playlist or guide for the app to parse. [client] MUST be
 * `PluginStreamHttp.client(base, plugin.hosts)`: the strict gate (declared hosts and typed servers,
 * every redirect hop, no LAN by DNS), never the `liveStreamHosts: "any"` one.
 *
 * - The whole call, body included, has [timeoutMs]: a server trickling bytes can't hold a
 *   playlist (and every screen waiting on it) forever.
 * - Cancelling the caller cancels the socket at once, not when the blocking read ends.
 * - The body is streamed to disk while its bytes are counted: nothing over [maxBytes], and never
 *   the whole list in memory.
 * - A body shorter than its Content-Length, or a chunked body without its last chunk, fails
 *   (OkHttp's own framing checks, plus the length check here), so a cut download never replaces a
 *   good saved copy. Residual case: a body with neither length nor chunking ends when the server
 *   closes the connection, so a cut there is indistinguishable from the end; the parsers are
 *   tolerant of a truncated list or guide.
 */
class PluginPlaylistFetcher(client: OkHttpClient, timeoutMs: Long = DEFAULT_TIMEOUT_MS) : LivePlaylistFetcher {
    private val client = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray {
        val buffer = Buffer()
        download(url, headers, maxBytes, buffer)
        return buffer.readByteArray()
    }

    override suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        try {
            withContext(Dispatchers.IO) { into.sink().buffer() }.use { sink -> download(url, headers, maxBytes, sink) }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    private suspend fun download(url: String, headers: Map<String, String>, maxBytes: Long, sink: BufferedSink) = coroutineScope {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(request)
        // The blocking read below can't see cancellation: this watcher cancels the socket for it.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("playlist answered ${r.code}")
                    val body = r.body ?: throw IOException("empty playlist response")
                    val declared = body.contentLength()
                    if (declared > maxBytes) throw IOException("playlist over ${maxBytes / (1024 * 1024)} MB")
                    val source = body.source()
                    var total = 0L
                    while (true) {
                        val n = source.read(sink.buffer, CHUNK_BYTES)
                        if (n == -1L) break
                        total += n
                        if (total > maxBytes) throw IOException("playlist over ${maxBytes / (1024 * 1024)} MB")
                        sink.emitCompleteSegments()
                    }
                    if (declared >= 0 && total != declared) throw IOException("playlist cut at $total of $declared bytes")
                    sink.flush()
                }
            }
        } catch (e: IOException) {
            // The watcher closed the socket because we were cancelled: that is a cancellation, not a failure.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
        private const val CHUNK_BYTES = 64 * 1024L
    }
}
