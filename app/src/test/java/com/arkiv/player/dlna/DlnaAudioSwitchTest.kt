package com.arkiv.player.dlna

import com.arkiv.player.cast.CastAudioRoute
import com.arkiv.player.cast.CastAudioSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaAudioSwitchTest {

    @Test fun `the remux routes re-remux and reload`() {
        for (kind in listOf("vod-remux-hls", "vod-remux")) {
            assertEquals(kind, CastAudioRoute.REMUX, DlnaAudioSwitch.routeOf(kind))
            assertEquals(kind, CastAudioSwitch.REMUX_AND_RELOAD, DlnaAudioSwitch.onChoiceChanged(kind, onTv = 0, wanted = 1))
        }
    }

    @Test fun `a file sent as it is only changes on the phone`() {
        for (kind in listOf("vod-proxy", "raw-url", "live-hls")) {
            assertEquals(kind, CastAudioRoute.FIXED, DlnaAudioSwitch.routeOf(kind))
            assertEquals(kind, CastAudioSwitch.PHONE_ONLY, DlnaAudioSwitch.onChoiceChanged(kind, onTv = 0, wanted = 1))
        }
    }

    @Test fun `the same audio, or no cast, does nothing`() {
        assertEquals(CastAudioSwitch.NONE, DlnaAudioSwitch.onChoiceChanged("vod-remux-hls", onTv = 1, wanted = 1))
        assertEquals(CastAudioSwitch.NONE, DlnaAudioSwitch.onChoiceChanged(null, onTv = 0, wanted = 1))
    }

    @Test fun `the TV's fresh position wins`() {
        assertEquals(2_400_000L, DlnaAudioSwitch.tvPositionMs(2_400_000L, 2_390_000L, 600_000L, seekSettled = true))
    }

    @Test fun `a TV that answers nothing or 0 falls back on the last read, then on the start`() {
        assertEquals(2_390_000L, DlnaAudioSwitch.tvPositionMs(null, 2_390_000L, 600_000L, seekSettled = true))
        assertEquals(2_390_000L, DlnaAudioSwitch.tvPositionMs(0L, 2_390_000L, 600_000L, seekSettled = true))
        assertEquals(600_000L, DlnaAudioSwitch.tvPositionMs(0L, null, 600_000L, seekSettled = true))
    }

    @Test fun `before its start Seek the TV is where it is about to be put, not at its own 0`() {
        assertEquals(600_000L, DlnaAudioSwitch.tvPositionMs(3_000L, 3_000L, 600_000L, seekSettled = false))
    }

    @Test fun `a cast from the top with no position says nothing`() {
        assertNull(DlnaAudioSwitch.tvPositionMs(null, null, 0L, seekSettled = false))
        assertEquals(12_000L, DlnaAudioSwitch.tvPositionMs(12_000L, null, 0L, seekSettled = false))
    }

    @Test fun `the re-send starts where the TV was`() {
        assertEquals(2_400_000L, DlnaAudioSwitch.startMs(tvMs = 2_400_000L, phoneMs = 600_000L, itemStartMs = 0L))
    }

    @Test fun `never 0 when the TV was elsewhere`() {
        // The TV cannot say: the phone (paused where the cast began), then where the item opened.
        assertEquals(600_000L, DlnaAudioSwitch.startMs(tvMs = null, phoneMs = 600_000L, itemStartMs = 0L))
        assertEquals(900_000L, DlnaAudioSwitch.startMs(tvMs = null, phoneMs = 0L, itemStartMs = 900_000L))
        val tv = DlnaAudioSwitch.tvPositionMs(reportedMs = 0L, lastKnownMs = null, castStartMs = 600_000L, seekSettled = true)
        assertEquals(600_000L, DlnaAudioSwitch.startMs(tv, phoneMs = 0L, itemStartMs = 0L))
    }
}
