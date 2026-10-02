package com.arkiv.player.dlna

import com.arkiv.player.cast.CastAudio
import com.arkiv.player.cast.CastAudioRoute
import com.arkiv.player.cast.CastAudioSwitch
import com.arkiv.player.cast.CastStart

/**
 * What another audio picked on the phone does to a DLNA cast in progress -- the Chromecast's rules
 * ([CastAudio], [CastStart]) with the facts a DLNA cast has. Pure.
 *
 * - A remux route (`vod-remux-hls`, the growing HLS; `vod-remux`, the whole MP4): a remux carries
 *   ONE audio track, so the title is remuxed again with the new one and sent again from where the
 *   TV was.
 * - Anything else (the TS or MP4 proxied as it is, a raw URL, a live channel): the TV plays the
 *   file's default audio and AVTransport has no action to change it, so the change stays on the
 *   phone and the person is told so. Moving a TV that plays the file as it is onto a remux just for
 *   the audio is left out on purpose: that route was chosen because the TV plays the file directly,
 *   and trading it for a remux mid-cast (minutes of export, a new route the TV may refuse) is a bet
 *   the person did not ask for.
 */
object DlnaAudioSwitch {

    /** The cast kinds ([DlnaController.activeKind]) that are a remux made on this phone. */
    private val REMUX_KINDS = setOf("vod-remux-hls", "vod-remux")

    fun routeOf(kind: String?): CastAudioRoute =
        if (kind in REMUX_KINDS) CastAudioRoute.REMUX else CastAudioRoute.FIXED

    /** [CastAudio.onChoiceChanged] for the DLNA cast of [kind]; null [kind] = nothing on the TV. */
    fun onChoiceChanged(kind: String?, onTv: Int?, wanted: Int?): CastAudioSwitch =
        CastAudio.onChoiceChanged(casting = kind != null, route = routeOf(kind), onTv = onTv, wanted = wanted)

    /**
     * Where the TV is, for a re-send to continue there, or null when it cannot be told:
     * - a cast whose start `Seek` has not happened yet ([seekSettled] false) is about to be put at
     *   [castStartMs]; the TV's own clock still reads the top meanwhile, and believing it restarted
     *   the title at 0:00;
     * - otherwise the TV's [reportedMs] (asked right now), else the last position the monitor read,
     *   else where the cast was sent to start. A 0 is no answer: a TV that does not know its position
     *   says 0:00, and one that really is at 0:00 loses nothing by falling through.
     */
    fun tvPositionMs(reportedMs: Long?, lastKnownMs: Long?, castStartMs: Long, seekSettled: Boolean): Long? = when {
        !seekSettled && castStartMs > 0 -> castStartMs
        else -> reportedMs?.takeIf { it > 0 } ?: lastKnownMs?.takeIf { it > 0 } ?: castStartMs.takeIf { it > 0 }
    }

    /**
     * Where the re-sent cast starts: the TV's position first, then the phone's (sat paused where the
     * cast began), then where the item opened ([CastStart.resumePointMs]), as a remux reload
     * ([CastAudio.reloadStartMs]). Never 0:00 when the TV was elsewhere.
     */
    fun startMs(tvMs: Long?, phoneMs: Long?, itemStartMs: Long): Long =
        CastAudio.reloadStartMs(CastAudioRoute.REMUX, CastStart.resumePointMs(tvMs, phoneMs, itemStartMs))
}
