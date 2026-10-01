package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind

/**
 * Whether the phone player shows the DLNA/Chromecast buttons for what it is playing.
 *
 * [streamItem] is the ViewModel's `magisItem` slot (a native Magis title or a plugin title), or
 * null when something else plays (playlist, live module channel, Caracol) -- those always cast.
 *
 * A plugin title casts unless [pluginCastModeFor] says [PluginCastMode.None]: the official Xuper
 * plugin's VOD exactly as before ([castsAsXuper]), any other plugin's progressive file through
 * `PluginCastProxy` (the plugin's gated client, never a plain fetch), its HLS that needs headers there too
 * (playlists rewritten) and a header-free HLS straight to the receiver. DRM, DASH and formats nothing
 * tells apart stay without the buttons.
 *
 * Orientation is deliberately not an input: the buttons live in the controls overlay, so they show
 * (portrait and landscape alike) only while the controls are up and hide with them.
 */
internal fun playerOffersCast(isTv: Boolean, streamItem: PlayerData?): Boolean =
    playerOffersCast(isTv, streamItem, ::directCastAllowed)

/**
 * [playerOffersCast] with the host rule for a direct cast as an input (tests). A separate overload,
 * not a default argument: `PlayerContent` calls the two-argument one, and a `$default` call would
 * grow that method, which is at ART's verifier limit.
 */
internal fun playerOffersCast(
    isTv: Boolean,
    streamItem: PlayerData?,
    directAllowed: (PlayerData) -> Boolean,
): Boolean =
    !isTv && (
        streamItem == null || streamItem.kind != SourceKind.PLUGIN ||
            pluginCastModeFor(streamItem, directAllowed) !is PluginCastMode.None
        )

/**
 * An official-Xuper plugin VOD title: [PlayerData.pluginXuper] is `PluginAccess.Ready.xuper`, i.e.
 * `XuperPrivilege.grants` on the INSTALLED record's address (new or legacy repo), never the id in
 * the episode id that any repo could claim. Its stream is the one the native Magis bridge resolved,
 * with the native CDN headers re-attached (`PluginOutput.streamOf` via `XuperStreams`). A plugin
 * live channel is left out: it is not a Magis VOD file, and the Magis cast path (remux, HLS of byte
 * ranges) is wrong for it.
 */
internal fun castsAsXuper(item: PlayerData): Boolean =
    item.kind == SourceKind.PLUGIN && item.pluginXuper && !PluginIds.isLiveEpisode(item.episodeId)

/**
 * [item] in the shape every cast path (Chromecast's `castRequestFor`, the TS remux, DLNA's
 * `sendToRenderer`) knows how to send, or null when it cannot be cast.
 *
 * Native items go as they are. An official-Xuper plugin title is re-spelled as the native Magis
 * item `loadMagis` used to publish for the very same stream, which is what cast in 0.9.40:
 * - `kind = MAGIS`, so the receiver is handed the proxy's LAN url (the CDN answers 401 without
 *   `Content-Auth`/`Content-License`, which neither the Default Media Receiver nor a DLNA renderer
 *   can send) and a `.ts` goes through the remux/HLS-playlist path;
 * - `mediaUrl` = [proxyUrl] of the CDN url with the stream's headers (`ArchiveCacheProxy.proxyUrl`,
 *   `direct = true`, like `loadMagis`' `localUrl`): the proxy puts the headers on every request;
 * - `castUrl` = the raw CDN url, the only place the true container (`_media.ts`/`_media.mp4`)
 *   survives, and the key the remux is filed under.
 *
 * Any other castable plugin title stays `kind = PLUGIN` (see [pluginCastUri]):
 * - [PluginCastMode.ViaProxy]: `mediaUrl` = [pluginProxyUrl] (the loopback token URL of
 *   `PluginCastProxy.register`; null while the proxy isn't up, and then there is no cast), headers
 *   dropped (they stay in the proxy), `mime` = what the receiver is told;
 * - [PluginCastMode.Direct]: the stream's own url, `mime` = HLS.
 * `castUrl` is null for both, so nothing Magis-specific (remux, byte-range HLS) ever applies.
 * The phone keeps playing the plugin item itself (its own gated HTTP stack); only what is sent to
 * the TV changes.
 */
internal fun castableStreamItem(
    item: PlayerData,
    proxyUrl: (url: String, headers: Map<String, String>) -> String,
    pluginProxyUrl: (item: PlayerData, mime: String) -> String? = { _, _ -> null },
    directAllowed: (PlayerData) -> Boolean = ::directCastAllowed,
): PlayerData? {
    if (item.kind != SourceKind.PLUGIN) return item
    return when (val mode = pluginCastModeFor(item, directAllowed)) {
        PluginCastMode.Xuper -> item.copy(
            kind = SourceKind.MAGIS,
            mediaUrl = proxyUrl(item.mediaUrl, item.requestHeaders),
            castUrl = item.mediaUrl,
        )
        is PluginCastMode.ViaProxy -> pluginProxyUrl(item, mode.mime)?.let { local ->
            item.copy(mediaUrl = local, castUrl = null, mime = mode.mime, requestHeaders = emptyMap())
        }
        is PluginCastMode.Direct -> item.copy(castUrl = null, mime = mode.mime)
        is PluginCastMode.None -> null
    }
}

/**
 * `PlayerScreen`'s `castMagis`: [item] (its `magisItem`) in its cast shape, wired to the app's two
 * cast proxies. Out of `PlayerContent` on purpose (`remember`'s block is inlined, and that function
 * is at ART's verifier limit).
 */
internal fun castShapeFor(item: PlayerData?, graph: com.arkiv.player.AppGraph): PlayerData? =
    item?.let {
        castableStreamItem(
            it,
            proxyUrl = { url, headers -> graph.archiveCacheProxy.proxyUrl(url, headers, direct = true) },
            pluginProxyUrl = { plugin, mime ->
                graph.pluginCastProxy.register(
                    key = plugin.episodeId,
                    origin = plugin.mediaUrl,
                    headers = plugin.requestHeaders,
                    hosts = plugin.pluginHosts,
                    shape = com.arkiv.player.playback.PluginCastProxy.shapeFor(mime),
                    mime = mime,
                )
            },
        )
    }

/** [castShapeFor], remembered per item. A call of its own so `PlayerContent` carries no inlined `remember`. */
@androidx.compose.runtime.Composable
internal fun rememberCastShape(item: PlayerData?, graph: com.arkiv.player.AppGraph): PlayerData? =
    androidx.compose.runtime.remember(item) { castShapeFor(item, graph) }

/**
 * The LAN URL of `ArchiveCacheProxy`'s HLS playlist over a Magis/Xuper `.ts` ([proxyUrl] is its
 * loopback stream URL), or null when that playlist could not be built -- it would answer the TV a
 * 502 and the TV would show an error (every time, 2026-10-01). [durationMs] is the phone player's
 * duration, which is what lets the proxy build it without probing the CDN.
 *
 * Only a fallback: the cast's way for a `.ts` is the fMP4 remux, faster to start and to seek on
 * the receiver; this one is used when the remux failed.
 */
internal fun magisTsPlaylistUrl(
    graph: com.arkiv.player.AppGraph,
    proxyUrl: String,
    lanIp: String,
    durationMs: Long,
): String? {
    graph.archiveCacheProxy.rememberDuration(proxyUrl, durationMs)
    if (!graph.archiveCacheProxy.canServePlaylist(proxyUrl)) {
        com.arkiv.player.cast.CastDiag.w("ts playlist fallback unavailable (no duration known): not offered to the TV")
        return null
    }
    return com.arkiv.player.playback.ArchiveCacheProxy.lanPlaylistUrl(proxyUrl, lanIp)
}

/** Loopback authority `PluginCastProxy` writes; [pluginCastUri] respells only these. */
private const val LOOPBACK = "http://127.0.0.1:"

/**
 * The URL a TV is handed for a castable plugin item ([castableStreamItem]'s PLUGIN shape): the
 * proxy's token URL with the phone's LAN [lanIp] instead of loopback (null without one), or the
 * stream's own URL for a direct cast.
 */
internal fun pluginCastUri(item: PlayerData, lanIp: String?): String? =
    if (item.mediaUrl.startsWith(LOOPBACK)) {
        lanIp?.let { com.arkiv.player.playback.ArchiveCacheProxy.lanUrl(item.mediaUrl, it) }
    } else {
        item.mediaUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

/** A castable plugin item is a live channel: the receiver starts at the live edge, not a saved position. */
internal fun isPluginLiveCast(item: PlayerData): Boolean =
    item.kind == SourceKind.PLUGIN && PluginIds.isLiveEpisode(item.episodeId)
