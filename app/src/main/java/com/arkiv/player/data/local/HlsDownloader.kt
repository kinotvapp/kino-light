package com.arkiv.player.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Saves an HLS VOD stream as ONE local file the app's player already opens offline: MPEG-TS
 * segments concatenated into `<base>.ts`, or an fMP4 stream's init section + a `sidx` + its
 * fragments into `<base>.mp4`. See the design notes in [HlsPlaylistParser], [HlsVariantPicker],
 * [HlsResumeState].
 *
 * Every request (playlists, key, init, segments) goes through [client] -- the plugin's host-gated
 * client, the same gate the player applies to that stream, redirects included -- with the Stream's
 * [headers] on it, as the player sends them.
 *
 * Files, all named `<base>.…` so "Quitar"'s prefix sweep reaches every one: `<base>.hls.part` (the
 * output so far), `<base>.hls.state` ([HlsResumeState]), `<base>.hls.seg<N>` (a batch's raw
 * segments, deleted as soon as they are appended, and on cancellation).
 *
 * Fetches [concurrency] segments at a time and appends them in order; each gets [attempts] quick
 * tries. A failure that survives them surfaces as is ([DownloadRetryPolicy] classifies it: network
 * trouble retries later through the worker, resuming at the first missing segment). A stream that
 * can never be saved throws [HlsRefusedException].
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val checkEveryBytes: Long = FreeSpacePolicy.CHECK_EVERY_BYTES,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 1_000,
) {

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        targetDir: File,
        /** Every file's name prefix: the sanitized episode id. */
        baseName: String,
        resumeKey: String,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching { run(url, headers, targetDir, baseName, resumeKey, onProgress) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private suspend fun run(
        url: String,
        headers: Map<String, String>,
        dir: File,
        base: String,
        resumeKey: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        dir.mkdirs()
        freeSpace(dir).let { if (FreeSpacePolicy.isExhausted(it)) throw InsufficientSpaceException(it) }

        // Master → the chosen variant's media playlist (one level: a variant is always a media playlist).
        var bandwidth = 0L
        val media = when (val first = fetchPlaylist(url, headers)) {
            is HlsPlaylist.Media -> first.playlist
            is HlsPlaylist.Master -> {
                val variant = HlsVariantPicker.pick(first.playlist)
                bandwidth = variant.bandwidth
                when (val second = fetchPlaylist(variant.uri, headers)) {
                    is HlsPlaylist.Media -> second.playlist
                    is HlsPlaylist.Master -> throw HlsRefusedException("nested master playlist")
                }
            }
        }
        HlsDownloadPlan.check(media)
        val segments = media.segments
        val fmp4 = media.init != null

        val part = File(dir, "$base.hls.part")
        val stateFile = File(dir, "$base.hls.state")
        val target = File(dir, "$base.${if (fmp4) "mp4" else "ts"}")
        val fingerprint = HlsResumeState.fingerprint(media)

        // Disk guard: the declared bandwidth over the whole duration, when the master said it.
        val estimate = if (bandwidth > 0) (bandwidth / 8.0 * media.totalDurationSec).toLong() else 0L
        freeSpace(dir).let { if (!FreeSpacePolicy.fits(it, estimate)) throw InsufficientSpaceException(it) }

        val saved = runCatching { stateFile.readText() }.getOrNull()?.let(HlsResumeState::fromJson)
        var state = HlsResumeState.resumable(saved, resumeKey, fingerprint, if (part.exists()) part.length() else -1L, segments.size)
        if (state == null) {
            part.delete()
            val header = if (fmp4) fetchBytes(media.init!!.uri, media.init!!.range, headers) + ByteArray(Mp4Sidx.size(segments.size)) else ByteArray(0)
            FileOutputStream(part).use { it.write(header) }
            state = HlsResumeState(resumeKey, fingerprint, 0, header.size.toLong(), emptyList(), header.size.toLong())
            stateFile.writeText(state.toJson())
        } else {
            RandomAccessFile(part, "rw").use { it.setLength(state.partBytes) }
        }

        val keys = ConcurrentHashMap<String, ByteArray>()
        var sinceDiskCheck = 0L
        var done = state.segmentsDone
        report(onProgress, state, segments.size)
        try {
            while (done < segments.size) {
                coroutineContext.ensureActive()
                val batch = (done until minOf(done + concurrency, segments.size)).toList()
                val raw = coroutineScope {
                    batch.map { i -> async { fetchToFile(segments[i], File(dir, "$base.hls.seg$i"), headers) } }.awaitAll()
                }
                val sizes = state!!.sizes.toMutableList()
                FileOutputStream(part, true).use { out ->
                    batch.forEachIndexed { n, i ->
                        val written = append(segments[i], raw[n], out, headers, keys, fmp4, first = i == 0)
                        raw[n].delete()
                        sizes += written
                        sinceDiskCheck += written
                    }
                    out.flush()
                    out.fd.sync()
                }
                done = batch.last() + 1
                state = state.copy(segmentsDone = done, partBytes = state.headerBytes + sizes.sum(), sizes = sizes)
                stateFile.writeText(state.toJson())
                report(onProgress, state, segments.size)
                if (sinceDiskCheck >= checkEveryBytes) {
                    sinceDiskCheck = 0L
                    freeSpace(dir).let { if (FreeSpacePolicy.isExhausted(it)) throw InsufficientSpaceException(it) }
                }
            }
        } finally {
            // A cancelled or failed batch leaves raw segments behind: never resumed from (a batch is
            // re-fetched whole), so they go now. The part and its state stay for the next attempt.
            dir.listFiles { f -> f.name.startsWith("$base.hls.seg") }?.forEach { runCatching { it.delete() } }
        }

        if (fmp4) {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(state!!.headerBytes - Mp4Sidx.size(segments.size))
                raf.write(Mp4Sidx.build(state.sizes, segments.map { it.durationSec }))
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("no se pudo renombrar el parcial")
        runCatching { stateFile.delete() }
        return target
    }

    private fun report(onProgress: (Long, Long) -> Unit, state: HlsResumeState, total: Int) {
        val bytes = state.partBytes
        // Estimated from the average segment so far: the percentage is then exactly segments done / total.
        val estimate = when {
            state.segmentsDone >= total -> bytes
            state.segmentsDone > 0 -> (bytes.toDouble() / state.segmentsDone * total).toLong()
            else -> 0L
        }
        onProgress(bytes, maxOf(estimate, bytes))
    }

    /** Decrypts (AES-128) and, for TS, strips a disguise header; appends to [out]. Returns bytes written. */
    private suspend fun append(
        segment: HlsSegment,
        raw: File,
        out: FileOutputStream,
        headers: Map<String, String>,
        keys: MutableMap<String, ByteArray>,
        fmp4: Boolean,
        first: Boolean,
    ): Long {
        val key = segment.key?.let { k -> keys[k.uri] ?: fetchBytes(k.uri, null, headers).also { keys[k.uri] = it } }
        val input = raw.inputStream().let { if (key != null) HlsCrypto.decrypting(it, key, HlsCrypto.ivFor(segment)) else it }
        return input.use { stream ->
            // Streamed: only the head is held, to find where the TS packets start.
            val head = ByteArray(HEAD_BYTES)
            var headLength = 0
            while (headLength < head.size) {
                val n = stream.read(head, headLength, head.size - headLength)
                if (n < 0) break
                headLength += n
            }
            val start = if (fmp4) 0 else TsSync.start(head, headLength)
            if (start < 0) {
                // The very first segment decides the format: no TS packets = nothing the player could open.
                if (first) throw HlsRefusedException("segment is not MPEG-TS")
                // A later one without packets (an empty filler) adds nothing.
                return@use 0L
            }
            out.write(head, start, headLength - start)
            var written = (headLength - start).toLong()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
            }
            written
        }
    }

    private suspend fun fetchPlaylist(url: String, headers: Map<String, String>): HlsPlaylist {
        val (text, finalUrl) = withRetries {
            client.newCall(request(url, null, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")
                if (body.contentLength() > MAX_PLAYLIST_BYTES) throw HlsRefusedException("playlist too large")
                body.string() to resp.request.url.toString()
            }
        }
        return HlsPlaylistParser.parse(text, finalUrl)
    }

    private suspend fun fetchBytes(url: String, range: HlsByteRange?, headers: Map<String, String>): ByteArray = withRetries {
        client.newCall(request(url, range, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
            val bytes = resp.body?.bytes() ?: throw IOException("respuesta sin cuerpo")
            // A server that ignores Range sends the whole resource: take the asked-for slice of it.
            if (range == null || resp.code != 200) return@use bytes
            if (range.offset + range.length > bytes.size) throw IncompleteDownloadException(bytes.size.toLong(), range.offset + range.length)
            bytes.copyOfRange(range.offset.toInt(), (range.offset + range.length).toInt())
        }
    }

    private suspend fun fetchToFile(segment: HlsSegment, file: File, headers: Map<String, String>): File = withRetries {
        client.newCall(request(segment.uri, segment.range, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
            val body = resp.body ?: throw IOException("respuesta sin cuerpo")
            // A server that ignores Range sends the whole resource: take the asked-for slice of it.
            val skip = if (segment.range != null && resp.code == 200) segment.range.offset else 0L
            val expected = segment.range?.length ?: body.contentLength()
            body.byteStream().use { input ->
                if (skip > 0) input.skipNBytesCompat(skip)
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val want = if (segment.range != null) minOf(buf.size.toLong(), expected - total).toInt() else buf.size
                        if (want <= 0) break
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    if (expected > 0 && total < expected) throw IncompleteDownloadException(total, expected)
                }
            }
            file
        }
    }

    private fun request(url: String, range: HlsByteRange?, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        range?.let { b.header("Range", it.header()) }
        return b.build()
    }

    /** [attempts] tries, [retryDelayMs] apart, for what [DownloadRetryPolicy] calls transient; the rest fails at once. */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(attempts) { n ->
            coroutineContext.ensureActive()
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!DownloadRetryPolicy.isTransient(e)) throw e
                last = e
                if (n < attempts - 1) delay(retryDelayMs)
            }
        }
        throw last!!
    }

    private fun java.io.InputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw IOException("fin inesperado del segmento")
                left--
            } else {
                left -= skipped
            }
        }
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 4
        /** What [TsSync.start] looks through for the first packet. */
        private const val HEAD_BYTES = 64 * 1024
        private const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024
    }
}
