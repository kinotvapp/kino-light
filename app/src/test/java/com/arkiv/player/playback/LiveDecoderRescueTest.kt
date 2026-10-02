package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecoderRescueTest {

    @Test
    fun `a decoder code on a hardware decoder triggers the rescue`() {
        for (code in 4001..4006) assertTrue(LiveDecoderRescue.shouldRescue(code, false, alreadySoftware = false, videoDecoder = "OMX.realtek.video.decoder"))
    }

    @Test
    fun `a CodecException in the causes triggers it even under a generic code`() {
        assertTrue(LiveDecoderRescue.shouldRescue(1000, true, alreadySoftware = false, videoDecoder = "OMX.realtek.video.decoder"))
    }

    @Test
    fun `it fires once per channel and never on a software decoder`() {
        assertFalse(LiveDecoderRescue.shouldRescue(4003, true, alreadySoftware = true))
        assertFalse(LiveDecoderRescue.shouldRescue(4003, true, alreadySoftware = false, videoDecoder = "c2.android.avc.decoder"))
        assertFalse(LiveDecoderRescue.shouldRescue(4003, true, alreadySoftware = false, videoDecoder = "OMX.google.h264.decoder"))
    }

    @Test
    fun `network and playlist errors do not trigger it`() {
        assertFalse(LiveDecoderRescue.shouldRescue(2001, false, alreadySoftware = false, videoDecoder = "c2.mtk.avc.decoder"))
        assertFalse(LiveDecoderRescue.shouldRescue(1002, false, alreadySoftware = false))
    }

    @Test
    fun `the give-up message is Spanish and names the channel`() {
        assertTrue(LiveDecoderRescue.message("Caracol").contains("Caracol"))
        assertTrue(LiveDecoderRescue.message("Caracol").startsWith("Este aparato no puede"))
    }
}
