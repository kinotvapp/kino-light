package com.arkiv.player.playback

/**
 * Whether the screen needs to stay covered while waiting for a media's FIRST frame.
 *
 * Exists because of "starts black with sound": libVLC used to reach `Playing` and let the audio
 * go as soon as it had something to play, but the first frame can take a lot longer -- the Fire
 * Stick's HEVC decoder has to spin up -- and in that gap `playbackState` is already NOT
 * `STATE_BUFFERING`. The screen would then sit with no spinner AND no image: plain black with
 * audio, which from the couch looks exactly like a freeze. Measured on 2026-08-13 on the Fire TV,
 * the gap between the first frame and the video actually moving reached 8.5s.
 *
 * It's a pure function because here the failure mode is leaving the spinner sitting ON TOP of a
 * video that was already playing, and that's worse than the black screen it came to cover. The
 * edges are pinned by test.
 */
object FirstFrameWait {

    /**
     * The most it waits.
     *
     * Longer than [DecoderWatchdog]'s hardware→software rescue on purpose: the case this spinner
     * covers is exactly that one -the seconds of black waiting for the decoder and the software
     * reload that does give a picture-, so cutting off sooner would leave right at the worst moment.
     */
    const val CAP_MS = 30_000L

    /**
     * How far BEFORE the requested point a seek can land and still count as "arrived".
     *
     * A seek lands on the keyframe before the requested point, so requiring `position >= requested`
     * would leave the spinner sitting over a video that already started fine. Measured on the Fire
     * TV on 2026-08-14: 1327653 ms was requested and it landed at 1327116, i.e. 537 ms early. 10 s
     * covers any reasonable GOP size with room to spare without ending up covering a seek that
     * actually went wrong.
     */
    const val LANDING_MARGIN_MS = 10_000L

    /**
     * @param loadedMsAgo since the media was loaded (negative = none loaded yet).
     * @param hadFrame whether this media has already produced any frame.
     * @param videoTracks how many video tracks the media declares (0 = doesn't know yet, or none).
     * @param audioTracks same, for audio.
     * @param requestedMs the point resume was requested at (0 = none was requested).
     * @param positionMs where the player's clock is right now.
     */
    @Suppress("LongParameterList")
    fun shouldWait(
        loadedMsAgo: Long,
        hadFrame: Boolean,
        videoTracks: Int,
        audioTracks: Int,
        requestedMs: Long = 0L,
        positionMs: Long = 0L,
    ): Boolean {
        if (loadedMsAgo < 0L) return false          // no media loaded
        if (loadedMsAgo > CAP_MS) return false       // past the cap: better black than an endless spinner
        // RESUME STILL LANDING: there IS a frame, but it's NOT the one for the requested point.
        //
        // Measured on the Fire TV on 2026-08-14: `:start-time` didn't open at the saved minute.
        // VLC opened at byte 0, pulled a frame from there (`⏱ abrió en 1025ms`) and only THEN
        // jumped -- the `PAUSA (buffering) en pos=0ms` that follows lasts 1.5s. That first frame
        // turned on `hadFrame` and switched off the spinner, so the user was left staring at a
        // frozen frame FROM THE BEGINNING, with audio already playing, until the seek landed. It
        // looks exactly like a freeze and shows the wrong content on top of it.
        //
        // It comes BEFORE the "there was already a frame" cutoff precisely because the case is
        // "there was a frame, but not the right one". The original player covers this same gap:
        // its seek turns the spinner on the instant it's requested.
        if (requestedMs > 0L && positionMs < requestedMs - LANDING_MARGIN_MS) return true
        if (hadFrame) return false                    // there was already a frame: not this one's business
        // Content WITH NO VIDEO: there's no image to wait for.
        //
        // It asks "is there audio and no video" and not "the list is empty", and that difference is
        // the whole point: on opening, EVERY count is 0 -and that doesn't mean there's no video, it
        // means the player doesn't know yet-. That's exactly the instant this spinner has to be up.
        if (videoTracks <= 0 && audioTracks > 0) return false
        return true
    }
}
