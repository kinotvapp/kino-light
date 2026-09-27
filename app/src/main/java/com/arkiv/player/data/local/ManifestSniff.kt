package com.arkiv.player.data.local

/**
 * The server answered an HLS/DASH/Smooth manifest where one video file was expected. Not an
 * `IOException` on purpose: [DownloadRetryPolicy.isTransient] must never retry it (the same URL
 * answers the same manifest tomorrow), and the strategy that asked for the check turns it into a
 * permanent refusal ("Este video no se puede descargar").
 */
class ManifestResponseException : RuntimeException(PluginDownloadEligibility.NOT_DOWNLOADABLE)

/**
 * Recognizes a streaming manifest from what the server ACTUALLY sent, for [HttpRangeDownloader]'s
 * `refuseManifests`: a plugin's CDN may serve an HLS playlist from an extensionless URL with no
 * `mime` declared, which [PluginDownloadEligibility] (URL and declared mime only) cannot see.
 * Without this the playlist text would be saved as `<episode>.mp4`, marked completed, and play a
 * black screen offline. Pure and byte-based so it is tested without a server.
 */
object ManifestSniff {
    /** How many leading bytes [looksLikeManifest] needs: every manifest declares itself at once. */
    const val SNIFF_BYTES = 512

    /** HLS (any `mpegurl` type), DASH (`application/dash+xml`) or Smooth (`application/vnd.ms-sstr+xml`). */
    fun isManifestMime(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return type.isNotEmpty() && MANIFEST_MIMES.any { type.contains(it) }
    }

    /**
     * Whether a body starting with [head] is a manifest: an HLS playlist header (`#EXTM3U`), a DASH
     * `<MPD` or a Smooth `<SmoothStreamingMedia` root, bare or after an XML prolog. A BOM and leading
     * whitespace are skipped. Video bytes (an `ftyp` box, EBML, TS sync bytes…) never match.
     */
    fun looksLikeManifest(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val text = String(head, Charsets.UTF_8).removePrefix("﻿").trimStart()
        if (text.startsWith(HLS_HEADER)) return true
        if (XML_ROOTS.any { text.startsWith(it) }) return true
        if (text.startsWith("<?xml")) return XML_ROOTS.any { text.contains(it) }
        return false
    }

    private const val HLS_HEADER = "#EXTM3U"
    private val XML_ROOTS = listOf("<MPD", "<SmoothStreamingMedia")
    private val MANIFEST_MIMES = listOf("mpegurl", "dash+xml", "vnd.ms-sstr")
}
