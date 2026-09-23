package com.arkiv.player.playback

/**
 * The END of the file, served from memory.
 *
 * When opening a TS over HTTP, libVLC used to probe the end: it asked for ranges a few KB from
 * the EOF to get the last PCR and deduce the duration. Those requests were what ate up startup --
 * measured on 2026-08-11 on the Fire TV, with the video already in `Playing` but stuck at `pos=0`:
 *
 * ```
 * bytes=859421696-   (940 B from the end)    → 206 with 0 KB
 * bytes=859419628-   (3,008 B from the end)  → 206 with 2 KB
 * bytes=859415492-   (7,144 B from the end)  → three attempts timed out in a row
 * ```
 *
 * And it's not that the CDN is broken there: requested from the Mac, those SAME offsets answered
 * 24 out of 24 times in 0.14-0.37s. It's the real path's latency that sometimes blows past any
 * reasonable deadline, and adjusting the deadline only moves the problem -- a short one cuts off
 * someone who was about to answer, a long one waits for someone who won't.
 *
 * That's why this doesn't roll the dice: the tail is downloaded ONCE together with startup (see
 * [ArchiveCacheProxy.preWarm]) and from there the player's probes are answered without
 * touching the network. It's the same idea as the hot startup at byte 0, applied to the other end.
 *
 * It's a pure function so the edges can be pinned by test, which is where this would be dangerous:
 * handing over a body shorter than the `Content-Length` leaves the player waiting forever, with
 * no visible error.
 */
object HotTail {

    /**
     * Whether the [container] container needs its tail pre-warmed.
     *
     * Measured on the Fire TV on 2026-08-14 over seven playbacks: the three **mp4** titles
     * downloaded their tail and did NOT use it once (zero `cola caliente` lines), while the mpegts
     * ones used it on every opening, several times each. And downloading it isn't free: on one of
     * those mp4s it cost **8284 ms and three CDN rejections**, in parallel with opening the video
     * and fighting the same origin that has to serve it for bandwidth.
     *
     * Only mp4 is skipped, which is the measured case. **When in doubt, it gets pre-warmed**: an
     * unknown container might have its index at the end -Matroska keeps its Cues there, which is
     * where the infinite-buffering bug on `.mkv` torrents came from- and saving 256 KB isn't worth
     * going back to that failure.
     *
     * Watch the residual risk: an mp4 WITHOUT faststart puts `moov` at the end and would need the
     * tail. Magis's -the only ones that come through here, [ArchiveCacheProxy.preWarm] has a
     * single caller- don't. If one ever did, it would show up in the log as a `pide
     * rango=bytes=<near the end>` on an mp4, and the worst that happens is that range goes to the
     * origin like it did before the tail existed.
     */
    fun needsPreWarming(container: String?): Boolean =
        container?.trim()?.lowercase() !in NO_INDEX_AT_END

    /** Containers that open without touching the end of the file. */
    private val NO_INDEX_AT_END = setOf("mp4", "m4v", "mov")

    /**
     * The bytes of [range] if they fall ENTIRELY within the saved tail, or null so the origin
     * resolves it.
     *
     * [tailStart] is the absolute byte where [tail] starts; [total] the file's size. Both are
     * required, and the range has to fit in whole: serving less would be worse than going to the network.
     */
    fun serve(tailStart: Long, tail: ByteArray, range: ByteRange?, total: Long): ByteArray? {
        if (tail.isEmpty() || total <= 0L || range == null) return null
        if (range.start < tailStart) return null
        // The requested end: `bytes=N-` means "to the EOF".
        val end = range.end ?: (total - 1)
        if (end > total - 1 || end < range.start) return null
        // And the requested stretch has to be covered by what's saved.
        val lastWeHave = tailStart + tail.size - 1
        if (end > lastWeHave) return null
        val from = (range.start - tailStart).toInt()
        val until = (end - tailStart).toInt() + 1
        return tail.copyOfRange(from, until)
    }
}
