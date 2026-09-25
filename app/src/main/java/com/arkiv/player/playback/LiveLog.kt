package com.arkiv.player.playback

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * One tag for the whole live-TV story, one id per channel zap, so a session reads top to bottom:
 *
 *     adb logcat -s ArkivLive
 *     [L4 cyx-RCNHD] ── zap to cyx-RCNHD RCN HD ──
 *     [L4 cyx-RCNHD] open: session resolved in 1840ms
 *     [L4 cyx-RCNHD] playlist seq=2782 (+1) segs=6 target=5.0s 1624B in 159ms cdn=…
 *     [L4 cyx-RCNHD] segment ok 812KB ttfb=210ms copy=430ms (1.9MB/s) dur=5.0s
 *     [L4 cyx-RCNHD] first frame 3120ms after the channel was chosen
 *     [L4 cyx-RCNHD] quality: state=READY buffered=28400ms liveOffset=6100ms stalls=0 dropped=0 …
 *
 * The id is process-wide because the pieces that write to it run on different threads (the proxy's
 * connection threads, the player's main thread, the view model's coroutine). It changes on a zap, not
 * on a reopen of the SAME channel after a cut, so the reopen shows up inside the story it belongs to.
 * Everything written here also reaches GlitchTip as a breadcrumb attached to a failure event.
 */
internal object LiveLog {
    const val TAG = "ArkivLive"

    private val counter = AtomicInteger()

    @Volatile
    var session: String = "-"
        private set

    @Volatile
    var channel: String = "-"
        private set

    @Volatile
    private var startedAtMs = 0L

    /** A new zap: starts the clock that "time to first frame" is measured against. */
    fun newSession(channelCode: String, channelName: String = ""): String {
        val id = "L${counter.incrementAndGet()}"
        session = id
        channel = channelCode
        startedAtMs = SystemClock.elapsedRealtime()
        i("── zap to $channelCode ${channelName.take(30)} ──")
        return id
    }

    /** The same channel opened again (after a cut or coming back to the app): the first-frame clock restarts, the id stays. */
    fun reopen(reopensSoFar: Int) {
        startedAtMs = SystemClock.elapsedRealtime()
        w("reopening the same channel (reopens so far: $reopensSoFar)")
    }

    /** Milliseconds since the person chose this channel, or -1 when no zap has been seen. */
    fun sinceZapMs(): Long = if (startedAtMs == 0L) -1L else SystemClock.elapsedRealtime() - startedAtMs

    fun i(message: String) {
        Log.i(TAG, "[$session $channel] $message")
    }

    fun w(message: String) {
        Log.w(TAG, "[$session $channel] $message")
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, "[$session $channel] $message") else Log.e(TAG, "[$session $channel] $message", t)
    }
}
