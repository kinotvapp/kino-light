package com.arkiv.player.data.local

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** An HTTP code that wasn't 2xx. Typed so it can be classified without parsing the message. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The response cut off before completing the declared `Content-Length`. */
class IncompleteDownloadException(val written: Long, val total: Long) :
    IOException("descarga incompleta: $written de $total bytes")

/**
 * Which failures are worth retrying on their own, and how many times.
 *
 * The distinction is between "the world shifted and it might work again in a bit" (network) and
 * "this is going to fail the same way tomorrow" (unsupported source, no space, web movie).
 * Retrying the second kind burns data and battery with zero chance of success; NOT retrying the
 * first leaves a 4 GB download at 80% stuck in `failed` because WiFi flickered for 30 seconds,
 * with the `.part` intact and nobody to pick it back up.
 *
 * Pure on purpose (doesn't touch WorkManager or Room): so it's tested on the JVM with no device.
 */
object DownloadRetryPolicy {

    /**
     * Cap on a SINGLE row's automatic attempts. Without a cap, a source that returns 503 forever
     * would retry in a loop until the user deletes the row. Once the cap is exhausted the row stays
     * in `failed` with its reason and the screen's "Reintentar" button is still available.
     */
    const val MAX_ATTEMPTS = 4

    /**
     * HTTP codes that ARE worth retrying: 408 (request timeout), 429 (we got throttled) and every
     * 5xx (the server is having a bad time right now). A 403/404 instead means the link expired or
     * doesn't exist: retrying it with the same `Range` will fail exactly the same way.
     */
    fun isTransientStatus(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    /**
     * Network or server failure (retryable) vs content/environment failure (definitive).
     *
     * Anything that isn't an `IOException` counts as definitive: an unexpected logic exception
     * doesn't get fixed by waiting 30 seconds.
     */
    fun isTransient(t: Throwable): Boolean = when (t) {
        is HttpStatusException -> isTransientStatus(t.code)
        // Cutoff mid-transfer: this is exactly the `.part` + `Range` case.
        is IncompleteDownloadException -> true
        is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException -> true
        is IOException -> true
        else -> false
    }

    /**
     * [attempt] is the number of attempts ALREADY made for this row (0 on the first). WorkManager
     * exposes it as `runAttemptCount` and applies exponential backoff between one and the next.
     */
    fun shouldRetry(transient: Boolean, attempt: Int): Boolean = transient && attempt + 1 < MAX_ATTEMPTS
}
