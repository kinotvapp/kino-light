package com.arkiv.player.ui.player

import com.arkiv.player.cast.CastStrategy
import com.arkiv.player.data.plugin.PluginHostGate
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.VideoContainer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How a PLUGIN title goes to a TV (Chromecast or DLNA). Decided by [pluginCastModeFor], pure.
 */
internal sealed interface PluginCastMode {
    /** The official Xuper plugin's VOD: re-spelled as the native Magis item, exactly as before. */
    data object Xuper : PluginCastMode

    /**
     * Through [com.arkiv.player.playback.PluginCastProxy]: the phone fetches with the plugin's gated
     * client and headers, the TV only ever sees a token URL. [mime] is what the receiver is told; an
     * HLS one is served as a rewritten playlist whose every URI points back to the proxy.
     */
    data class ViaProxy(val mime: String) : PluginCastMode

    /**
     * The receiver fetches the stream's own URL: an MP4 or WebM file that needs no header, on a
     * host the plugin's rules allow. Nothing goes through the phone unless the receiver fails it
     * before playing: then it is loaded once more through the proxy (`CastRequest.fallback`).
     */
    data class Direct(val mime: String) : PluginCastMode

    /** Not castable; [reason] is for the log. */
    data class None(val reason: String) : PluginCastMode
}

/** What kind of stream a plugin returned, from its declared MIME or, failing that, its URL. */
internal enum class PluginStreamFormat { FILE, HLS, DASH, UNKNOWN }

internal const val MIME_HLS = "application/vnd.apple.mpegurl"

/** The format of [url] (declared [mime] first) and the MIME a receiver should be told. */
internal fun pluginStreamFormat(url: String, mime: String): Pair<PluginStreamFormat, String> {
    val declared = mime.substringBefore(';').trim().lowercase()
    when {
        declared.contains("mpegurl") -> return PluginStreamFormat.HLS to MIME_HLS
        declared.contains("dash") -> return PluginStreamFormat.DASH to declared
        declared.startsWith("video/") -> return PluginStreamFormat.FILE to declared
    }
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> PluginStreamFormat.HLS to MIME_HLS
        path.endsWith(".mpd") -> PluginStreamFormat.DASH to "application/dash+xml"
        else -> VideoContainer.byExtension(url)?.let { PluginStreamFormat.FILE to it.mime }
            ?: (PluginStreamFormat.UNKNOWN to "")
    }
}

/**
 * Whether a receiver may be handed [item]'s own URL: it passes the plugin's host rules
 * ([PluginHostGate.check] against the INSTALLED record's hosts: declared or typed host, https unless
 * approved as `insecureHttp`, never an IP literal or a local name). No DNS here: the phone does not
 * fetch it, the TV does, and the rule is only there so a plugin cannot point the TV into the LAN.
 */
internal fun directCastAllowed(item: PlayerData): Boolean {
    val url = item.mediaUrl.toHttpUrlOrNull() ?: return false
    return runCatching { PluginHostGate.check(url, item.pluginHosts) }.isSuccess
}

/**
 * The cast decision for one plugin title ([item] is `PlayerData` as `loadPlugin` published it):
 * the lightest route [com.arkiv.player.cast.CastStrategy] allows, with no remux (plugins have none).
 *
 * | stream                                          | mode                    |
 * |-------------------------------------------------|-------------------------|
 * | official Xuper VOD                               | [PluginCastMode.Xuper] (unchanged) |
 * | official Xuper live channel                      | None (unchanged)        |
 * | DRM (Widevine or ClearKey)                       | None                    |
 * | mp4/webm (mkv…), no headers, host allowed        | Direct (proxy fallback) |
 * | mp4/webm/mkv… that need headers (or can't go direct) | ViaProxy           |
 * | HLS, always (the receiver needs CORS on it)      | ViaProxy, playlists rewritten |
 * | progressive MPEG-TS                              | None: the receiver refuses it (LOAD_FAILED) |
 * | DASH, or a format nothing tells apart            | None                    |
 *
 * The format is the declared MIME, else the URL, else what a probe of the first bytes found
 * ([PlayerData.probedMime]). [directAllowed] is [directCastAllowed] outside tests.
 */
internal fun pluginCastModeFor(
    item: PlayerData,
    directAllowed: (PlayerData) -> Boolean = ::directCastAllowed,
): PluginCastMode {
    if (item.kind != SourceKind.PLUGIN) return PluginCastMode.None("not a plugin title")
    val live = PluginIds.isLiveEpisode(item.episodeId)
    if (item.pluginXuper) {
        return if (live) PluginCastMode.None("xuper live channel") else PluginCastMode.Xuper
    }
    if (item.drm) return PluginCastMode.None("drm")
    val (format, mime) = pluginStreamFormat(item.mediaUrl, item.mime.ifBlank { item.probedMime })
    val needsHeaders = item.requestHeaders.isNotEmpty()
    return when (format) {
        PluginStreamFormat.FILE -> when (
            CastStrategy.choose(CastStrategy.formatOf(item.mediaUrl, mime), needsHeaders, !needsHeaders && directAllowed(item), remuxAvailable = false)
        ) {
            CastStrategy.Route.DIRECT -> PluginCastMode.Direct(mime)
            CastStrategy.Route.PROXY -> PluginCastMode.ViaProxy(mime)
            else -> PluginCastMode.None("progressive mpeg-ts: the receiver refuses it, and plugin titles have no remux")
        }
        // Always through the proxy, headers or not: the receiver reads HLS with XHR, so every
        // playlist and segment host must answer with CORS, and no plugin host is known to (an OK.ru,
        // an iptv-org channel: idle at 0:00 on a Chromecast, ERRORES-AME). The proxy sends CORS and
        // the plugin's headers, through its gated client. A progressive file needs no CORS.
        PluginStreamFormat.HLS -> PluginCastMode.ViaProxy(MIME_HLS)
        PluginStreamFormat.DASH -> PluginCastMode.None("dash")
        PluginStreamFormat.UNKNOWN -> PluginCastMode.None("unknown format")
    }
}

/**
 * Whether a plugin stream nothing describes gets its first bytes probed for the cast
 * ([PlayerData.probedMime]): a title always (while casting before it is published, since the cast
 * decides on it then; otherwise after, off the phone's start); a [live] channel only while [casting]. A probe is one more connection to the stream, and on every
 * zap of an M3U/Xtream list it cost up to 2 s more and, on a single-connection provider or a
 * one-use link, could get the player itself refused (review 2026-10-01).
 */
internal fun pluginCastProbe(live: Boolean, casting: Boolean): Boolean = !live || casting

/** What the person is told when a cast session is up and this plugin title cannot go to the TV. */
internal fun pluginNoCastMessage(item: PlayerData): String =
    if (item.drm) "Este título está protegido y no se puede enviar a la TV"
    else "Este título no se puede enviar a la TV"
