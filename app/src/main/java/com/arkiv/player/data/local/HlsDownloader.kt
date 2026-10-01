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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
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
 * segments, deleted as soon as they are appended, and on cancellation). A refusal
 * ([HlsRefusedException], or the plugin's host gate: [PluginHostRefusal]) deletes all of them: the
 * row ends `refused` and nothing will ever resume that part, so it must not sit on the disk.
 *
 * A segment is bounded while it is written: never more than [segmentCapFor] bytes (a playlist
 * listing a live endpoint or a whole movie as one "segment" is refused, not written until the disk
 * is full), and the disk is measured every [checkEveryBytes] of it ([FreeSpacePolicy]). A segment
 * that arrives empty is asked for again [emptyAttempts] times (a CDN hiccup answering 200 with no
 * body) before it is a refusal.
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
    /** The floor of [segmentCapFor]: whatever the playlist declares, a segment may be this big. */
    private val minSegmentCapBytes: Long = MIN_SEGMENT_CAP_BYTES,
    private val emptyAttempts: Int = 3,
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
            .onFailure { if (isRefusal(it)) discard(targetDir, baseName) }
    }

    /** Final for this stream: the worker ends the row `refused`, and nothing resumes its files. */
    private fun isRefusal(t: Throwable) = t is HlsRefusedException || PluginHostRefusal.of(t) != null

    /** Every file of an HLS download of [base] but the finished one: part, state and raw segments. */
    private fun discard(dir: File, base: String) {
        dir.listFiles { f -> f.name == "$base.hls.part" || f.name == "$base.hls.state" || f.name.startsWith("$base.hls.seg") }
            ?.forEach { runCatching { it.delete() } }
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
        var variant: HlsVariant? = null
        val media = when (val first = fetchPlaylist(url, headers)) {
            is HlsPlaylist.Media -> first.playlist
            is HlsPlaylist.Master -> {
                val picked = HlsVariantPicker.pick(first.playlist)
                variant = picked
                when (val second = fetchPlaylist(picked.uri, headers)) {
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

        // Disk guard: the declared bandwidth over the whole duration, when the master said it.
        val bandwidth = variant?.bandwidth ?: 0L
        val estimate = if (bandwidth > 0) (bandwidth / 8.0 * media.totalDurationSec).toLong() else 0L
        freeSpace(dir).let { if (!FreeSpacePolicy.fits(it, estimate)) throw InsufficientSpaceException(it) }

        // The content's own bytes go into the fingerprint (fMP4: the init section, needed anyway;
        // TS: the head of the first segment), so a retry that resolved to another encode with the
        // same segment count and duration starts over instead of splicing two encodes, while the
        // same bytes behind another CDN's URL still resume. An AES-128 TS head is hashed DECRYPTED:
        // sites that hand out a new key (URL and bytes) on every resolve encrypt the same video
        // differently each time, and the part holds plaintext anyway, so only the plaintext says
        // whether it is the same content. The key itself is never part of the fingerprint.
        val keys = ConcurrentHashMap<String, ByteArray>()
        val initBytes = media.init?.let { fetchCapped(it.uri, it.range, headers, MAX_INIT_BYTES, "init section") }
        val contentBytes = initBytes ?: fetchHead(segments[0], headers).let { head ->
            val key = segments[0].key?.let { k -> keys[k.uri] ?: fetchKey(k.uri, headers).also { keys[k.uri] = it } }
            if (key == null) head else HlsCrypto.decryptPrefix(head, key, HlsCrypto.ivFor(segments[0]))
        }
        val fingerprint = HlsResumeState.fingerprint(media, variant, HlsResumeState.contentHash(contentBytes))

        val saved = runCatching { stateFile.readText() }.getOrNull()?.let(HlsResumeState::fromJson)
        var state = HlsResumeState.resumable(saved, resumeKey, fingerprint, if (part.exists()) part.length() else -1L, segments.size)
        if (state == null) {
            part.delete()
            val header = if (initBytes != null) initBytes + ByteArray(Mp4Sidx.size(segments.size)) else ByteArray(0)
            FileOutputStream(part).use { it.write(header) }
            state = HlsResumeState(resumeKey, fingerprint, 0, header.size.toLong(), emptyList(), header.size.toLong())
            stateFile.writeText(state.toJson())
        } else {
            RandomAccessFile(part, "rw").use { it.setLength(state.partBytes) }
        }

        // TS discontinuities are kept as they are unless the program's audio/video codecs change
        // there ([TsProgramCheck]): compared with the first segment's, re-read from the part on a resume.
        val program = TsProgramCheck(if (!fmp4 && state.segmentsDone > 0) TsProgram.streams(readHead(part)) else null)

        var sinceDiskCheck = 0L
        var done = state.segmentsDone
        report(onProgress, state, segments.size)
        try {
            while (done < segments.size) {
                coroutineContext.ensureActive()
                val batch = (done until minOf(done + concurrency, segments.size)).toList()
                val raw = coroutineScope {
                    batch.map { i ->
                        async { fetchSegment(i, segments[i], File(dir, "$base.hls.seg$i"), headers, segmentCapFor(segments[i], bandwidth)) }
                    }.awaitAll()
                }
                val sizes = state!!.sizes.toMutableList()
                FileOutputStream(part, true).use { out ->
                    batch.forEachIndexed { n, i ->
                        val written = append(i, segments[i], raw[n], out, headers, keys, fmp4, program)
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
        if (!fmp4) runCatching { logJoinedDuration(target, segments) }
        return target
    }

    /**
     * Diagnostics (tag KinoProgress): the playlist's duration against the PCR span the player will
     * read off the joined file, with the discontinuity count. No names, no URLs.
     */
    private fun logJoinedDuration(file: File, segments: List<HlsSegment>) {
        val window = JoinedTsDuration.WINDOW_BYTES
        val (head, tail) = RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val h = ByteArray(minOf(len, window.toLong()).toInt()).also { raf.readFully(it) }
            val tailLen = minOf(len, window.toLong()).toInt()
            raf.seek(len - tailLen)
            h to ByteArray(tailLen).also { raf.readFully(it) }
        }
        val playlistMs = (segments.sumOf { it.durationSec } * 1000).toLong()
        val pcrMs = JoinedTsDuration.pcrSpanMs(head, tail)
        android.util.Log.i(
            "KinoProgress",
            "joined ts · segments=${segments.size} discontinuities=${segments.count { it.discontinuity }} " +
                "playlist=${playlistMs}ms pcrSpan=${pcrMs}ms agrees=${JoinedTsDuration.agrees(pcrMs, playlistMs)}",
        )
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
        index: Int,
        segment: HlsSegment,
        raw: File,
        out: FileOutputStream,
        headers: Map<String, String>,
        keys: MutableMap<String, ByteArray>,
        fmp4: Boolean,
        program: TsProgramCheck,
    ): Long {
        // Checked as each segment arrives, not at the end ([fetchSegment] already asked again): an
        // fMP4 fragment of 0 bytes cannot even be described in the sidx, and a TS one is a hole.
        if (raw.length() == 0L) throw HlsRefusedException("segment $index is empty")
        val key = segment.key?.let { k -> keys[k.uri] ?: fetchKey(k.uri, headers).also { keys[k.uri] = it } }
        val written = try {
            copySegment(index, segment, raw, out, key, fmp4, program)
        } catch (e: IOException) {
            // A wrong key (an error page served as the key, a rotated key) fails the padding check:
            // the same on every retry, so it is a refusal, not a network trouble.
            if (e.cause is GeneralSecurityException) throw HlsRefusedException("segment $index does not decrypt: ${e.cause}")
            throw e
        } catch (e: GeneralSecurityException) {
            throw HlsRefusedException("segment $index does not decrypt: $e")
        }
        if (fmp4 && written !in 1..Mp4Sidx.MAX_REFERENCE_SIZE) throw HlsRefusedException("fMP4 segment $index has $written bytes")
        return written
    }

    private fun copySegment(
        index: Int,
        segment: HlsSegment,
        raw: File,
        out: FileOutputStream,
        key: ByteArray?,
        fmp4: Boolean,
        program: TsProgramCheck,
    ): Long {
        val first = index == 0
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
            if (!fmp4) program.check(index, first, segment.discontinuity, head.copyOfRange(start, headLength))
            // Renumbered PIDs after a splice are written back under the first segment's (see TsProgramCheck).
            val rewriter = program.rewriter()?.let { TsPidRewriter(out, it) }
            val write: (ByteArray, Int, Int) -> Unit = rewriter?.let { r -> r::write } ?: { b, off, len -> out.write(b, off, len) }
            write(head, start, headLength - start)
            var written = (headLength - start).toLong()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                write(buf, 0, n)
                written += n
            }
            rewriter?.finish()
            written
        }
    }

    private suspend fun fetchPlaylist(url: String, headers: Map<String, String>): HlsPlaylist {
        val (text, finalUrl) = withRetries {
            client.newCall(request(url, null, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")
                if (body.contentLength() > MAX_PLAYLIST_BYTES) throw HlsRefusedException("playlist too large")
                // Bounded whatever the headers say: a chunked body has no length to check up front.
                val bytes = body.byteStream().use { it.readAtMost(MAX_PLAYLIST_BYTES + 1) }
                if (bytes.size > MAX_PLAYLIST_BYTES) throw HlsRefusedException("playlist larger than $MAX_PLAYLIST_BYTES bytes")
                String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8) to resp.request.url.toString()
            }
        }
        return HlsPlaylistParser.parse(text, finalUrl)
    }

    /** An AES-128 key: exactly 16 bytes, anything else (an error page, a JSON…) is refused. */
    private suspend fun fetchKey(url: String, headers: Map<String, String>): ByteArray {
        val key = fetchCapped(url, null, headers, KEY_BYTES.toLong(), "AES-128 key")
        if (key.size != KEY_BYTES) throw HlsRefusedException("AES-128 key has ${key.size} bytes")
        return key
    }

    /**
     * A small resource (init section, key) held in memory, never more than [cap] bytes of it read:
     * with a [range], exactly its length (from its offset when the server ignored `Range` and sent
     * the whole resource with a 200); without one, [cap] + 1 at most, and more is a refusal.
     */
    private suspend fun fetchCapped(url: String, range: HlsByteRange?, headers: Map<String, String>, cap: Long, what: String): ByteArray {
        if (range != null && range.length > cap) throw HlsRefusedException("$what byte range of ${range.length} bytes")
        return withRetries {
            client.newCall(request(url, range, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")
                body.byteStream().use { input ->
                    if (range == null) {
                        val bytes = input.readAtMost(cap + 1)
                        if (bytes.size > cap) throw HlsRefusedException("$what larger than $cap bytes")
                        return@use bytes
                    }
                    if (resp.code == 200) input.skipNBytesCompat(range.offset)
                    val bytes = input.readAtMost(range.length)
                    if (bytes.size < range.length) throw IncompleteDownloadException(bytes.size.toLong(), range.length)
                    bytes
                }
            }
        }
    }

    /** The first [HEAD_PROBE_BYTES] of [segment] (within its byte range), for the resume fingerprint. */
    private suspend fun fetchHead(segment: HlsSegment, headers: Map<String, String>): ByteArray {
        val range = segment.range?.let { HlsByteRange(minOf(it.length, HEAD_PROBE_BYTES), it.offset) } ?: HlsByteRange(HEAD_PROBE_BYTES, 0)
        return withRetries {
            client.newCall(request(segment.uri, range, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")
                body.byteStream().use { input ->
                    if (resp.code == 200) input.skipNBytesCompat(range.offset)
                    input.readAtMost(range.length)
                }
            }
        }
    }

    private fun readHead(file: File): ByteArray = file.inputStream().use { it.readAtMost(HEAD_BYTES.toLong()) }

    /**
     * The most one segment may weigh: [SEGMENT_CAP_FACTOR] times what the variant's declared
     * bandwidth says it should (a VBR peak is far below that), never under [minSegmentCapBytes] and
     * never over a sidx reference's 31 bits.
     */
    private fun segmentCapFor(segment: HlsSegment, bandwidth: Long): Long {
        val expected = if (bandwidth > 0) (bandwidth / 8.0 * segment.durationSec).toLong() else 0L
        return maxOf(minSegmentCapBytes, expected * SEGMENT_CAP_FACTOR).coerceAtMost(Mp4Sidx.MAX_REFERENCE_SIZE)
    }

    /**
     * [fetchToFile], asked again when it arrives empty: a 200 with an empty (chunked) body is often a
     * CDN hiccup that the next request doesn't repeat. Empty [emptyAttempts] times in a row is a hole
     * in the video, and a refusal.
     */
    private suspend fun fetchSegment(index: Int, segment: HlsSegment, file: File, headers: Map<String, String>, cap: Long): File {
        repeat(emptyAttempts) { n ->
            val fetched = fetchToFile(index, segment, file, headers, cap)
            if (fetched.length() > 0) return fetched
            if (n < emptyAttempts - 1) delay(retryDelayMs)
        }
        throw HlsRefusedException("segment $index is empty")
    }

    private suspend fun fetchToFile(index: Int, segment: HlsSegment, file: File, headers: Map<String, String>, cap: Long): File {
        if (segment.range != null && segment.range.length > cap) throw HlsRefusedException("segment $index byte range of ${segment.range.length} bytes")
        return withRetries {
            client.newCall(request(segment.uri, segment.range, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")
                // A server that ignores Range sends the whole resource: take the asked-for slice of it.
                val skip = if (segment.range != null && resp.code == 200) segment.range.offset else 0L
                val expected = segment.range?.length ?: body.contentLength()
                if (expected > cap) throw HlsRefusedException("segment $index declares $expected bytes")
                body.byteStream().use { input ->
                    if (skip > 0) input.skipNBytesCompat(skip)
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var sinceDiskCheck = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val want = if (segment.range != null) minOf(buf.size.toLong(), expected - total).toInt() else buf.size
                            if (want <= 0) break
                            val n = input.read(buf, 0, want)
                            if (n < 0) break
                            // Bounded whatever the headers said: a chunked body has no length to check up front.
                            if (total + n > cap) throw HlsRefusedException("segment $index larger than $cap bytes")
                            out.write(buf, 0, n)
                            total += n
                            sinceDiskCheck += n
                            if (sinceDiskCheck >= checkEveryBytes) {
                                sinceDiskCheck = 0L
                                freeSpace(file.parentFile ?: file).let { if (FreeSpacePolicy.isExhausted(it)) throw InsufficientSpaceException(it) }
                            }
                        }
                        if (expected > 0 && total < expected) throw IncompleteDownloadException(total, expected)
                    }
                }
                file
            }
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

    private fun java.io.InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
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
        /** An fMP4 init section is a few KB (moov with its track headers); far more is not one. */
        const val MAX_INIT_BYTES = 16L * 1024 * 1024
        private const val KEY_BYTES = 16
        /** How much of the first TS segment identifies the content for the resume fingerprint. */
        private const val HEAD_PROBE_BYTES = 16L * 1024
        /** A segment is a few MB (10 s of 4K is ~50 MB); this much is never one, whatever was declared. */
        const val MIN_SEGMENT_CAP_BYTES = 256L * 1024 * 1024
        /** How far over its declared bandwidth × duration a segment may go before it is refused. */
        private const val SEGMENT_CAP_FACTOR = 8L
    }
}
