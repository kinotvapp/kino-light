package com.arkiv.player.dlna

import com.arkiv.player.cast.CastStrategy

/**
 * When a VOD file sent to the TV as it is (a bare MPEG-TS the TV's list mentions) gets rejected, the remux to a real MP4 is the
 * container every TV takes: worth one more try instead of leaving the person with "the TV rejected the video". Measured on an
 * LG webOS that listed `video/mp2t` and still answered `Play` with `HTTP 500 · upnp=501 Action Failed` after fetching the file.
 */
internal object DirectPlayFallback {
    /** UPnP AVTransport: 714 illegal MIME type, 716 resource not found (the renderer does not take that format). */
    private val SET_URI_REJECTIONS = setOf(714, 716)
    private const val ACTION_FAILED = 501

    /** [stage] is the failing step ("set_uri", "play"...); [upnpCode] the UPnP fault code, [http] the HTTP status of the SOAP reply. */
    fun shouldRemux(stage: String, upnpCode: Int?, http: Int): Boolean = when {
        http != 500 || upnpCode == null -> false
        stage == "play" -> upnpCode == ACTION_FAILED
        stage == "set_uri" -> upnpCode in SET_URI_REJECTIONS
        else -> false
    }

    /**
     * The renderer refused the growing remux as HLS, with a UPnP fault ([upnpCode], HTTP 500): it
     * does not take that playlist after all (listing HLS is no promise about fMP4 segments), so the
     * whole MP4 is worth the wait. A 701 left after the retries is a busy renderer, not a format
     * it refuses; no answer at all is not a refusal either.
     */
    fun wholeFileAfterHls(upnpCode: Int?, http: Int): Boolean =
        http == 500 && upnpCode != null && upnpCode != 701

    /**
     * The TV accepted a file sent as it is and then gave up on it on its own -- [stage] is
     * [DlnaDiagnosis.STOPPED_EARLY] or [DlnaDiagnosis.TRANSPORT_ERROR] -- without ever playing it
     * ([advanced] false: no position past 0:00). Measured: an LG webOS listing `video/mp2t` fetched
     * 39 MB of a TS in 6 requests over 21 s, then went STOPPED at 0:00 (ERRORES-AL7, 0.9.45) -- the
     * network was fine (15 Mbit/s at -68 dBm), the container was not. The remux is worth one try. A
     * file that played and then stopped is not the container's fault: reported as before.
     */
    fun remuxAfterEarlyStop(stage: String, advanced: Boolean): Boolean =
        !advanced && (stage == DlnaDiagnosis.STOPPED_EARLY || stage == DlnaDiagnosis.TRANSPORT_ERROR)

    /**
     * Whether a DLNA VOD cast moves on to the next stage of its chain ([DlnaRouteChain]) after the
     * renderer REFUSED [route] over SOAP ([stage] the step, [upnpCode], [http]). The TV's own URL
     * ([CastStrategy.Route.DIRECT]) goes through the proxy after any refusal with an HTTP error that
     * is not a busy renderer (701); a file as it is through the proxy only after the refusals a real
     * MP4 fixes ([shouldRemux]); the growing remux as HLS when [wholeFileAfterHls] says so. No HTTP
     * answer at all is a TV that is not there: nothing else would reach it either.
     */
    fun advancesOnRejection(route: CastStrategy.Route, stage: String, upnpCode: Int?, http: Int): Boolean = when (route) {
        CastStrategy.Route.DIRECT -> http in 400..599 && upnpCode != 701
        CastStrategy.Route.PROXY -> shouldRemux(stage, upnpCode, http)
        CastStrategy.Route.REMUX -> wholeFileAfterHls(upnpCode, http)
        else -> false
    }

    /**
     * Whether a DLNA VOD cast the renderer ACCEPTED moves on to the next stage when the monitor
     * judges it failed ([stage], a [DlnaDiagnosis] stage) without it ever playing ([advanced]
     * false): an early stop or a transport error ([remuxAfterEarlyStop]), a load that never reached
     * PLAYING in time ([DlnaDiagnosis.STUCK_LOADING], [DlnaDiagnosis.NEVER_PLAYED]), and -- only for
     * the TV's own URL, which it fetches from the Internet and never from the phone -- no request to
     * the phone ([DlnaDiagnosis.NEVER_FETCHED], expected there). A cast that played and then stalled
     * is the network's, not the route's.
     */
    fun advancesOnMonitor(route: CastStrategy.Route?, stage: String, advanced: Boolean): Boolean = when {
        advanced -> false
        stage == DlnaDiagnosis.NEVER_FETCHED -> route == CastStrategy.Route.DIRECT
        stage == DlnaDiagnosis.STUCK_LOADING || stage == DlnaDiagnosis.NEVER_PLAYED -> true
        else -> remuxAfterEarlyStop(stage, advanced)
    }
}
