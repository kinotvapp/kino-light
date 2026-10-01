package com.arkiv.player.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DLNA's log lines are private whatever a caller puts in them. */
class DlnaLogTest {

    @Test
    fun `a credentialed url or a token never reaches a line`() {
        val line = DlnaLog.line(
            "proxy serving https://cdn.example/vod/a_media.ts?auth=SECRET as http://192.168.1.5:4000/t/0123456789abcdef0123456789abcdef/stream.ts",
            null,
        )
        assertFalse(line, line.contains("SECRET"))
        assertFalse(line, line.contains("0123456789abcdef0123456789abcdef"))
        assertTrue(line, line.contains("http://192.168.1.5:4000/t/…/stream.ts"))
    }

    @Test
    fun `an exception is logged as its scrubbed text`() {
        val e = java.io.IOException("unexpected end of stream on https://cdn.example/x.ts?sig=SECRET")
        val line = DlnaLog.line("proxy: request FAILED", e)
        assertFalse(line, line.contains("SECRET"))
        assertTrue(line, line.contains("IOException"))
    }

    @Test
    fun `the remux server's token path is masked too`() {
        val line = DlnaLog.line("TV GET http://10.0.0.2:4000/r/11d5dc3da36ae1c8fe7f9d09498d07f3/s3.m4s", null)
        assertTrue(line, line.contains("/r/…/s3.m4s"))
    }
}
