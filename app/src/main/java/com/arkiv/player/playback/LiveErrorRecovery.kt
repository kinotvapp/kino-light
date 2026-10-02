package com.arkiv.player.playback

/** The live playback errors that a fresh look at the playlist fixes without opening the channel from scratch. */
internal enum class LiveErrorKind {
    /** The player fell behind the oldest segment the playlist still lists. */
    BEHIND_LIVE_WINDOW,

    /** The playlist went backwards or was replaced by an unrelated one. */
    PLAYLIST_RESET,

    /** The playlist stopped advancing for longer than the player tolerates. */
    PLAYLIST_STUCK,

    /**
     * The player sat READY and playing with its clock frozen for 10 s (Media3's stuck-playing detector,
     * ERRORES-AKD): the data is there, a renderer stopped taking it. A re-prepare at the live edge gets it
     * moving; a repeat on the same channel swaps its hardware decoder for software ([stuckSwitchesDecoder]).
     */
    STUCK_PLAYING,

    /** Anything else: the CDN said no, the connection dropped, the data was garbage. */
    OTHER,
    ;

    /** Seek to the live edge and prepare again, as ExoPlayer's own documentation asks for. */
    val recoverableInPlace: Boolean get() = this != OTHER
}

/**
 * How often the in-place recovery may be tried before the error is handed to the full reopen instead.
 *
 * Measured in GlitchTip: these three errors were ~40% of the "Source error" reports of live channels, and the app
 * answered every one by re-resolving the whole session (a 2 s wait, then up to 11 s to resolve, three tries, then "the
 * signal was cut"). Viewers reported cuts and a spinner that only went away by leaving and coming back. A channel
 * that keeps falling behind right after a recovery is a broken feed, not a hiccup: past [max] tries in [windowMs] it
 * is the reopen's job.
 */
internal class InPlaceRecoveryBudget(private val max: Int = MAX, private val windowMs: Long = WINDOW_MS) {

    private val recent = ArrayDeque<Long>()

    /** Takes one try if there is one left. */
    fun tryConsume(nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() > windowMs) recent.removeFirst()
        if (recent.size >= max) return false
        recent.addLast(nowMs)
        return true
    }

    companion object {
        const val MAX = 3
        const val WINDOW_MS = 60_000L
    }
}

/**
 * A stuck-playing error ([LiveErrorKind.STUCK_PLAYING]) repeated on a channel ([repeated]) a hardware decoder
 * plays: the re-prepare did not help, so the decoder is swapped for software, once (a software player never
 * switches again; past that the in-place recovery and the reopen take over as for any other error).
 */
internal fun stuckSwitchesDecoder(kind: LiveErrorKind, repeated: Boolean, software: Boolean): Boolean =
    kind == LiveErrorKind.STUCK_PLAYING && repeated && !software

/**
 * Which live playback errors mean "the HARDWARE decoder is the problem" and so call for the one-time software rescue.
 *
 * ERRORES-B29: a Realtek TV (`OMX.realtek.video.decoder`, the KALLEY's family) threw `MediaCodec$CodecException:
 * Error 0x80001009` on a live TS channel. Only the `NO_EXCEEDS_CAPABILITIES` text used to trigger the rescue, so a
 * decoder that dies on the stream with any other message went straight to the reopen loop and hit the same wall
 * every time. Any decoder-level error (the `ERROR_CODE_DECODER_*` / `DECODING_*` codes, or a `CodecException`
 * somewhere in the causes) on a decoder that is not already software is that same case.
 */
internal object LiveDecoderRescue {

    private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.", "omx.ffmpeg.", "c2.google.")

    /** True for a software decoder's name (an unknown name is assumed hardware). */
    fun isSoftwareDecoder(name: String): Boolean = name.lowercase().let { n -> SOFTWARE_PREFIXES.any { n.startsWith(it) } }

    /**
     * @param errorCode the `PlaybackException` code.
     * @param decoderFailureInCauses whether a `MediaCodec.CodecException` / `MediaCodecDecoderException` is in the causes.
     * @param alreadySoftware this channel already opened in software (the rescue fires once per channel).
     * @param videoDecoder the video decoder's name when known, "" otherwise.
     */
    fun shouldRescue(errorCode: Int, decoderFailureInCauses: Boolean, alreadySoftware: Boolean, videoDecoder: String = ""): Boolean {
        if (alreadySoftware || isSoftwareDecoder(videoDecoder)) return false
        return errorCode in DECODER_CODES || decoderFailureInCauses
    }

    /**
     * What `LiveExoPlayer` hands `onError` when even the software decoder failed: the view model shows [message]
     * at once instead of reopening (a reopen hits the same wall).
     */
    const val GAVE_UP = "decoder failed in software too"

    fun message(channelName: String): String =
        "Este aparato no puede reproducir $channelName. Prueba con otro canal."

    // ERROR_CODE_DECODER_INIT_FAILED (4001) .. ERROR_CODE_DECODING_RESOURCES_RECLAIMED (4006).
    private val DECODER_CODES = 4001..4006
}
