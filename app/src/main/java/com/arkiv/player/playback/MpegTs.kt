package com.arkiv.player.playback

/**
 * Packet-level primitives of an MPEG transport stream: finding packet alignment and reading the
 * program clock reference out of one.
 *
 * Extracted from [TsDurationProbe], which had them private, when [TsSegmenter] turned out to need
 * exactly the same three things. A second copy of PCR parsing is the kind of duplication this
 * codebase pays for later: the two would answer differently about a wrap, or about which byte a
 * packet starts on, and the symptom would be a playlist whose timestamps disagree with the
 * duration shown on the bar.
 *
 * Pure and free of Android, so its edges can be pinned by tests.
 */
object MpegTs {

    /** Transport-stream packet size. An invariant of the format. */
    const val PACKET = 188

    /** The PCR is a 33-bit counter at 90 kHz: it wraps roughly every 26.5 h. */
    const val PCR_WRAP = 1L shl 33

    /** Ticks per second of the PCR clock. */
    const val PCR_HZ = 90_000L

    data class Pcr(val pid: Int, val base90k: Long)

    /** A PCR together with the offset, inside the buffer it was read from, of its packet. */
    data class Located(val offset: Int, val pcr: Pcr)

    /**
     * Offset of the first complete packet in [buf], or -1.
     *
     * A Range request lands on any byte, so a block generally starts mid-packet. Alignment is the
     * 0x47 sync byte that repeats every 188: a single one could be any payload byte, three in a
     * row could not.
     */
    fun alignment(buf: ByteArray): Int {
        if (buf.size < PACKET) return -1
        // The offset falls inside the first packet by definition.
        val limit = minOf(buf.size - PACKET, PACKET - 1)
        for (off in 0..limit) {
            var k = 0
            var ok = true
            while (k < 3 && off + PACKET * (k + 1) <= buf.size) {
                if (buf[off + PACKET * k] != 0x47.toByte()) { ok = false; break }
                k++
            }
            if (ok && k > 0) return off
        }
        return -1
    }

    /**
     * Does the packet starting at [i] declare a random access point?
     *
     * `random_access_indicator` is the adaptation field's way of saying "a decoder can start
     * here" -- in practice the packet holding the start of an IDR frame. It is what an HLS
     * segment boundary has to land on: cut anywhere else and the receiver gets a segment it
     * cannot begin decoding, which it answers by stalling and asking for it again.
     */
    fun isRandomAccess(buf: ByteArray, i: Int): Boolean {
        if (i + PACKET > buf.size || i + 5 >= buf.size) return false
        if (buf[i] != 0x47.toByte()) return false
        val afc = (buf[i + 3].toInt() shr 4) and 0x3
        if (afc != 2 && afc != 3) return false
        if ((buf[i + 4].toInt() and 0xFF) < 1) return false        // empty adaptation field
        return buf[i + 5].toInt() and 0x40 != 0
    }

    /**
     * First packet in the block that is BOTH a random access point and carries a PCR, on [pid]
     * when given.
     *
     * Both are needed at a boundary: the random access point is where the decoder can start, and
     * the PCR is what dates it so the segment's duration is read rather than guessed.
     */
    fun firstRandomAccess(buf: ByteArray, pid: Int? = null): Located? {
        val start = alignment(buf)
        if (start < 0) return null
        var i = start
        while (i + PACKET <= buf.size) {
            if (isRandomAccess(buf, i)) {
                readPcr(buf, i)?.let { if (pid == null || it.pid == pid) return Located(i, it) }
            }
            i += PACKET
        }
        return null
    }

    /** The PCR of the packet starting at [i], if it carries one. */
    fun readPcr(buf: ByteArray, i: Int): Pcr? {
        if (i + PACKET > buf.size || i + 10 >= buf.size) return null
        if (buf[i] != 0x47.toByte()) return null
        val afc = (buf[i + 3].toInt() shr 4) and 0x3
        if (afc != 2 && afc != 3) return null                 // no adaptation field -> no PCR
        val afLen = buf[i + 4].toInt() and 0xFF
        if (afLen < 7) return null                            // a PCR does not fit (6 bytes + flags)
        if (buf[i + 5].toInt() and 0x10 == 0) return null     // PCR flag off
        val b = { k: Int -> buf[i + k].toLong() and 0xFF }
        val base = (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
        val pid = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)
        return Pcr(pid, base)
    }

    /** Every PCR in the block, in order of appearance. */
    fun pcrs(buf: ByteArray): List<Pcr> = located(buf).map { it.pcr }

    /** Every PCR in the block with the offset of the packet carrying it. */
    fun located(buf: ByteArray): List<Located> {
        val start = alignment(buf)
        if (start < 0) return emptyList()
        val out = ArrayList<Located>()
        var i = start
        while (i + PACKET <= buf.size) {
            readPcr(buf, i)?.let { out.add(Located(i, it)) }
            i += PACKET
        }
        return out
    }

    /**
     * First PCR in the block on [pid] (any pid when null), with its offset.
     *
     * Pinning the pid matters once more than one program is muxed in: mixing two clocks produces a
     * nonsense delta, which is the same trap [TsDurationProbe.durationMs] guards against.
     */
    fun firstPcr(buf: ByteArray, pid: Int? = null): Located? =
        located(buf).firstOrNull { pid == null || it.pcr.pid == pid }

    /** Wrap-aware difference between two PCR readings, in 90 kHz ticks. Never negative. */
    fun deltaTicks(from: Long, to: Long): Long {
        var d = to - from
        if (d < 0) d += PCR_WRAP
        return d
    }

    /** Wrap-aware difference in seconds. */
    fun deltaSeconds(from: Long, to: Long): Double = deltaTicks(from, to).toDouble() / PCR_HZ
}
