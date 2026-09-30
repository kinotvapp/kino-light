package com.arkiv.player.data.local

import com.arkiv.player.data.plugin.HostNotAllowedException
import com.arkiv.player.data.plugin.PluginFetchException
import com.arkiv.player.data.plugin.PrivateAddressException
import com.arkiv.player.data.plugin.UndeclaredPlaybackHostException
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
     * doesn't get fixed by waiting 30 seconds. So is a plugin's host gate refusing the request
     * ([PluginHostRefusal]), although it arrives as an `IOException` (an `UnknownHostException` for
     * a name that points into the home network): the same host is refused on every retry.
     */
    fun isTransient(t: Throwable): Boolean = if (PluginHostRefusal.of(t) != null) false else when (t) {
        is HttpStatusException -> isTransientStatus(t.code)
        // Cutoff mid-transfer: this is exactly the `.part` + `Range` case.
        is IncompleteDownloadException -> true
        is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException -> true
        // A full disk never fixes itself in 30 seconds, and every retry writes more onto it. Our
        // own guard throws the typed one; the raw OS one (ENOSPC, raised when the guard is beaten
        // by something else filling the disk) has to be recognized by its message.
        is InsufficientSpaceException -> false
        is IOException -> !isNoSpaceLeft(t)
        else -> false
    }

    private fun isNoSpaceLeft(t: IOException): Boolean {
        val message = t.message.orEmpty()
        return message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }

    /**
     * [attempt] is the number of attempts ALREADY made for this row (0 on the first). WorkManager
     * exposes it as `runAttemptCount` and applies exponential backoff between one and the next.
     */
    fun shouldRetry(transient: Boolean, attempt: Int): Boolean = transient && attempt + 1 < MAX_ATTEMPTS

    /**
     * What the worker does with a [DownloadOutcome.Failed]: a [DownloadOutcome.Failed.permanent]
     * refusal is final whatever else it says ([FailureResolution.REFUSE]); a transient failure with
     * attempts left retries; everything else lands in `failed` with "Reintentar" available.
     */
    fun resolve(transient: Boolean, permanent: Boolean, attempt: Int): FailureResolution = when {
        permanent -> FailureResolution.REFUSE
        shouldRetry(transient, attempt) -> FailureResolution.RETRY
        else -> FailureResolution.FAIL
    }

    /**
     * Whether a failure is worth a crash report: a definitive one nobody expected. Network trouble
     * retries on its own, and a permanent refusal (an HLS-only plugin source, DRM, live) is a
     * documented limit of the downloader, not a bug -- reporting each one would only be noise; so
     * is an [expected][DownloadOutcome.Failed.expected] one (a plugin the person switched off).
     */
    fun reports(transient: Boolean, permanent: Boolean, expected: Boolean = false): Boolean = !transient && !permanent && !expected
}

/** See [DownloadRetryPolicy.resolve]. */
enum class FailureResolution {
    /** Same request again, with WorkManager's backoff; the row stays `downloading` with the reason. */
    RETRY,
    /** `failed`, with the reason and "Reintentar". */
    FAIL,
    /** `refused`: final, no retry, no report, removable. */
    REFUSE,
}

/**
 * A plugin download the plugin's host gate refused (`PluginStreamHttp`, the same gate the player
 * applies): a CDN the plugin never declared, a redirect to one, a name that resolves into the home
 * network, plain http where only https is allowed. Deterministic -- the same request is refused on
 * every retry -- so it is a permanent refusal ([DownloadOutcome.Failed.permanent]), never a network
 * trouble to retry. Classified here by type, whatever `PluginHttp` says in its message.
 */
object PluginHostRefusal {
    /** The gate's refusal in [t] or its cause chain, or null when [t] is anything else. */
    fun of(t: Throwable): Throwable? = generateSequence(t) { it.cause }.take(MAX_CAUSES).firstOrNull(::isGate)

    /**
     * What the Downloads row says. A host the player would ask about ([UndeclaredPlaybackHostException],
     * thrown by a download client built with `askAboutFor`) tells the person how to allow it: playing
     * the title once asks, and the approved host is in the plugin's hosts for the next download.
     */
    fun message(t: Throwable): String = when (val e = of(t)) {
        is UndeclaredPlaybackHostException ->
            "El video usa un servidor (${e.host.take(100)}) que este plugin no tiene permitido. " +
                "Reprodúcelo una vez para aprobar ese servidor y vuelve a descargarlo."
        is PrivateAddressException -> "El servidor del video apunta a tu red local, y este plugin no puede usarla."
        is HostNotAllowedException -> "El servidor del video (${e.host.take(100)}) no está permitido para este plugin."
        else -> "El servidor del video no está permitido para este plugin."
    }

    private fun isGate(e: Throwable): Boolean =
        e is HostNotAllowedException || e is PrivateAddressException || (e is PluginFetchException && e.code == "host_not_allowed")

    private const val MAX_CAUSES = 8
}
