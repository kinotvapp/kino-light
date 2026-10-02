package com.arkiv.player.dlna

import com.arkiv.player.dlna.DlnaSubtitleSwitch.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaSubtitleSwitchTest {

    private val es = "http://192.168.1.5:40000/s/tok/0/0.srt"
    private val en = "http://192.168.1.5:40000/s/tok/0/1.srt"

    @Test fun `another subtitle re-sends every VOD route, the file as it is included`() {
        for (kind in listOf("vod-remux-hls", "vod-remux", "vod-proxy", "raw-url")) {
            assertEquals(kind, Action.RESEND, DlnaSubtitleSwitch.onChoiceChanged(kind, onTv = es, wanted = en, audioPending = false))
        }
    }

    @Test fun `turning them on or off re-sends too`() {
        // "Desactivados": re-sent with no subtitle fields at all.
        assertEquals(Action.RESEND, DlnaSubtitleSwitch.onChoiceChanged("vod-proxy", onTv = es, wanted = null, audioPending = false))
        assertEquals(Action.RESEND, DlnaSubtitleSwitch.onChoiceChanged("vod-remux-hls", onTv = null, wanted = es, audioPending = false))
    }

    @Test fun `what the TV already has does nothing`() {
        assertEquals(Action.NONE, DlnaSubtitleSwitch.onChoiceChanged("vod-remux-hls", onTv = es, wanted = es, audioPending = false))
        // An embedded subtitle picked, or off with nothing offered: no external one either way.
        assertEquals(Action.NONE, DlnaSubtitleSwitch.onChoiceChanged("vod-proxy", onTv = null, wanted = null, audioPending = false))
    }

    @Test fun `no cast, or a live channel, does nothing`() {
        assertEquals(Action.NONE, DlnaSubtitleSwitch.onChoiceChanged(null, onTv = null, wanted = es, audioPending = false))
        assertEquals(Action.NONE, DlnaSubtitleSwitch.onChoiceChanged("live-hls", onTv = null, wanted = es, audioPending = false))
    }

    @Test fun `while another audio is prepared it goes along with that send`() {
        assertEquals(Action.WITH_AUDIO, DlnaSubtitleSwitch.onChoiceChanged("vod-remux-hls", onTv = es, wanted = en, audioPending = true))
        assertEquals(Action.NONE, DlnaSubtitleSwitch.onChoiceChanged("vod-remux-hls", onTv = es, wanted = es, audioPending = true))
    }

    @Test fun `the re-send starts where the TV is, never back at 0 00`() {
        // The audio switch's own fallbacks: the TV, else the phone, else where the item opened.
        assertEquals(754_000L, DlnaAudioSwitch.startMs(tvMs = 754_000L, phoneMs = 120_000L, itemStartMs = 0L))
        assertEquals(120_000L, DlnaAudioSwitch.startMs(tvMs = null, phoneMs = 120_000L, itemStartMs = 0L))
        assertEquals(60_000L, DlnaAudioSwitch.startMs(tvMs = null, phoneMs = null, itemStartMs = 60_000L))
    }
}
