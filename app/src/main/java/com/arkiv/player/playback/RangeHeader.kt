package com.arkiv.player.playback

/** Byte range requested by the HTTP client. `end` null = to the end. */
data class ByteRange(val start: Long, val end: Long?)

object RangeHeader {
    private val RE = Regex("""bytes=(\d+)-(\d*)""")
    fun parse(header: String?): ByteRange? {
        val m = header?.let { RE.matchEntire(it.trim()) } ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull()
        return ByteRange(start, end)
    }
}
