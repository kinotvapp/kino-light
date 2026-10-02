package com.arkiv.player.dlna

/**
 * Decides, from what the renderer reports after `Play`, whether the cast has failed and HOW. Pure, so
 * every pattern is pinned by a test.
 *
 * A DLNA renderer that accepts `SetAVTransportURI` and `Play` with a 200 has promised nothing: it fetches
 * the media afterwards, and rejects it asynchronously (STOPPED, ERROR_OCCURRED, or just never leaving
 * TRANSITIONING). Those failures are invisible from the SOAP replies alone, so the controller keeps
 * asking the renderer how it's doing and feeds the answers here.
 *
 * The stage names are the GlitchTip grouping keys (one issue per stage), so they're stable strings.
 * The single most useful split is "did the TV ever ask our server for the media?":
 *  - it did NOT  -> reachability (wrong IP, client isolation, firewall) or the TV never tried a URL it dislikes;
 *  - it DID, then stopped -> what we serve it (container, codec, headers, MIME) is what it dislikes.
 */
internal object DlnaDiagnosis {

    const val TRANSPORT_ERROR = "transport_error"
    const val NEVER_FETCHED = "renderer_never_fetched"
    const val STOPPED_EARLY = "stopped_early"
    const val STUCK_LOADING = "stuck_loading"
    const val POSITION_STALLED = "position_stalled"

    /**
     * Accepted and still not playing (never PLAYING, no position past 0:00) this long after `Play`,
     * sitting STOPPED, NO_MEDIA_PRESENT or loading: a load that will not start. The stages above
     * catch most of these sooner; this is the time limit behind them.
     */
    const val NEVER_PLAYED = "never_played"
    const val NEVER_PLAYED_AFTER_MS = 60_000L

    /** Right after `Play` a renderer may still report the state it had before: don't judge until it settled. */
    const val SETTLE_MS = 3_000L

    /** No request from the renderer for this long after `Play`, and it isn't playing: it never went to fetch. */
    const val NEVER_FETCHED_AFTER_MS = 15_000L

    /** A STOPPED this soon after `Play` is a rejection; later, it may just be the end of a short video. */
    const val EARLY_STOP_WINDOW_MS = 45_000L

    /** Still TRANSITIONING (loading) this long after `Play`, having requested the media. */
    const val STUCK_LOADING_AFTER_MS = 25_000L

    /** A reported position that hasn't moved for this long while PLAYING. */
    const val STALL_AFTER_MS = 8_000L

    data class Snapshot(
        /** Time since `Play` was accepted. */
        val sincePlayMs: Long,
        /** `CurrentTransportState` (PLAYING, STOPPED, TRANSITIONING, PAUSED_PLAYBACK, NO_MEDIA_PRESENT), null if the poll failed. */
        val state: String?,
        /** `CurrentTransportStatus` (OK, ERROR_OCCURRED). */
        val status: String?,
        /** Requests the renderer has made to OUR servers since `Play`. */
        val lanHits: Int,
        /** We sent `Pause` ourselves. */
        val userPaused: Boolean,
        /** How long a position the renderer DOES report hasn't advanced while PLAYING (0 when it doesn't report one). */
        val stalledMs: Long,
        /** The renderer was PLAYING at some point, or reported a position past 0:00. */
        val everPlayed: Boolean = true,
    )

    /** The failure stage, or null when nothing is wrong (yet). */
    fun failure(s: Snapshot): String? {
        if (s.status.equals("ERROR_OCCURRED", ignoreCase = true)) return TRANSPORT_ERROR
        val state = s.state?.uppercase() ?: return null
        val stopped = state == "STOPPED" || state == "NO_MEDIA_PRESENT"
        if (stopped && s.sincePlayMs in SETTLE_MS..EARLY_STOP_WINDOW_MS) {
            return if (s.lanHits == 0) NEVER_FETCHED else STOPPED_EARLY
        }
        if (s.lanHits == 0 && s.sincePlayMs >= NEVER_FETCHED_AFTER_MS && state != "PLAYING") return NEVER_FETCHED
        // A vendor may prefix it (an LG webOS reports LG_TRANSITIONING while it loads).
        if (state.endsWith("TRANSITIONING") && s.lanHits > 0 && s.sincePlayMs >= STUCK_LOADING_AFTER_MS) return STUCK_LOADING
        if (state == "PLAYING" && !s.userPaused && s.stalledMs >= STALL_AFTER_MS) return POSITION_STALLED
        if (!s.everPlayed && !s.userPaused && s.sincePlayMs >= NEVER_PLAYED_AFTER_MS && (stopped || state.endsWith("TRANSITIONING"))) {
            return NEVER_PLAYED
        }
        return null
    }

    /**
     * A `Play` the renderer has not answered yet ([noHttpAnswer]: a timeout, not a refusal) while it is already fetching our media
     * ([tvRequests] > 0). An LG webOS answers `Play` only once it has enough media, and with a slow CDN that took longer than the
     * wait: the TV was loading and then played, and the cast used to be dropped as failed. The monitor judges it from here on.
     */
    fun playStillLoading(noHttpAnswer: Boolean, tvRequests: Int): Boolean = noHttpAnswer && tvRequests > 0

    /** What to tell the person, in Spanish, for each stage. */
    fun userMessage(stage: String): String = when (stage) {
        TRANSPORT_ERROR -> "La TV reportó un error al reproducir"
        NEVER_FETCHED -> "La TV no llegó a pedir el video (revisa que estén en la misma red WiFi)"
        STOPPED_EARLY -> "La TV pidió el video pero lo detuvo (formato no soportado)"
        STUCK_LOADING -> "La TV se quedó cargando el video"
        POSITION_STALLED -> "La reproducción en la TV se detuvo"
        NEVER_PLAYED -> "La TV no logró empezar a reproducir el video"
        else -> "No se pudo reproducir en la TV"
    }
}
