package com.arkiv.player.cast

/**
 * The ONE place every cast passes through when all of its routes are exhausted, Chromecast and DLNA
 * alike: the Chromecast's last "Se cortó en el Chromecast" question (after the direct→proxy retry,
 * the automatic retry and, when offered, "Probar por DLNA"), and a DLNA cast whose whole route chain
 * (direct → proxy → HLS remux → whole MP4 remux, or the continuous TS of a live channel) failed.
 *
 * The escalation, in order: the chosen route; every alternative route; only then a LAST option,
 * [lastResort]: "Descargar y preparar para la TV" (0.9.46, `DownloadForTv`): download the title,
 * make it a faststart MP4 on the phone and cast the local file -- or "Enviar la descarga a la TV"
 * when it is already downloaded. [exhausted] returns what it offers for every exhausted VOD cast
 * (never a live one: a channel cannot be downloaded), and both final messages show it: the
 * Chromecast's trouble dialog (`CastTroubleDialog`, from `CastSessionManager.Trouble.lastResort`)
 * and the DLNA failure (`DlnaState`, from `DlnaController.failures`).
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
        /** The DLNA renderer that gave up, to cast the prepared download to it again; null for a Chromecast. */
        val dlnaDevice: com.arkiv.player.dlna.DlnaDevice? = null,
    )

    enum class Receiver { CHROMECAST, DLNA }

    /** What the final message offers: its button [label], a plain [explanation], and what tapping it does. */
    class Offer(val label: String, val explanation: String, private val action: (Exhausted) -> Unit) {
        fun start(exhausted: Exhausted) = action(exhausted)
    }

    /** The last option, decided per title: what it offers for [e], or null (not downloadable, nothing to send). */
    fun interface LastResort {
        fun offerFor(e: Exhausted): Offer?
    }

    /** The last option for an exhausted VOD cast; set by `AppGraph` (`DownloadForTv`). */
    @Volatile
    var lastResort: LastResort? = null

    /** The last cast that gave up (diagnostics, tests). */
    @Volatile
    var last: Exhausted? = null
        private set

    /** What [e] may still offer: [lastResort]'s offer for a VOD title, never for a live channel. */
    fun lastResortFor(e: Exhausted): Offer? =
        if (e.live || e.episodeId.isBlank()) null else runCatching { lastResort?.offerFor(e) }.getOrNull()

    /** Records that [e] gave up and returns the last option to offer with the final message (null: none). */
    fun exhausted(e: Exhausted): Offer? {
        last = e
        CastDiag.w("cast gave up · ${e.receiver} · ${if (e.live) "live" else "vod"} · routes ${e.routes.ifBlank { "-" }}")
        return lastResortFor(e)
    }
}
