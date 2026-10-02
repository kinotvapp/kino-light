package com.arkiv.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * A video format beyond every decoder on the device (ERRORES-AML: a live TS on a moto g52 declares
 * hvc1 level 6 at 7680x4320, `OMX.qcom.video.decoder.hevc` refuses it with
 * `format_supported=NO_EXCEEDS_CAPABILITIES`). Decoder fallback is on ([fallbackRenderers]), so by
 * the time this error arrives every decoder that lists the MIME type was already tried: a reopen,
 * a playlist refresh or a software decoder hit the same wall. What still helps is a smaller
 * variant of the same stream (an HLS master's lower rungs); with none, the person is told the
 * channel's format is beyond the device instead of watching it reopen and fail.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object DecoderCapability {
    const val UNSUPPORTED_MESSAGE = "Este canal usa un formato que tu dispositivo no puede reproducir. Prueba con otro canal."

    /** The video format a renderer error rated beyond the device's decoders, or null for any other error. */
    fun exceededVideoFormat(error: PlaybackException): Format? {
        val e = error as? ExoPlaybackException ?: return null
        if (e.type != ExoPlaybackException.TYPE_RENDERER || e.rendererFormatSupport != C.FORMAT_EXCEEDS_CAPABILITIES) return null
        return e.rendererFormat?.takeIf { MimeTypes.isVideo(it.sampleMimeType) }
    }

    /**
     * The largest variant in [variants] smaller than [failed] (and than [previousCap], a cap already
     * tried that still failed), as the max video size to select under; null when there is none.
     * Strictly shrinking, so repeated failures always end in null.
     */
    fun capBelow(failed: Format, variants: List<Format>, previousCap: Pair<Int, Int>?): Pair<Int, Int>? {
        var maxW = if (failed.width > 0) failed.width else Int.MAX_VALUE
        var maxH = if (failed.height > 0) failed.height else Int.MAX_VALUE
        if (previousCap != null) {
            maxW = minOf(maxW, previousCap.first)
            maxH = minOf(maxH, previousCap.second)
        }
        if (maxW == Int.MAX_VALUE && maxH == Int.MAX_VALUE) return null
        val limit = maxW.toLong() * maxH
        return variants
            .filter { it.width in 1..maxW && it.height in 1..maxH && it.width.toLong() * it.height < limit }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { it.width to it.height }
    }
}
