package com.arkiv.player.dlna

import java.util.Locale

/**
 * Puts a DLNA renderer at the cast's start point ([com.arkiv.player.cast.CastStart], the rule
 * shared with the Chromecast): the DLNA half of "never 0:00 when the person was elsewhere". Pure.
 *
 * The Chromecast is LOADED at a position; a DLNA renderer cannot be: `SetAVTransportURI` + `Play`
 * always start at the top. So once it plays, it gets the standard AVTransport `Seek` (`REL_TIME`,
 * `H:MM:SS`), which is protocol-specific. Before this, every DLNA cast started at 0:00.
 *
 * Asked on every poll of the cast's monitor until it settles: wait for the renderer to play, seek,
 * retry ONCE if it refused, then leave it playing where it is and say so in the log.
 */
internal object DlnaSeek {

    /** A start point closer to the top than this is the top: no `Seek` for it. */
    const val MIN_START_MS = 3_000L

    /** A renderer already this close to the start point is there (an HLS `#EXT-X-START` it honoured). */
    const val ON_TARGET_MS = 15_000L

    /**
     * Some renderers stay TRANSITIONING (an LG reports LG_TRANSITIONING) while buffering for a
     * while: past this, a `Seek` is tried there too instead of waiting for PLAYING.
     */
    const val TRANSITIONING_SEEK_AFTER_MS = 10_000L

    /** Not playing yet this long after `Play`: give up on positioning it (the diagnosis judges the rest). */
    const val GIVE_UP_AFTER_MS = 120_000L

    /** The first try and ONE retry. */
    const val MAX_ATTEMPTS = 2

    enum class Step {
        /** Nothing to do: no start point, or already settled. */
        NONE,

        /** Not playing yet: ask again on the next poll. */
        WAIT,

        /** Send the `Seek` now. */
        SEEK,

        /** The renderer already reports a position at the start point. */
        ALREADY_THERE,

        /** It never got to play within [GIVE_UP_AFTER_MS]. */
        GIVE_UP,
    }

    /**
     * What to do on this poll. [state] is `CurrentTransportState`, [positionMs] the renderer's
     * `RelTime` (null when it does not report one), [attempts] the `Seek`s already sent.
     */
    fun next(targetMs: Long, state: String?, positionMs: Long?, sincePlayMs: Long, attempts: Int): Step {
        if (targetMs < MIN_START_MS || attempts >= MAX_ATTEMPTS) return Step.NONE
        if (sincePlayMs > GIVE_UP_AFTER_MS) return Step.GIVE_UP
        val s = state?.uppercase() ?: return Step.WAIT
        val playing = s == "PLAYING" || s == "PAUSED_PLAYBACK"
        val loadingLong = s.endsWith("TRANSITIONING") && sincePlayMs >= TRANSITIONING_SEEK_AFTER_MS
        if (!playing && !loadingLong) return Step.WAIT
        if (positionMs != null && kotlin.math.abs(positionMs - targetMs) <= ON_TARGET_MS) return Step.ALREADY_THERE
        return Step.SEEK
    }

    /** A `Seek` that lands this close to its target is there: no correction. */
    const val LANDED_WITHIN_MS = 10_000L

    /** Corrective `Seek`s after the first one, at most. */
    const val MAX_CORRECTIONS = 2

    /**
     * Where to `Seek` next after one landed at [reportedMs] (the renderer's first `RelTime` once it
     * plays again) for [targetMs], having asked for [sentMs]; null when it is there (within
     * [LANDED_WITHIN_MS]) or [corrections] ran out. Some renderers seek a file by a byte estimate
     * or back to an earlier index point: an LG webOS on a direct MPEG-TS landed 75-77 s before every
     * target (2026-10-01: 977 → 900, 979 → 902, 950 → 877). That miss is the renderer's, steady
     * near one point, so asking for the target plus what it missed by lands on it; from the second
     * correction on, what was asked last moves by the miss left, so it converges either way.
     */
    fun correction(targetMs: Long, sentMs: Long, reportedMs: Long, corrections: Int): Long? {
        if (kotlin.math.abs(reportedMs - targetMs) <= LANDED_WITHIN_MS) return null
        if (corrections >= MAX_CORRECTIONS) return null
        return (sentMs + (targetMs - reportedMs)).coerceAtLeast(0L)
    }

    /** [ms] as the `REL_TIME` target UPnP AVTransport takes: `H:MM:SS`, hours unpadded. */
    fun relTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }
}
