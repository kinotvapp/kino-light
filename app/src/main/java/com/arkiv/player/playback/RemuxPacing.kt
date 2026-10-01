package com.arkiv.player.playback

/**
 * How far the cast's remux may run ahead of the TV before it is paused. Pure.
 *
 * Why it exists, measured 2026-10-01 on the KALLEY over 2.4 GHz Wi-Fi (Redmi → Chromecast built
 * in, Xuper `.ts`): the remux pulled the title from the CDN at ~12x realtime over the SAME radio
 * that uploads the segments to the TV, and the download took the airtime. 10 s segments (~1.2 MB)
 * reached the TV in 2.4-13 s instead of a fraction of one, the TV gave up on them (broken pipe) and
 * buffered for ever. The owner keeps 2.4 GHz, so the fix cannot be "use 5 GHz": the phone simply
 * stops downloading once it holds enough, and the radio is the TV's.
 *
 * The remux is paused once it is [MAX_LEAD_SEC] past where the TV is (the start of the last
 * segment it asked for, or where the cast was told to start before it asks anything) and resumed
 * when that lead falls to [RESUME_LEAD_SEC]: short bursts every ~20 s of playback instead of a
 * continuous 12x download. Before the cast starts (nothing planned) it is never paused: that is the
 * remux racing to the person's position, with nothing on the TV to starve.
 */
object RemuxPacing {

    /**
     * Seconds of remuxed title kept ready past the TV. Three minutes: far more than any Wi-Fi hiccup
     * the receiver has to ride out (its own buffer is ~30-60 s), and at the bitrates measured
     * (~1 Mbit/s HEVC 720p, ~1.2 MB per 10 s) only ~22 MB on disk ahead of it.
     */
    const val MAX_LEAD_SEC = 180.0

    /** Once paused, the remux runs again when the lead falls to this (~20 s, ~2.5 MB per burst). */
    const val RESUME_LEAD_SEC = 160.0

    /**
     * The speed a remux is ASSUMED to catch up at when it is behind a reused earlier remux (the
     * head, see [RemuxHls.Splice]). Measured at ~12x on Xuper; assumed 3x so a slow CDN still gets
     * there before the TV runs off the end of the head.
     */
    const val CATCH_UP_SPEED = 3.0

    /**
     * How far behind where the TV is a segment request may land and still be taken at face value.
     * Further back is a probe until the next request confirms it: the receiver's Shaka fetches one
     * stray segment (s157 at 1377 s, with the cast starting at 2580 s, 2026-10-01) before the ones
     * it plays, and taking it as the TV's position paused the remux for nothing.
     */
    const val PROBE_BEHIND_SEC = 60.0

    /**
     * How close after a held-back request the next one has to land to confirm it: a real seek
     * backwards plays on, so its next request is the following segment or two.
     */
    const val CONFIRM_WITHIN_SEC = 30.0

    /** The TV's position as the pacing tracks it: see [onSegmentRequest]. */
    data class TvRequests(val lastRequestedSec: Double?, val heldBackSec: Double?)

    /**
     * The TV asked for the segment starting at [requestedSec]. Taken as its position unless it is
     * more than [PROBE_BEHIND_SEC] behind where it was ([lastRequestedSec], else [plannedStartSec]);
     * such a request is held ([heldBackSec]) and only taken when the next one follows it within
     * [CONFIRM_WITHIN_SEC] -- a seek backwards. An isolated probe changes nothing.
     */
    fun onSegmentRequest(
        lastRequestedSec: Double?,
        plannedStartSec: Double?,
        heldBackSec: Double?,
        requestedSec: Double,
    ): TvRequests {
        val anchor = lastRequestedSec ?: plannedStartSec ?: return TvRequests(requestedSec, null)
        if (requestedSec >= anchor - PROBE_BEHIND_SEC) return TvRequests(requestedSec, null)
        if (heldBackSec != null && requestedSec > heldBackSec && requestedSec - heldBackSec <= CONFIRM_WITHIN_SEC) {
            return TvRequests(requestedSec, null)
        }
        return TvRequests(lastRequestedSec, requestedSec)
    }

    /**
     * Should the remux wait now?
     *
     * @param anchorSec where the TV is in the title: the start of the last segment it requested,
     *   else where the cast was told to start; null while nothing of this remux is being cast.
     * @param mainSec how far THIS remux run has got (it always runs from 0:00).
     * @param playableSec how far what is served reaches (the head, then this run).
     * @param headEndSec end of the head still being served on its own, or 0 when there is none.
     * @param paused whether it is paused right now (the hysteresis).
     */
    fun shouldPause(
        anchorSec: Double?,
        mainSec: Double,
        playableSec: Double,
        headEndSec: Double,
        paused: Boolean,
    ): Boolean {
        anchorSec ?: return false
        val lead = playableSec - anchorSec
        if (lead < if (paused) RESUME_LEAD_SEC else MAX_LEAD_SEC) return false
        if (headEndSec > mainSec) {
            // The TV plays the head while this run catches up with it. It must reach the head's
            // end before the TV gets within MAX_LEAD of it, at no more than CATCH_UP_SPEED: it
            // works when that is getting tight and rests otherwise, which spreads the catch-up
            // over the playback instead of one long 12x burst.
            val playbackLeft = headEndSec - anchorSec - MAX_LEAD_SEC
            val workLeft = (headEndSec - mainSec) / CATCH_UP_SPEED
            if (workLeft >= playbackLeft) return false
        }
        return true
    }
}
