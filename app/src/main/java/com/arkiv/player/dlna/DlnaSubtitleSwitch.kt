package com.arkiv.player.dlna

/**
 * What another subtitle picked on the phone does to a DLNA cast in progress. Pure.
 *
 * A renderer has no AVTransport action to switch subtitles mid-play: they only travel with the
 * video, in the DIDL-Lite and the video's headers ([DlnaSubtitles]). So a different choice means
 * sending the same video again, with the new subtitle (or none: "Desactivados" sends no subtitle
 * fields at all), from where the TV is -- the audio switch's reload ([DlnaAudioSwitch]).
 */
object DlnaSubtitleSwitch {

    enum class Action {
        /** Nothing to do on the TV: no cast, a live channel, or the TV already has that subtitle. */
        NONE,

        /** Pause the TV, send the cast again with the new subtitle and seek it back where it was. */
        RESEND,

        /**
         * Another audio is being prepared for the TV: its send reads the subtitles then, so this one
         * goes along with it instead of a reload of the audio about to be replaced.
         */
        WITH_AUDIO,
    }

    /** The cast kinds ([DlnaController.activeKind]) that never carry subtitles: a live channel. */
    private val LIVE_KINDS = setOf("live-hls")

    /**
     * [kind]: the cast in progress, null with none. [onTv]: the subtitle it was sent with, [wanted]:
     * the one the phone has on now (each a URL on the phone's subtitle server; null = none).
     * [audioPending]: another audio is being prepared for the TV.
     */
    fun onChoiceChanged(kind: String?, onTv: String?, wanted: String?, audioPending: Boolean): Action = when {
        kind == null || kind in LIVE_KINDS -> Action.NONE
        onTv == wanted -> Action.NONE
        audioPending -> Action.WITH_AUDIO
        else -> Action.RESEND
    }
}
