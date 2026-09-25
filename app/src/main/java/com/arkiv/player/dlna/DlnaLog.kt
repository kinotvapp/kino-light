package com.arkiv.player.dlna

import android.util.Log
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
 * Never logs a full URL: [DlnaXml.safeUrl] drops the query, where the proxies carry their tokens.
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
        Log.i(TAG, "[$session] $message")
    }

    fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, "[$session] $message") else Log.w(TAG, "[$session] $message", t)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, "[$session] $message") else Log.e(TAG, "[$session] $message", t)
    }

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

    private fun isLoopback(address: String): Boolean =
        address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("::ffff:127.")
}
