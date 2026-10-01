package com.arkiv.player.dlna

import android.util.Log
import com.arkiv.player.cast.CastDiag
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One tag, one id per cast attempt, so a whole DLNA attempt reads as a single story:
 *
 *     adb logcat -s ArkivDlna
 *     [c3] cast start kind=vod-proxy device='Sala' …
 *     [c3] soap SetAVTransportURI → 200 in 41ms
 *     [c3] TV→proxy GET /stream.mp4 range=bytes=0- ua=… from=192.168.1.40
 *     [c3] upstream 403 type=text/html → relaying it as 403 (NOT as 200)
 *     [c3] transport PLAYING → STOPPED (+4s)
 *
 * Every line carries the id of the cast it belongs to, including the ones written by the local servers
 * the TV pulls from (they run on other threads), which is why the id is process-wide. Anything that
 * goes through `Log` here also reaches GlitchTip as a breadcrumb attached to the failure event.
 *
 * PRIVATE by construction, like the Chromecast's: EVERY line goes through [CastDiag.scrub] (any URL
 * reduced to scheme/host/path with its token segments hidden and its query dropped, any long hex
 * run blanked), whatever the caller put in it -- an exception's own text, a renderer's SOAP fault, a
 * device description's URL. An exception is logged as its scrubbed text, never with its raw trace.
 *
 * The moments that explain a cast ([diag]: the route chosen, what the renderer lists, the start
 * `Seek`, every request the TV makes with its timing) ALSO go to the cast's own trail,
 * `adb logcat -s KinoCastDiag`, as `dlna [cN] …`: one place for both protocols.
 */
internal object DlnaLog {
    const val TAG = "ArkivDlna"

    private val counter = AtomicInteger()

    @Volatile
    var session: String = "-"
        private set

    /** Requests the renderer has made to our servers during the current cast (the phone's own player doesn't count). */
    val lanHits = AtomicInteger()
    val lanBytes = AtomicLong()

    /** Starts a new cast attempt and returns its id. Resets the "did the TV come for the media?" counters. */
    fun newSession(): String {
        val id = "c${counter.incrementAndGet()}"
        session = id
        lanHits.set(0)
        lanBytes.set(0)
        return id
    }

    fun i(message: String) {
        Log.i(TAG, line(message, null))
    }

    fun w(message: String, t: Throwable? = null) {
        Log.w(TAG, line(message, t))
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, line(message, t))
    }

    /** [i] here and the same line in the cast's diagnostic trail ([CastDiag], tag `KinoCastDiag`). */
    fun diag(message: String) {
        i(message)
        CastDiag.i("dlna [$session] $message")
    }

    /** [w] here and in the cast's diagnostic trail. */
    fun diagW(message: String) {
        w(message)
        CastDiag.w("dlna [$session] $message")
    }

    /** One scrubbed line: the message and, when there is one, the exception's scrubbed text (no raw trace). */
    fun line(message: String, t: Throwable?): String =
        CastDiag.scrub("[$session] $message" + (t?.let { " · $it" } ?: ""))

    /**
     * A request has arrived at one of our LAN servers. Counts it and logs who asked for what when the
     * caller is NOT this phone (the phone's own player also talks to these servers over loopback and
     * that isn't what a DLNA cast is about). Returns whether it was a renderer.
     */
    fun lanHit(server: String, remote: String?, requestLine: String, range: String?, userAgent: String?): Boolean {
        if (remote == null || isLoopback(remote)) return false
        lanHits.incrementAndGet()
        val method = requestLine.substringBefore(' ')
        val path = DlnaXml.safeUrl(requestLine.split(' ').getOrNull(1))
        i("TV→$server $method $path range=${range ?: "(none)"} ua=${userAgent?.take(60) ?: "?"} from=$remote")
        return true
    }

    fun isLoopback(address: String): Boolean =
        address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("::ffff:127.")
}
