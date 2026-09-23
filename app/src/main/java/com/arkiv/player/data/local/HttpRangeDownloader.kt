package com.arkiv.player.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Resumption arithmetic, kept apart so it can be tested with no network or disk. */
object RangeMath {
    /** null when there's nothing prior: asking for `bytes=0-` needlessly confuses some hosts. */
    fun rangeHeaderFor(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    /** A partial response's `Content-Length` is what's LEFT, not the file's total. */
    fun totalBytesOf(contentLength: Long, startByte: Long): Long =
        if (contentLength <= 0) 0 else contentLength + startByte
}

/**
 * Downloads a file over HTTP with resume support.
 *
 * Always writes to `<target>.part` and renames it at the end: this way a half-downloaded
 * destination file that `LocalLibrary` could mistake for good and hand to the player never exists.
 *
 * A partial is ONLY resumed if it's from the same origin (see [LocalFilePaths.originOf]); if it
 * doesn't match — or if it has no mark, which is the case for partials left by earlier versions —
 * it's thrown away and started from zero. Losing what's downloaded once is infinitely better than
 * gluing one file's tail to another's prefix and marking it "Listo".
 */
class HttpRangeDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        /**
         * The origin's identity for deciding whether the `.part` can be resumed. Defaults to the
         * URL itself. It's passed differently when the URL ISN'T stable across attempts even
         * though the content is: today that's Magis's case, whose URL carries a token that
         * changes on every resolution (see `MagisDownloadStrategy`, which passes the `episodeId`
         * as the key); with the URL as the key, a simple retry would discard a perfectly valid
         * multi-GB partial.
         */
        resumeKey: String = url,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val part = LocalFilePaths.partOf(target)
            val origin = LocalFilePaths.originOf(target)

            // Only a partial from the SAME origin gets resumed. With no mark (a partial from an
            // old version) it counts as unknown origin: it can't be asserted to be the same file.
            val sameOrigin = part.exists() &&
                runCatching { origin.readText() }.getOrNull() == resumeKey
            if (part.exists() && !sameOrigin) {
                part.delete()
                origin.delete()
            }
            val startByte = if (sameOrigin) part.length() else 0L

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            RangeMath.rangeHeaderFor(startByte)?.let { builder.header("Range", it) }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")

                // If Range was requested and the server answered 200 (doesn't support it), what
                // arrives is the WHOLE file: the partial has to be discarded or the prefix would
                // end up duplicated.
                val appending = startByte > 0 && resp.code == 206
                if (startByte > 0 && !appending) part.delete()
                val effectiveStart = if (appending) startByte else 0L
                val total = RangeMath.totalBytesOf(body.contentLength(), effectiveStart)

                // The mark gets (re)written BEFORE the first byte: if the process dies halfway,
                // whatever partial is left is already labeled and the next attempt knows where it
                // came from.
                runCatching { origin.writeText(resumeKey) }

                var written = effectiveStart
                java.io.FileOutputStream(part, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            // The write loop doesn't suspend, so without this check a cancellation
                            // (the user tapped "Quitar", or WorkManager stopped the worker) went
                            // unnoticed until the whole file finished: mobile data kept being spent
                            // on something already cancelled. `ensureActive` throws
                            // CancellationException and the `use` blocks close the stream and response.
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                }

                // Verification: if the server declared a size and it didn't arrive complete, it's a cutoff.
                if (total > 0 && written < total) {
                    throw IncompleteDownloadException(written, total)
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("no se pudo renombrar el parcial")
                // No more partial left to identify.
                runCatching { origin.delete() }
                target
            }
        }.onFailure {
            // `runCatching` also catches CancellationException, and swallowing it would turn a
            // "the user cancelled" into a "the download failed" (a row in `failed` with an absurd
            // message) and would lie to WorkManager about why the worker finished.
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
    }
}
