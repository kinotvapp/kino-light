package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeHeaderTest {
    @Test fun parses_open_ended() {
        val r = RangeHeader.parse("bytes=1000-")!!
        assertEquals(1000L, r.start); assertNull(r.end)
    }
    @Test fun parses_closed() {
        val r = RangeHeader.parse("bytes=0-1023")!!
        assertEquals(0L, r.start); assertEquals(1023L, r.end)
    }
    @Test fun null_when_absent() { assertNull(RangeHeader.parse(null)) }
    @Test fun null_when_malformed() { assertNull(RangeHeader.parse("kilobytes=1-2")) }
}
