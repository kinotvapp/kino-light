package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CastDiagTest {

    @Test
    fun `a url in a message loses its token and its query`() {
        val out = CastDiag.scrub("load http://192.168.2.11:39493/t/0123456789abcdef0123456789abcdef/hls.m3u8?f=0.5 now")
        assertEquals("load http://192.168.2.11:39493/t/…/hls.m3u8 now", out)
    }

    @Test
    fun `a remux url is masked too`() {
        val out = CastDiag.scrub("uri=http://10.0.0.2:4000/r/11d5dc3da36ae1c8fe7f9d09498d07f3/media.m3u8")
        assertEquals("uri=http://10.0.0.2:4000/r/…/media.m3u8", out)
    }

    @Test
    fun `a CDN url keeps its host but not its signature`() {
        val out = CastDiag.scrub("origin https://cdn.example.com/vod/a_media.ts?auth=SECRET&exp=1")
        assertFalse(out, out.contains("SECRET"))
        assertEquals("origin https://cdn.example.com/vod/a_media.ts", out)
    }

    @Test
    fun `a bare long hex run is blanked`() {
        assertEquals("token … gone", CastDiag.scrub("token 39B06948C7DB42BAA374603D5A661FED gone"))
    }

    @Test
    fun `ordinary numbers stay`() {
        assertEquals("seg 46 at 406.3s took 7367ms", CastDiag.scrub("seg 46 at 406.3s took 7367ms"))
    }
}
