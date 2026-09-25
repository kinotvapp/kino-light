package com.arkiv.player.playback

/**
 * Which video decoder a live channel gets first.
 *
 * Samsung's Exynos hardware H.264 decoder (`c2.exynos.h264.decoder`, `OMX.Exynos.*`) cannot take the live channels'
 * encoding: the picture holds for ~2 s after the decoder starts and then the chip reports `input is corrupted` on
 * every few frames, showing one band of the frame with green and black blocks while the audio plays fine
 * (measured on a Galaxy S24+ / Exynos 2400, every live session; the same segments decode with no error in
 * ffmpeg, so the stream is well formed). Films and series, encoded differently, decode fine on the same chip.
 * Their SPS uses picture order count type 1, explicit weighted prediction and adaptive B-frames.
 *
 * The channels are standard definition (1024x576, 25 fps), so a software decoder costs about half a core on that
 * phone. Only this decoder family is moved: any other device keeps its hardware decoder first.
 */
internal object LiveDecoderPolicy {

    private const val AVC = "video/avc"

    fun isExynos(decoderName: String): Boolean =
        decoderName.startsWith("c2.exynos.", ignoreCase = true) || decoderName.startsWith("OMX.Exynos.", ignoreCase = true)

    /**
     * [decoders] in the order the system offers them; returned with software decoders first when the first is an
     * Exynos one, or when [forceSoftware] says this channel already failed in hardware (see [LiveDecoderMemory]).
     */
    fun <T> order(
        mimeType: String,
        decoders: List<T>,
        nameOf: (T) -> String,
        isHardware: (T) -> Boolean,
        forceSoftware: Boolean = false,
    ): List<T> {
        if (mimeType != AVC) return decoders
        val first = decoders.firstOrNull() ?: return decoders
        if (!forceSoftware && !isExynos(nameOf(first))) return decoders
        return LocalExoPlayer.softwareFirst(decoders, isHardware)
    }
}
