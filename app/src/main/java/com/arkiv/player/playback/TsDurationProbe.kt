package com.arkiv.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Duration of an MPEG-TS, deduced from its PCR.
 *
 * TS is a broadcast format: it doesn't carry the duration in any header. The only way to know how
 * long it runs is subtracting the program clock reference (PCR) at the start from the one at the
 * end. libVLC used to do exactly that... but ONLY when the access was fast-read (a local file):
 * over HTTP it never probed the end, so `mediaPlayer.length` stayed at 0. With duration 0 the
 * progress bar fills up instantly, the right side shows 00:00, there's no seeking forward (seeking
 * by TIME is ignored without a duration) and where you were doesn't get saved.
 *
 * Since the origin does accept Range, we pull it ourselves: 256 KB from the start + 256 KB from
 * the end. Measured against magis's real movie, it's 89ms off from ffprobe (10,143.84s vs
 * 10,143.93s), which is more than enough for drawing the bar and seeking.
 */
object TsDurationProbe {

    private const val TAG = "ArkivTsDur"


    /** How much is downloaded from each end looking for a PCR. 256 KB ≈ 1400 packets: plenty (a
     *  PCR repeats at least every 100 ms by spec). */
    const val PROBE_BYTES = 256 * 1024

    /**
     * How long the WHOLE probe can take before it's given up for lost and playback starts with no
     * duration. Applied by the caller (the video doesn't start until this finishes).
     *
     * It used to be 30 s, which is excessive for something that only paints a progress bar: the
     * duration is a nicety, never a reason to leave the user staring at a spinner. Today's number
     * comes from [TsDurationProbeTest]: it has to fit the MEASURED case -one dead connection per
     * stretch, recovered on the second attempt- and little more.
     */
    const val BUDGET_MS = 13_000L

    /**
     * Attempts per stretch, and how long each one gets.
     *
     * Against this CDN **giving up fast and trying again wins**: a new connection rolls the dice
     * again, while waiting out a bad one only spends the budget. It used to be one 15 s attempt
     * plus a spare, and lost whenever the CDN got dense (seen on device: both stretches timed out
     * and the film came out with no duration).
     *
     * The NUMBERS no longer live here. This probe hits exactly the same CDN as
     * [ArchiveCacheProxy], so keeping its own calibration only let the two drift apart: on
     * 2026-08-11 the proxy waited 20 s and the probe 8 s on the same dead connection, both too
     * long (the retry answered in ~1 s). Single source: [OriginPolicy.Profile.MAGIS].
     */
    // Its own profile, not playback's: see [OriginPolicy.Profile.MAGIS_PROBE]. What's at stake
    // here is a spinner, not the film cutting out, so it gives up sooner.
    private val PROFILE = OriginPolicy.Profile.MAGIS_PROBE
    private val ATTEMPTS = OriginPolicy.attempts(PROFILE)

    fun readTimeoutMs(attempt: Int): Int = OriginPolicy.responseMs(attempt, PROFILE)

    /** Breather between attempts: short on purpose, the point is to roll the dice again right away. */
    fun waitBetweenAttemptsMs(attempt: Int): Long = OriginPolicy.waitMs(attempt, PROFILE)

    /** Above this the parsing went haywire: better no duration than a made-up one. */
    private const val MAX_BELIEVABLE_MS = 24L * 60 * 60 * 1000

    /**
     * Duration in ms between [head]'s first PCR and [tail]'s last one of the SAME pid, or 0 if it
     * can't be determined. Requiring the same pid avoids mixing clocks from different programs,
     * which would give a nonsensical number.
     *
     * Packet parsing lives in [MpegTs] since [TsSegmenter] needed exactly the same thing: two
     * copies would end up disagreeing about a counter wraparound or where a packet starts, and
     * that would show up as a bar that doesn't line up with the playlist.
     */
    fun durationMs(head: ByteArray, tail: ByteArray): Long {
        val first = MpegTs.pcrs(head).firstOrNull() ?: return 0L
        val last = MpegTs.pcrs(tail).lastOrNull { it.pid == first.pid } ?: return 0L
        val ms = MpegTs.deltaTicks(first.base90k, last.base90k) * 1000 / MpegTs.PCR_HZ
        return if (ms in 1..MAX_BELIEVABLE_MS) ms else 0L
    }

    /**
     * Downloads both ends of the stream and calculates the duration. Returns 0 if anything fails:
     * it's a nicety for the bar, never a reason not to play.
     *
     * [headers] are the origin's (magis serves behind `Content-Auth` and `Content-License`).
     */
    suspend fun probeRemote(url: String, headers: Map<String, String>): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // ONE END AT A TIME, and the tail first. Measured afterward: the CDN DOES serve two
        // connections to the same file (the old "one per file" theory was false), but answers each
        // range whenever it feels like it -between 0.2 s and 20 s- so requesting both together
        // shortens nothing and doubles the chances of hitting a bad one. The tail goes by
        // suffix-range (`bytes=-N`) so as not to have to ask the size first, and goes first because
        // it's the one that fails most: no tail means no duration, so no point spending the budget
        // downloading a useless head.
        val tail = fetchRange(url, headers, "bytes=-$PROBE_BYTES")
            ?: return@withContext 0L
        val head = fetchRange(url, headers, "bytes=0-${PROBE_BYTES - 1}")
            ?: return@withContext 0L
        val ms = durationMs(head, tail)
        android.util.Log.w(
            TAG,
            "PCR probe: head=${head.size}B tail=${tail.size}B → duration=${ms}ms " +
                "(${System.currentTimeMillis() - t0}ms)",
        )
        ms
    }

    /** One stretch, retrying: see [ATTEMPTS] for why there are several, short ones. */
    private fun fetchRange(url: String, headers: Map<String, String>, range: String): ByteArray? {
        repeat(ATTEMPTS) { i ->
            tryRange(url, headers, range, i)?.let { return it }
            if (i < ATTEMPTS - 1) Thread.sleep(waitBetweenAttemptsMs(i))
        }
        android.util.Log.w(TAG, "range $range: exhausted all $ATTEMPTS attempts")
        return null
    }

    private fun tryRange(
        url: String,
        headers: Map<String, String>,
        range: String,
        attempt: Int,
    ): ByteArray? =
        runCatching {
            val conn = open(url, headers, range, attempt)
            // No 206 means the server ignored the Range and would be sending the WHOLE file
            // (hundreds of MB for a probe). Cut off before reading anything.
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                android.util.Log.w(TAG, "range $range: the origin ignored the Range (${conn.responseCode})")
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        }.getOrElse {
            android.util.Log.w(TAG, "range $range failed: ${it.message}")
            null
        }

    private fun open(
        url: String,
        headers: Map<String, String>,
        range: String,
        attempt: Int,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", range)
            // A new socket, not recycled from the pool: see OriginPolicy.Profile.reuseSockets.
            if (!PROFILE.reuseSockets) setRequestProperty("Connection", "close")
            connectTimeout = PROFILE.connectMs
            readTimeout = readTimeoutMs(attempt)
        }
}
