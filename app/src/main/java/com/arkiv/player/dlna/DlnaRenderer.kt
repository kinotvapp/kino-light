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
