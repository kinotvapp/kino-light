package com.arkiv.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERRORES-AML: a video beyond the device's decoders picks a smaller variant, or says so. */
class DecoderCapabilityTest {
    private fun video(w: Int, h: Int, mime: String = MimeTypes.VIDEO_H265) =
        Format.Builder().setSampleMimeType(mime).setWidth(w).setHeight(h).build()

    private fun rendererError(format: Format, support: Int, code: Int = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) =
        ExoPlaybackException.createForRenderer(RuntimeException("Decoder init failed"), "MediaCodecVideoRenderer", 0, format, support, false, code)

    @Test
    fun `only a renderer error over a video the decoders rated beyond them counts`() {
        val eightK = video(7680, 4320)
        assertSame(eightK, DecoderCapability.exceededVideoFormat(rendererError(eightK, C.FORMAT_EXCEEDS_CAPABILITIES)))
        assertNull(DecoderCapability.exceededVideoFormat(rendererError(eightK, C.FORMAT_HANDLED)))
        assertNull(DecoderCapability.exceededVideoFormat(rendererError(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(), C.FORMAT_EXCEEDS_CAPABILITIES)))
        assertNull(DecoderCapability.exceededVideoFormat(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
    }

    @Test
    fun `the largest smaller variant is chosen, and every retry shrinks until nothing is left`() {
        val failed = video(7680, 4320)
        val variants = listOf(failed, video(3840, 2160), video(1920, 1080), video(1280, 720))
        val first = DecoderCapability.capBelow(failed, variants, null)
        assertEquals(3840 to 2160, first)
        // The 4K one failed too (its own SPS, or the same 8K picture behind a smaller label).
        val second = DecoderCapability.capBelow(video(3840, 2160), variants, first)
        assertEquals(1920 to 1080, second)
        val third = DecoderCapability.capBelow(failed, variants, second)
        assertEquals(1280 to 720, third)
        assertNull(DecoderCapability.capBelow(failed, variants, third))
    }

    @Test
    fun `a single variant, or unknown sizes, leave nothing to fall back to`() {
        val failed = video(7680, 4320)
        assertNull(DecoderCapability.capBelow(failed, listOf(failed), null))
        assertNull(DecoderCapability.capBelow(video(Format.NO_VALUE, Format.NO_VALUE), listOf(video(1280, 720)), null))
        assertNull(DecoderCapability.capBelow(failed, listOf(video(Format.NO_VALUE, Format.NO_VALUE)), null))
    }

    @Test
    fun `the live player tells the screen instead of reopening, and the message is in Spanish`() {
        assertTrue(DecoderCapability.UNSUPPORTED_MESSAGE.startsWith("Este canal usa un formato que tu dispositivo no puede reproducir"))
        val src = File("src/main/java/com/arkiv/player/ui/player/LiveExoPlayer.kt").readText()
        val branch = src.substringAfter("DecoderCapability.exceededVideoFormat(error)").substringBefore("val kind = liveErrorKind(error)")
        assertTrue(branch.contains("setMaxVideoSize(cap.first, cap.second)"))
        assertTrue(branch.indexOf("onUnsupportedFormat()") in 0 until branch.indexOf("rescueInSoftware"))
    }
}
