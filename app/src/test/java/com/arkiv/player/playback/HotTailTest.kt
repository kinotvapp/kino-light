package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The end of the file, served from memory. See [HotTail] for why it's needed.
 */
class HotTailTest {

    // A 1000-byte file of which we have the last 100 saved (900 to 999), each byte worth its
    // position modulo 251 (prime, so no pattern repeats within the stretch).
    private val TOTAL = 1000L
    private val START = 900L
    private val TAIL = ByteArray(100) { ((START + it) % 251).toByte() }

    private fun serve(header: String?) =
        HotTail.serve(START, TAIL, RangeHeader.parse(header), TOTAL)

    @Test fun an_open_range_inside_the_tail_is_served_from_memory() {
        // It's what libVLC asks for while probing the end: `bytes=N-` with N near the EOF.
        val expected = ByteArray(60) { ((940 + it) % 251).toByte() }
        assertArrayEquals(expected, serve("bytes=940-"))
    }

    @Test fun a_closed_range_inside_the_tail_too() {
        val expected = ByteArray(10) { ((940 + it) % 251).toByte() }
        assertArrayEquals(expected, serve("bytes=940-949"))
    }

    @Test fun the_tail_s_first_byte_is_included() {
        assertArrayEquals(TAIL, serve("bytes=900-"))
    }

    @Test fun a_range_that_starts_before_the_tail_is_not_served() {
        // It would be missing bytes at the front: answering only the piece we have would hand VLC
        // a body shorter than the Content-Length, and it would be left waiting for the rest forever.
        assertNull(serve("bytes=899-"))
    }

    @Test fun a_range_past_the_end_of_the_file_is_not_served() {
        // An invalid request has to be answered by the origin, not by us making up a 206.
        assertNull(serve("bytes=940-1200"))
    }

    @Test fun with_no_range_nothing_is_served_from_the_tail() {
        // With no Range, VLC wants the WHOLE file from byte 0. The tail isn't that.
        assertNull(serve(null))
    }

    @Test fun with_no_tail_saved_nothing_is_served() {
        assertNull(HotTail.serve(START, ByteArray(0), RangeHeader.parse("bytes=940-"), TOTAL))
    }

    @Test fun not_knowing_the_file_s_size_serves_nothing() {
        // Without the total the upper bound can't be validated, and serving blind is worse than
        // going to the origin: a badly-built 206 leaves the player hanging with no error.
        assertNull(HotTail.serve(START, TAIL, RangeHeader.parse("bytes=940-"), 0L))
    }

    // ---- Which containers need the tail ----

    /**
     * MEASURED ON THE FIRE TV on 2026-08-14, seven playbacks in a row: the three **mp4** titles
     * downloaded their tail and did NOT use it **once** (zero `cola caliente` lines), while the
     * mpegts ones used it on every opening, several times each.
     *
     * And downloading it isn't free: on one of those mp4s it cost **8284 ms and three CDN
     * rejections**, in parallel with opening the video and against the same origin that has to
     * serve it.
     *
     * ```
     * 10:04:42.079  origen rechazó bytes=129893353-130155496 con -1 (intento 1/3)
     * 10:04:43.279  origen rechazó bytes=129893353-130155496 con -1 (intento 1/3)
     * 10:04:45.131  origen rechazó bytes=129893353-130155496 con -1 (intento 2/3)
     * 10:04:46.758  precalentada la cola: 256KB en 8284ms
     * ```
     */
    @Test fun mp4_does_not_need_the_tail() {
        assert(!HotTail.needsPreWarming("mp4"))
        assert(!HotTail.needsPreWarming("MP4"))
    }

    /** TS does: libVLC reads its last PCR to deduce the duration, and without it it won't open. */
    @Test fun ts_needs_it() {
        assert(HotTail.needsPreWarming("ts"))
        assert(HotTail.needsPreWarming("mpegts"))
    }

    /**
     * When in doubt, it gets pre-warmed. An unknown container might have its index at the end -
     * Matroska keeps its Cues there, which is where the infinite-buffering bug on `.mkv` torrents
     * came from- and not downloading it would mean going back to that failure to save 256 KB.
     */
    @Test fun when_in_doubt_it_gets_pre_warmed() {
        assert(HotTail.needsPreWarming(""))
        assert(HotTail.needsPreWarming("matroska"))
        assert(HotTail.needsPreWarming("flv"))
        assert(HotTail.needsPreWarming(null))
    }
}
