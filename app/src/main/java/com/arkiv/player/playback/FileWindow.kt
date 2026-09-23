package com.arkiv.player.playback

/**
 * Serve a CHUNK of the file as if it were the whole file.
 *
 * Exists to resume an MPEG-TS from magis without using libVLC's seek, which used to break
 * playback. Over HTTP, libVLC didn't know the duration, so the only seek that responded was by
 * FRACTION, which sought by byte; and doing so left the demuxer with the clock at the old point
 * while data from the new one came in. Measured on device: after the seek, VLC swallowed 752 MB
 * in 330s (17x real time, i.e. discarding all of it) and the clock didn't move a single ms --
 * black screen and "buffering 0%" forever.
 *
 * Opening directly AT the point doesn't have that problem: it's the case that does work (a movie
 * never watched before starts perfectly). So instead of opening at 0 and seeking, the proxy opens
 * a window that STARTS at the requested point and lies to the player about the size: for VLC it
 * was a new file starting at 0, with a clean clock and not a single seek.
 */
object FileWindow {

    /** Size of a TS packet. Invariant of the format. */
    private const val PACKET = 188

    /**
     * Byte where the window for the requested [fraction] starts, aligned DOWN to a TS packet.
     *
     * The alignment is what saves the demuxer from having to resync blindly: landing on the 0x47,
     * the first packet is already valid. Clamped so there's always at least one packet ahead (an
     * empty window would be a 0-byte file).
     */
    fun start(total: Long, fraction: Float): Long {
        if (total <= PACKET || fraction <= 0f) return 0L
        // In Double on purpose: a Float has 24 bits of mantissa and a 1 GB file doesn't fit in
        // that, so the calculated byte drifted by up to ~64 bytes off the requested one (and with
        // it, the time offset added to the clock).
        val raw = (total.toDouble() * fraction.coerceIn(0f, 1f).toDouble()).toLong()
        val cap = total - PACKET
        return (raw.coerceIn(0L, cap) / PACKET) * PACKET
    }

    /** Size of the virtual file the player sees: what's left starting at [start]. */
    fun visibleSize(total: Long, start: Long): Long = (total - start).coerceAtLeast(0L)

    /**
     * The `Range` to request from the origin for the [range] the player asked for.
     *
     * The player asks in the virtual file's coordinates (which starts at 0) and the origin only
     * understands the real file's: here the offset gets added back. With no range requested, it
     * asks from [start] to the end, which is the whole virtual file.
     */
    fun rangeToOrigin(range: ByteRange?, start: Long): String {
        val from = start + (range?.start ?: 0L)
        val until = range?.end?.let { start + it }
        return if (until == null) "bytes=$from-" else "bytes=$from-$until"
    }

    /**
     * The `Content-Range` to hand back to the player, translated from the origin's.
     *
     * Returns null if the origin's can't be parsed: better to send none than to send one in real
     * coordinates, which would make the player think the file starts at a byte that doesn't exist
     * for it.
     */
    fun visibleContentRange(originContentRange: String?, start: Long): String? {
        val m = RE_CONTENT_RANGE.find(originContentRange?.trim().orEmpty()) ?: return null
        val from = m.groupValues[1].toLongOrNull() ?: return null
        val until = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].toLongOrNull() ?: return null
        if (from < start) return null
        return "bytes ${from - start}-${until - start}/${visibleSize(total, start)}"
    }

    /** The file's real total, read from the origin's `Content-Range` (`bytes a-b/TOTAL`). */
    fun totalFromContentRange(contentRange: String?): Long {
        val m = RE_CONTENT_RANGE.find(contentRange?.trim().orEmpty()) ?: return 0L
        return m.groupValues[3].toLongOrNull() ?: 0L
    }

    private val RE_CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
}
