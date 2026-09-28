package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind

/**
 * Whether the phone player shows the DLNA/Chromecast buttons for what it is playing.
 *
 * [streamItem] is the ViewModel's `magisItem` slot (a native Magis title or a plugin title), or
 * null when something else plays (playlist, live module channel, Caracol) -- those always cast.
 *
 * A plugin title casts only when it is the OFFICIAL Xuper plugin's ([castsAsXuper]). Since Xuper's
 * Home rows moved to the plugin path every Xuper title plays as [SourceKind.PLUGIN], and the old
 * blanket "no cast for plugin titles" rule took the buttons away from all of them (0.9.41). Any
 * other plugin still has no cast: its stream is only reachable with the plugin's own gated HTTP
 * stack, which a receiver on the LAN never goes through.
 *
 * Orientation is deliberately not an input: the buttons live in the controls overlay, so they show
 * (portrait and landscape alike) only while the controls are up and hide with them.
 */
internal fun playerOffersCast(isTv: Boolean, streamItem: PlayerData?): Boolean =
    !isTv && (streamItem == null || streamItem.kind != SourceKind.PLUGIN || castsAsXuper(streamItem))

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
 * The phone keeps playing the plugin item itself (its own gated HTTP stack); only what is sent to
 * the TV changes.
 */
internal fun castableStreamItem(
    item: PlayerData,
    proxyUrl: (url: String, headers: Map<String, String>) -> String,
): PlayerData? = when {
    item.kind != SourceKind.PLUGIN -> item
    castsAsXuper(item) -> item.copy(
        kind = SourceKind.MAGIS,
        mediaUrl = proxyUrl(item.mediaUrl, item.requestHeaders),
        castUrl = item.mediaUrl,
    )
    else -> null
}
