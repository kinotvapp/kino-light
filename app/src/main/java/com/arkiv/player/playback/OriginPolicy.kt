package com.arkiv.player.playback

/**
 * How much to put up with archive.org, and when it's worth asking again.
 *
 * The why, measured on 2026-08-10 against the item `tpo-neon-genesis-evangelion-05-…`:
 * the node took **72.3 s to the first byte** and then served a perfect 206. With the fixed
 * `readTimeout = 20000` that existed, all three attempts died by timeout well before the node got
 * to answer, the proxy returned 502 and the film never started -- even though it was whole and
 * available on the other end. It wasn't a network failure or the player's: it was the app giving
 * up too early.
 *
 * Hence this object's two ideas:
 *
 * 1. **The read timeout grows with each attempt.** Against a slow origin, insisting with the same
 *    short deadline is repeating the same failure three times. Each attempt gets more room, and
 *    the last covers the worst measured case. The first stays short so a genuinely dead origin
 *    doesn't leave anyone staring at the screen for several minutes.
 *
 * 2. **A timeout is NOT a rejection.** The code used to treat "it answered no" (503) and "it never
 *    got to answer" (-1) the same, and worse: it also retried 404s. A 404 doesn't change by
 *    insisting -the file isn't there- so retrying it is throwing away three timeouts to land in
 *    the same place. And it's exactly the signal that archive renamed the file and the metadata
 *    needs revalidating.
 */
object OriginPolicy {

    /**
     * How much to put up with EACH origin, because they don't fail the same way.
     *
     * This object was born measuring archive.org and for a while its calibration got applied to
     * everything. On 2026-08-11, while diagnosing why magis took ~15 s to start, magis's CDN
     * (`yuwc.swzablvpm.com`) got measured and turned out to be the OPPOSITE kind of origin:
     *
     * | | archive.org | magis |
     * |---|---|---|
     * | healthy worst case | 72.3 s to first byte | 0.82 s (3 connections at once) |
     * | how it fails | takes forever, but arrives | answers NOTHING, ever |
     *
     * On magis the "slow but it arrives" case doesn't exist: it answers in under a second or it's
     * dead. With archive's calibration, every dead connection cost **20.1 s of waiting** -measured
     * on device, with the retry answering in 101 ms right after.
     *
     * [response] and [body] are two different things stuffed into `HttpURLConnection`'s single
     * `readTimeout`, and separating them is what allows lowering the first without breaking the
     * second: an origin that doesn't answer in 2 s is dead, but a stream that goes 2 s without data
     * mid-film is a perfectly normal WiFi hiccup.
     */
    enum class Profile(
        val connectMs: Int,
        internal val response: IntArray,
        internal val body: Int,
        internal val waitBase: Long,
        /**
         * Whether sockets from the keep-alive pool can be recycled for it.
         *
         * Magis says no, and the reason is the hypothesis that explains why `curl` never
         * reproduced the hang (a new socket every time, 26 shots with no hang) while on device the
         * open connection always hung right after `preWarm` abandoned a 209 MB `bytes=0-` having
         * read 2 MB: an undrained body going back into the pool leaves the next request reading
         * leftovers instead of headers. Reusing saves a ~100 ms handshake; the hang costs seconds.
         */
        val reuseSockets: Boolean,
    ) {
        /** 20 s → 45 s → 90 s. The last one covers the measured 72.3 s with margin. */
        ARCHIVE(15_000, intArrayOf(20_000, 45_000, 90_000), 90_000, 400L, true),

        /**
         * 4s -> 10s -> 20s. The first deadline stays short on purpose -- the whole point here is
         * rolling the dice again right away, not waiting -- but the later ones give the CDN the
         * time it genuinely takes: it's been measured answering the same range anywhere from
         * 0.2s to 20s.
         *
         * They used to be a flat 3s, calibrated so the whole budget (9.65s) fit inside the 10s the
         * old "no picture -> software" rescue took to fire. That invariant DIED once magis moved to
         * ExoPlayer: there's no longer a media reload to beat to the punch, and what was left was a
         * tight deadline strangling healthy requests. Measured on the Fire Stick on 2026-08-22: two
         * ranges "rejected by the origin" at 3.002s and 3.004s -- this timer, not the CDN, cutting
         * them off -- cost 18s of waiting before the first picture. This is the third time this
         * number has come up short (2s -> 3s -> here).
         *
         * The waits BETWEEN attempts stay short (50ms -> 150ms -> 450ms): archive's long step
         * exists so as not to punish a saturated node answering 503, and this CDN doesn't throttle
         * us that way -- what limits us is the portal, and the gateway handles that.
         */
        MAGIS(5_000, intArrayOf(4_000, 10_000, 20_000), 30_000, 50L, false),

        /**
         * The same CDN, but for the DURATION PROBE, which plays a different game.
         *
         * They used to share a profile -"one single source of truth"- and that made sense while
         * both wanted the same thing. Not anymore: the proxy is serving playback and benefits from
         * insisting, while the probe BLOCKS STARTUP (every second of it is a second of spinner) and
         * what it's after is a duration that, if it doesn't arrive, only costs a bar with no total.
         * Stretching out [MAGIS]'s deadlines pushed its worst case from 12.1 s to 28.1 s, i.e. it
         * blew its budget and got cancelled: the bar was left with no duration in exactly the case
         * that did have a fix.
         *
         * So it keeps the same flat 3 s as always, which is what fits its budget (see
         * TsDurationProbeTest). Against this CDN, giving up fast and rolling the dice again wins.
         */
        MAGIS_PROBE(5_000, intArrayOf(3_000, 3_000, 3_000), 30_000, 50L, false),
    }

    /** Attempts against the origin before giving up. */
    const val ATTEMPTS = 3

    /**
     * Connect timeout, the same across every attempt: what's slow isn't the handshake.
     * Measured in the same case: 4.28 s to connect against 72.3 s to the first byte.
     */
    const val CONNECT_MS = 15_000

    fun attempts(profile: Profile = Profile.ARCHIVE): Int = profile.response.size

    /**
     * How long to wait for THE RESPONSE (the headers) on this attempt.
     *
     * Grows with each attempt: against a slow origin, repeating the same short deadline is
     * repeating the same failure. Archive's full budget lands at ~160 s: a lot for a blank screen,
     * but it's the price of not discarding an origin that was going to answer, and it's only paid
     * in full when the origin accepts the connection and then goes silent.
     */
    fun responseMs(attempt: Int, profile: Profile = Profile.ARCHIVE): Int =
        profile.response[attempt.coerceIn(0, profile.response.lastIndex)]

    /**
     * How long a stall is tolerated WHILE READING THE BODY, with the response already in hand.
     *
     * Generous on purpose and unrelated to [responseMs]: by here we already know the origin is
     * alive and serving, and a network hiccup mid-playback recovers on its own. Cutting off fast
     * here fixes nothing -- it breaks the film.
     */
    fun bodyMs(profile: Profile = Profile.ARCHIVE): Int = profile.body

    /**
     * Wait before the next attempt: on archive 400 ms → 1.2 s → 3.6 s.
     *
     * Used to be a flat 400 ms. Against a saturated node -exactly the one answering 503- three
     * attempts in 1.2 s is three quick blows at one that already said it can't keep up. The first
     * stays short because an occasional "no" recovers right away and the good case shouldn't be punished.
     */
    fun waitMs(attempt: Int, profile: Profile = Profile.ARCHIVE): Long {
        var ms = profile.waitBase
        repeat(attempt.coerceIn(0, profile.response.lastIndex)) { ms *= 3 }
        return ms
    }

    /**
     * Whether this code deserves another attempt.
     *
     * Only what can change on its own gets retried: no answer at all (-1), the server having
     * trouble (5xx), or it throttling us (429). Everything else -404 and 410 because the resource
     * isn't there, permission 4xxs because they don't fix themselves in 400 ms, and successes- gets
     * answered on the spot without spending attempts.
     */
    fun worthRetrying(code: Int): Boolean = code == NO_RESPONSE || code == 429 || code in 500..599

    /** Code the proxy uses when the connection died without ever giving a response. */
    const val NO_RESPONSE = -1
}
