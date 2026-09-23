package com.arkiv.player.data.caracol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaracolDownloadTest {

    private val sample = CaracolDownload(
        mpd = "https://mdstrm.com/video/693351c8fdae1b250c0d85e3.mpd",
        keys = listOf(TrackKey(0, 0, 4), TrackKey(0, 1, 1)),
        height = 720,
    )

    @Test
    fun `survives the round trip to json`() {
        assertEquals(sample, CaracolDownload.fromJson(sample.toJson()))
    }

    @Test
    fun `a record with no mpd is not useful to open anything`() {
        assertNull(CaracolDownload.fromJson("""{"claves":["0.0.4"]}"""))
    }

    @Test
    fun `a record with no keys either`() {
        assertNull(CaracolDownload.fromJson("""{"mpd":"https://x/y.mpd","claves":[]}"""))
    }

    @Test
    fun `garbage does not blow up, it returns null`() {
        assertNull(CaracolDownload.fromJson("no soy json"))
        assertNull(CaracolDownload.fromJson(""))
    }

    @Test
    fun `keys are read and written as period dot group dot track`() {
        assertEquals("0.1.4", TrackKey(0, 1, 4).text())
        assertEquals(TrackKey(0, 1, 4), TrackKey.fromText("0.1.4"))
    }

    @Test
    fun `a malformed key is discarded instead of blowing up`() {
        assertNull(TrackKey.fromText("0.1"))
        assertNull(TrackKey.fromText("a.b.c"))
        assertNull(TrackKey.fromText("0.-1.2"))
    }
}
