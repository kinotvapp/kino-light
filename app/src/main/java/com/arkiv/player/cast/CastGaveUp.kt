package com.arkiv.player.cast

/**
 * The ONE place every cast passes through when all of its routes are exhausted, Chromecast and DLNA
 * alike: the Chromecast's last "Se cortó en el Chromecast" question (after the direct→proxy retry,
 * the automatic retry and, when offered, "Probar por DLNA"), and a DLNA cast whose whole route chain
 * (direct → proxy → HLS remux → whole MP4 remux, or the continuous TS of a live channel) failed.
 *
 * The escalation, in order: the chosen route; every alternative route; only then a LAST option.
 * That last option is not built yet. It is "Descargar y preparar para la TV" (0.9.46): download the
 * title, remux it to MP4 on the phone and cast the local file. It plugs in HERE with one
 * assignment, [lastResort]; nothing else changes:
 * - [exhausted] already returns it for every exhausted VOD cast (never a live one: a channel
 *   cannot be downloaded), and both final messages already show it when it is there: the
 *   Chromecast's trouble dialog (`CastTroubleDialog`, from `CastSessionManager.Trouble.lastResort`)
 *   and the DLNA failure (`DlnaState`, from `DlnaController.failures`).
 *
 * Pure apart from the log line.
 */
object CastGaveUp {

    /** A cast that tried everything: where ([receiver]), what, and the routes it went through ([CastRouteTrail.summary]). */
    data class Exhausted(
        val receiver: Receiver,
        val episodeId: String,
        val title: String,
        val live: Boolean,
        val routes: String,
        /** Where the person was in the title when it gave up (the receiver's last position), 0 unknown. */
        val positionMs: Long = 0L,
    )

    enum class Receiver { CHROMECAST, DLNA }

    /** An action offered once everything else failed: its button [label], and what tapping it does. */
    interface LastResort {
        val label: String
        fun start(exhausted: Exhausted)
    }

    /** The last option for an exhausted VOD cast; null until 0.9.46's "Descargar y preparar para la TV" sets it. */
    @Volatile
    var lastResort: LastResort? = null

    /** The last cast that gave up (diagnostics, tests). */
    @Volatile
    var last: Exhausted? = null
        private set

    /** What [e] may still offer: [lastResort] for a VOD title, never for a live channel. */
    fun lastResortFor(e: Exhausted): LastResort? = if (e.live || e.episodeId.isBlank()) null else lastResort

    /** Records that [e] gave up and returns the last option to offer with the final message (null: none). */
    fun exhausted(e: Exhausted): LastResort? {
        last = e
        CastDiag.w("cast gave up · ${e.receiver} · ${if (e.live) "live" else "vod"} · routes ${e.routes.ifBlank { "-" }}")
        return lastResortFor(e)
    }
}
