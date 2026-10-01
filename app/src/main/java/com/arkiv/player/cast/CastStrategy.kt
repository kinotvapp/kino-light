package com.arkiv.player.cast

import com.arkiv.player.playback.Container
import com.arkiv.player.playback.VideoContainer

/**
 * The LIGHTEST way to put one stream on a Cast receiver (Google's Default Media Receiver). Pure.
 *
 * Lightest = the least work and LAN traffic on the phone, in this order:
 * 1. [Route.DIRECT]: the receiver fetches the stream's own URL; the phone moves no bytes at all.
 * 2. [Route.PROXY]: the receiver fetches it through a token-gated proxy on the phone, bytes as they
 *    are, because the CDN wants headers the receiver cannot send (Xuper's `Content-Auth`, a
 *    hoster's Referer) or the host must not be handed to the TV.
 * 3. [Route.REMUX]: only when the CONTAINER is one the receiver refuses: a progressive MPEG-TS.
 *    The remux (`TsRemuxer`) changes the container only -- TS to fragmented MP4, served as HLS --
 *    and copies the samples: HEVC stays HEVC, AAC stays AAC. It fixes nothing about codecs.
 * 4. [Route.TS_PLAYLIST]: the TS wrapped as an HLS playlist of byte ranges through the proxy, no
 *    remux. A fallback for when the remux failed (see `magisTsPlaylistUrl`).
 *
 * Measured 2026-10-01 on the KALLEY's built-in Chromecast (Cast 3.72, Shaka for HLS):
 * progressive `.ts` → LOAD_FAILED (never sent); MP4 faststart progressive → start 0.5-0.8 s, seek
 * 0.5-1 s (the best there is); HLS fMP4 → start 2.8 s (H.264) / 7-25 s (HEVC on a congested
 * 2.4 GHz), seek 2-5 s; HLS of TS segments → plays, H.264 start 5-6 s and seek 9-14 s, HEVC too on
 * this TV although Google's docs say HEVC in TS is unsupported. Hence: an MP4/WebM/HLS stream
 * goes as it is; a TS is remuxed (fMP4 HLS is faster and does not rely on HEVC-in-TS), and the TS
 * playlist is only the fallback.
 *
 * Codecs are not decided here: the receiver reports none of its decoders to the sender, H.264/AAC
 * play everywhere, and HEVC plays on receivers that have it (the KALLEY does, in fMP4 and in TS);
 * no route on the phone turns HEVC into H.264.
 */
object CastStrategy {

    enum class Format { MP4, WEBM, HLS, DASH, MPEG_TS, MATROSKA, OTHER_FILE, UNKNOWN }

    /**
     * [REMUX] is the growing remux served as an fMP4 HLS EVENT playlist (`RemuxHlsServer`).
     * [REMUX_FILE] is the same remux handed over only once it is whole, as one plain MP4 file with
     * ranges: for a receiver that cannot take HLS (most DLNA renderers list no playlist type), at
     * the cost of waiting for the whole title.
     */
    enum class Route { DIRECT, PROXY, REMUX, REMUX_FILE, TS_PLAYLIST, NONE }

    /**
     * What the receiver can take beyond a progressive MP4 (which every one plays), as an INPUT to
     * the route: Google's receiver is [CAST]; a DLNA renderer's comes from the sink protocols it
     * lists (`DlnaRenderer.receiverOf`).
     *
     * @param playsTs plays a progressive MPEG-TS as it is.
     * @param playsHls plays HLS, so the growing remux can go out at once instead of whole.
     */
    data class Receiver(val playsTs: Boolean, val playsHls: Boolean) {
        companion object {
            /** Google's Default Media Receiver: refuses a progressive TS (LOAD_FAILED), plays HLS (Shaka). */
            val CAST = Receiver(playsTs = false, playsHls = true)
        }
    }

    const val MIME_HLS = "application/vnd.apple.mpegurl"
    const val MIME_DASH = "application/dash+xml"

    /** The format of [url], its declared [mime] first (blank = unknown), then its extension. */
    fun formatOf(url: String, mime: String): Format {
        val declared = mime.substringBefore(';').trim().lowercase()
        if (declared.isNotEmpty()) {
            when {
                declared.contains("mpegurl") -> return Format.HLS
                declared.contains("dash") -> return Format.DASH
                declared == Container.MP4.mime -> return Format.MP4
                declared == Container.WEBM.mime -> return Format.WEBM
                declared == Container.MPEGTS.mime -> return Format.MPEG_TS
                declared == Container.MATROSKA.mime -> return Format.MATROSKA
                declared.startsWith("video/") -> return Format.OTHER_FILE
            }
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return Format.HLS
        if (path.endsWith(".mpd")) return Format.DASH
        return when (VideoContainer.byExtension(url)) {
            Container.MP4 -> Format.MP4
            Container.WEBM -> Format.WEBM
            Container.MPEGTS -> Format.MPEG_TS
            Container.MATROSKA -> Format.MATROSKA
            null -> Format.UNKNOWN
            else -> Format.OTHER_FILE
        }
    }

    /**
     * The route for a stream of [format].
     *
     * @param needsHeaders the CDN wants request headers (auth, Referer...) the receiver cannot send.
     * @param directAllowed the receiver may be pointed at the stream's host (the plugin's own host
     *   rules: declared, https, never a LAN address).
     * @param remuxAvailable this source has the TS remux wired (Magis/Xuper and downloads).
     * @param tsPlaylistAvailable this source can serve a TS as an HLS playlist (fallback).
     * @param receiver what the receiver takes; Google's by default.
     */
    fun choose(
        format: Format,
        needsHeaders: Boolean,
        directAllowed: Boolean,
        remuxAvailable: Boolean,
        tsPlaylistAvailable: Boolean = false,
        receiver: Receiver = Receiver.CAST,
    ): Route = when (format) {
        Format.MP4, Format.WEBM, Format.HLS ->
            if (needsHeaders || !directAllowed) Route.PROXY else Route.DIRECT
        // A receiver that plays a TS gets it as it is: no remux, the lightest route there is.
        // Google's never does: it refuses a progressive TS outright (LOAD_FAILED).
        Format.MPEG_TS -> when {
            receiver.playsTs -> if (needsHeaders || !directAllowed) Route.PROXY else Route.DIRECT
            remuxAvailable -> remuxRoute(receiver)
            tsPlaylistAvailable -> Route.TS_PLAYLIST
            else -> Route.NONE
        }
        // Not a container the Default Media Receiver documents; passed as it is, as before. Not
        // remuxed: the remux is wired for TS only, and an mkv's tracks (AC-3, subtitles) are the
        // likelier problem.
        Format.MATROSKA, Format.OTHER_FILE ->
            if (needsHeaders || !directAllowed) Route.PROXY else Route.DIRECT
        Format.DASH, Format.UNKNOWN -> Route.NONE
    }

    /** How a remux reaches [receiver]: growing as HLS when it plays HLS, else whole as one MP4. */
    fun remuxRoute(receiver: Receiver): Route = if (receiver.playsHls) Route.REMUX else Route.REMUX_FILE

    /**
     * The next route after the receiver REJECTED [route] for a stream of [format] (a rejection the
     * container explains, as the caller judged it), or [Route.NONE] when there is nothing lighter
     * left to try. A TS it claimed to play goes to the remux; the growing remux as HLS goes to the
     * whole MP4, the container every receiver takes.
     */
    fun afterRejection(format: Format, route: Route, receiver: Receiver, remuxAvailable: Boolean): Route = when {
        !remuxAvailable || format != Format.MPEG_TS -> Route.NONE
        route == Route.DIRECT || route == Route.PROXY -> remuxRoute(receiver)
        route == Route.REMUX -> Route.REMUX_FILE
        else -> Route.NONE
    }

    /**
     * A stream's MIME from its first bytes (a cheap probe of a URL nothing else describes), or
     * null when they say nothing: an HLS or DASH manifest by its text, a media container by its
     * signature ([VideoContainer.bySignature]).
     */
    fun mimeFromSignature(head: ByteArray): String? {
        val text = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t')
        if (text.startsWith("#EXTM3U")) return MIME_HLS
        if (text.startsWith("<?xml") && text.contains("<MPD") || text.startsWith("<MPD")) return MIME_DASH
        return VideoContainer.bySignature(head)?.mime
    }
}
