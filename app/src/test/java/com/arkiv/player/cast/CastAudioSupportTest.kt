package com.arkiv.player.cast

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Chromecast receiver decodes a small set of audio codecs. Whatever isn't in it fails in
 * SILENCE: the TV shows the video and nothing sounds, with not a single error. These tests pin
 * what gets sent through as-is and what doesn't -- the same decisions the previous fourcc-based
 * libVLC gate made, now read straight off ExoPlayer's `Format`.
 */
class CastAudioSupportTest {

    @Test
    fun `AC-3 is not decoded by the receiver`() {
        // The Avatar case: the picture showed up and there was no sound.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AC3, channelCount = 6))
    }

    @Test
    fun `neither is E-AC-3`() {
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_E_AC3, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_E_AC3_JOC, channelCount = 6))
    }

    @Test
    fun `neither is DTS, not even passthrough`() {
        // The Naruto case. Cast doesn't support it in any form.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS_HD, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS_EXPRESS, channelCount = 6))
    }

    @Test
    fun `neither is TrueHD`() {
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_TRUEHD, channelCount = 8))
    }

    @Test
    fun `stereo AAC goes direct`() {
        // AAC stereo -the shape Magis downloads carry today- decodes on the receiver directly.
        assertTrue(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AAC, channelCount = 2))
    }

    @Test
    fun `multichannel AAC needs the might-play-mute warning`() {
        // Cast lists AAC as supported but fails with 5.1: VLC's own Chromecast module forbids it
        // explicitly, and Jellyfin had to fix that exact same thing.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AAC, channelCount = 6))
    }

    @Test
    fun `MP3, Opus, Vorbis, FLAC and PCM go direct`() {
        listOf(
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_MPEG_L1,
            MimeTypes.AUDIO_MPEG_L2,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_RAW,
        ).forEach { mime ->
            assertTrue(mime, CastAudioSupport.receiverDecodes(mime, channelCount = 2))
        }
    }

    @Test
    fun `an unknown codec is marked as might play mute`() {
        // A mime the gate has never seen (a new codec, say). There's no transcode to fall back
        // to: it still gets cast, but the whitelist decides it might play mute.
        assertFalse(CastAudioSupport.receiverDecodes("audio/x-something-new", channelCount = 2))
    }

    @Test
    fun `with no track information yet, today's path is kept`() {
        // mime null = the track couldn't be read yet. Keeps "don't know -> send direct".
        assertTrue(CastAudioSupport.receiverDecodes(sampleMimeType = null, channelCount = 0))
    }
}
