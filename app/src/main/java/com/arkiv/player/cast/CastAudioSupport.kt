package com.arkiv.player.cast

import androidx.media3.common.MimeTypes

/**
 * What audio the Chromecast receiver can decode on its own.
 *
 * Exists because the failure is SILENT: if the receiver doesn't know the codec, it plays the
 * video and no sound comes out, without a single error on either the phone or the TV. It happened
 * with AC-3 (Avatar) and DTS (Naruto), both H.264 -- that's why the picture showed up fine.
 *
 * The list comes from Cast's own docs (https://developers.google.com/cast/docs/media): AAC, MP3,
 * Opus, Vorbis, FLAC and LPCM. Chromecast does NOT decode AC-3 and E-AC-3 itself, it only passes
 * them through over HDMI for the TV to decode, and that has to be enabled separately; DTS and
 * TrueHD aren't supported at all.
 *
 * There is no transcoder any more: when the receiver can't handle the audio, it still gets cast
 * and a warning is shown (see `castRequestFor` in `PlayerScreen.kt`). This object only decides the
 * warning, not a fallback.
 */
object CastAudioSupport {

    /**
     * The AAC family. Cast lists it as supported, but it falls over with more than 2 channels:
     * VLC's own Chromecast module forbids it explicitly ("Disallow multichannel AAC") and Jellyfin
     * had to fix that exact same thing.
     */
    private val AAC = setOf(MimeTypes.AUDIO_AAC)

    /** What the receiver decodes no matter how many channels it carries. */
    private val DECODABLE = setOf(
        MimeTypes.AUDIO_MPEG,
        MimeTypes.AUDIO_MPEG_L1,
        MimeTypes.AUDIO_MPEG_L2,
        MimeTypes.AUDIO_OPUS,
        MimeTypes.AUDIO_VORBIS,
        MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_RAW,
    )

    /**
     * Does the receiver decode this audio as-is?
     *
     * It's a whitelist: anything we don't recognize is NOT assumed decodable. Without a transcoder
     * there is nowhere left to fall back to, so this only decides whether the "might play mute"
     * warning is needed, never whether the item gets cast. The only exception is a null
     * `sampleMimeType`: the track hasn't been parsed yet, and forcing the warning there would break
     * today's path for sources that work fine.
     */
    fun receiverDecodes(sampleMimeType: String?, channelCount: Int): Boolean = when (sampleMimeType) {
        null -> true
        in AAC -> channelCount <= 2
        in DECODABLE -> true
        else -> false
    }
}
