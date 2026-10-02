package com.arkiv.player.data.subtitles

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * [MovieHash.RangeReader] over HTTP range requests, through [client] and [headers]: the SAME gated
 * client and headers the player uses for that stream, so it reaches nothing the player could not.
 * The size comes from the `Content-Range` total of a one-byte range (a server that answers 200 to a
 * range has no range support: not hashable). Never logs or throws; the URL (maybe signed) stays here.
 */
class HttpRangeReader(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : MovieHash.RangeReader {

    override suspend fun length(): Long? {
        val r = fetch(0, 1) ?: return null
        return r.use { if (it.code == 206) totalOf(it.header("Content-Range")) else null }
    }

    override suspend fun read(offset: Long, len: Int): ByteArray? {
        val r = fetch(offset, len) ?: return null
        return r.use {
            if (it.code != 206) return@use null
            runCatching { it.body?.source()?.readByteArray(len.toLong()) }.getOrNull()?.takeIf { b -> b.size == len }
        }
    }

    private suspend fun fetch(offset: Long, len: Int): Response? = try {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$offset-${offset + len - 1}").get().build()
        val call = client.newCall(request)
        suspendCancellableCoroutine<Response?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The total of `bytes 0-0/1234` (1234), or null for `*` or garbage. */
        fun totalOf(contentRange: String?): Long? =
            contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}

/** Why a stream is not worth a hash: no subtitle was ever uploaded for that exact file, or it can't be range-read. */
enum class HashSkip { NOT_REMOTE, LOCAL_PROXY, LIVE, HLS, DASH, TS, XUPER, DRM }

/** Which streams get a hash attempt: only a stable, plain, remote file. Pure. */
object SubtitleHashPolicy {

    /** Null when the stream at [url] is hashable; else the first reason it is not. */
    fun skipReason(url: String, mime: String, live: Boolean, xuper: Boolean, proxied: Boolean, drm: Boolean): HashSkip? {
        val u = runCatching { java.net.URI(url) }.getOrNull()
        val scheme = u?.scheme?.lowercase()
        val path = u?.path.orEmpty().lowercase()
        val host = u?.host?.lowercase().orEmpty()
        val m = mime.lowercase()
        return when {
            scheme != "http" && scheme != "https" -> HashSkip.NOT_REMOTE
            proxied || host == "127.0.0.1" || host == "localhost" -> HashSkip.LOCAL_PROXY
            live -> HashSkip.LIVE
            xuper -> HashSkip.XUPER
            drm -> HashSkip.DRM
            "mpegurl" in m || path.endsWith(".m3u8") || path.endsWith(".m3u") -> HashSkip.HLS
            "dash" in m || path.endsWith(".mpd") -> HashSkip.DASH
            m == "video/mp2t" || path.endsWith(".ts") || path.endsWith(".m2ts") -> HashSkip.TS
            else -> null
        }
    }

    /** The playing file's name from its URL: last path segment, decoded, without query, credentials or extension. */
    fun fileNameOf(url: String): String? {
        val seg = runCatching { java.net.URI(url).path }.getOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") }.getOrDefault(seg)
        return decoded.replace(Regex("\\.(mp4|mkv|avi|m4v|mov|webm|wmv|mpg|mpeg|ts|ogv)$", RegexOption.IGNORE_CASE), "").takeIf { it.isNotBlank() }
    }
}
