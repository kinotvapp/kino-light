package com.arkiv.player.data.subtitles

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** One HTTP answer, already read: [code] and at most [SubtitleHttp.MAX_BODY] bytes of [body]. */
internal class HttpAnswer(val code: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/** The plumbing both providers share: one bounded call, and a status code read as a [SubtitleFailure]. */
internal object SubtitleHttp {

    /** Bigger than any search page or zipped subtitle. */
    const val MAX_BODY = 6 * 1024 * 1024

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .dns(com.arkiv.player.data.net.DohDns)
        .build()

    /** Runs [request]: the answer, whatever its status, or the failure of a call that never got one. */
    fun execute(client: OkHttpClient, request: Request): SubtitleResult<HttpAnswer> = try {
        client.newCall(request).execute().use { resp ->
            val body = resp.body
            if (body != null && body.contentLength() > MAX_BODY) {
                SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)
            } else {
                SubtitleResult.Ok(HttpAnswer(resp.code, readCapped(body?.byteStream()) ?: return@use SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)))
            }
        }
    } catch (e: InterruptedIOException) {
        SubtitleResult.Failed(SubtitleFailure.TIMEOUT)
    } catch (e: IOException) {
        SubtitleResult.Failed(SubtitleFailure.NETWORK)
    } catch (e: IllegalArgumentException) {
        SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)
    }

    private fun readCapped(input: java.io.InputStream?): ByteArray? {
        if (input == null) return ByteArray(0)
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY) return null
            }
            return out.toByteArray()
        }
    }

    /**
     * What a non-2xx [code] means: 401/403 a bad key, 406 OpenSubtitles' daily download quota, 429
     * too many requests, anything else the service being down.
     */
    fun failureOf(code: Int): SubtitleFailure = when (code) {
        401, 403 -> SubtitleFailure.BAD_KEY
        406 -> SubtitleFailure.QUOTA
        429 -> SubtitleFailure.RATE_LIMITED
        else -> SubtitleFailure.UNAVAILABLE
    }
}
