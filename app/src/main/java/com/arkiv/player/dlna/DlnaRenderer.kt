package com.arkiv.player.dlna

import com.arkiv.player.cast.CastStrategy
import com.arkiv.player.playback.Container
import com.arkiv.player.playback.LanRequestListener

/**
 * What a DLNA renderer says it plays, as the [CastStrategy.Receiver] the shared route decision takes.
 * The route itself is NOT decided here: DLNA asks [CastStrategy.choose] like the Chromecast does,
 * with this as its input.
 */
internal object DlnaRenderer {

    /**
     * The HLS types a renderer may list in its sink protocols. LG webOS lists
     * `application/vnd.apple.mpegurl` or `application/x-mpegurl` on the models that play HLS over
     * DLNA; `audio/…mpegurl` is left out on purpose (an audio-only renderer's playlist).
     */
    private val HLS_MIMES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl")

    /**
     * The receiver [sinkMimes] (from `GetProtocolInfo`, see [DlnaXml.sinkMimes]) describe. An empty
     * list -- no ConnectionManager, or no answer -- is a renderer that plays NEITHER: what has
     * always been sent to one that says nothing is the whole MP4, so it still is.
     */
    fun receiverOf(sinkMimes: List<String>): CastStrategy.Receiver = CastStrategy.Receiver(
        playsTs = sinkMimes.any { it.equals(Container.MPEGTS.mime, ignoreCase = true) },
        playsHls = sinkMimes.any { it.lowercase() in HLS_MIMES },
    )

    /**
     * Whether an HLS playlist may go to a renderer that lists [sinkMimes]. Refused only when the
     * list is KNOWN and names no HLS type: a Philips "NMR" listing only `video/mpeg`,
     * `video/vnd.dlna.mpeg-tts` and the like answered `SetAVTransportURI` for a live channel's
     * playlist with HTTP 500 / UPnP 716 (ERRORES-AMF, 0.9.45). A renderer that lists nothing is sent
     * it as before: some that play HLS answer no `GetProtocolInfo`.
     *
     * One that refuses HLS may still take the stream as ONE continuous MPEG-TS body: [liveRoute].
     */
    fun takesHls(sinkMimes: List<String>): Boolean = sinkMimes.isEmpty() || receiverOf(sinkMimes).playsHls

    /** How an HLS stream (a live channel, a plugin's playlist) reaches a renderer: [liveRoute]. */
    sealed interface HlsRoute {
        /** The playlist as it is: the renderer lists an HLS type, or lists nothing. */
        data object Playlist : HlsRoute

        /** One continuous MPEG-TS body labelled [mime], the TS type the renderer lists ([com.arkiv.player.playback.ContinuousTs]). */
        data class ContinuousTs(val mime: String) : HlsRoute

        /** Neither: [noHlsMessage]. */
        data object None : HlsRoute
    }

    /**
     * The route for an HLS stream to a renderer that lists [sinkMimes]. The playlist whenever
     * [takesHls]: an LG answered the continuous body 501 and a Samsung stalled on it
     * (`experiment/dlna-live-ts`), and both list HLS. The continuous TS ONLY for a renderer that
     * lists no HLS type and does list a TS one (the Philips "NMR" of ERRORES-AMF); nothing for one
     * that lists neither.
     */
    fun liveRoute(sinkMimes: List<String>): HlsRoute = when {
        takesHls(sinkMimes) -> HlsRoute.Playlist
        else -> com.arkiv.player.playback.ContinuousTs.mimeFor(sinkMimes)?.let { HlsRoute.ContinuousTs(it) } ?: HlsRoute.None
    }

    /** What the person is told when [takesHls] refuses: a [live] channel, or a title. */
    fun noHlsMessage(live: Boolean): String =
        if (live) "Este TV no puede reproducir canales en vivo por DLNA"
        else "Este TV no puede reproducir este título por DLNA"

    /** One line for the log: how many types, and the ones the route depends on. */
    fun summary(sinkMimes: List<String>): String {
        if (sinkMimes.isEmpty()) return "lists nothing (no ConnectionManager or no answer)"
        val r = receiverOf(sinkMimes)
        val mp4 = sinkMimes.any { it.equals(Container.MP4.mime, ignoreCase = true) }
        val mkv = sinkMimes.any { it.equals(Container.MATROSKA.mime, ignoreCase = true) }
        return "${sinkMimes.size} types · mp4=${yn(mp4)} ts=${yn(r.playsTs)} hls=${yn(r.playsHls)} mkv=${yn(mkv)}"
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
}

/**
 * Counts and logs the requests a DLNA renderer makes to one of the phone's LAN servers (the
 * remux's [com.arkiv.player.playback.RemuxHlsServer], the finished remux's
 * [com.arkiv.player.playback.LocalFileServer]): "did the TV come for the media?" is what
 * [DlnaDiagnosis] splits every failure on, and a server that does not report it makes a TV that is
 * playing look like one that never fetched.
 *
 * [timings]: also put each finished request, with its bytes and time, in the diagnostic trail.
 * Off for the remux server, which already writes one line per segment there.
 */
internal class DlnaLanRequests(private val server: String, private val timings: Boolean) : LanRequestListener {

    override fun started(remote: String?, requestLine: String, range: String?, userAgent: String?) {
        DlnaLog.lanHit(server, remote, requestLine, range, userAgent)
    }

    override fun finished(remote: String?, requestLine: String, bytes: Long, ms: Long, failure: String?) {
        if (remote == null || DlnaLog.isLoopback(remote)) return
        if (bytes > 0) DlnaLog.lanBytes.addAndGet(bytes)
        if (!timings && failure == null) return
        val method = requestLine.substringBefore(' ')
        val path = DlnaXml.safeUrl(requestLine.split(' ').getOrNull(1))
        DlnaLog.diag("TV→$server $method $path · ${bytes}B in ${ms}ms" + (failure?.let { " · cut: $it" } ?: ""))
    }
}
