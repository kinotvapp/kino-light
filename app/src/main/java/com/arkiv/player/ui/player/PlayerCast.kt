package com.arkiv.player.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * `PluginCastProxy` (the plugin's gated client, never a plain fetch), its HLS there too (playlists
 * rewritten, CORS added) and a header-free file straight to the receiver. DRM, DASH and formats
 * nothing tells apart stay without the buttons.
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
 * - [PluginCastMode.Direct]: the stream's own url (a file), `mime` = its container; the proxied
 *   twin rides along as the request's fallback ([withPluginProxyFallback]).
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

/**
 * [request] for a castable plugin item, labelled with its route and, for a DIRECT one (the
 * receiver fetches the stream's own URL), given the same media through `PluginCastProxy` as its
 * [com.arkiv.player.cast.CastRequest.fallback]: loaded once if the receiver fails the direct one
 * before playing (ERRORES-AME: OK.ru mp4s idle at 0:00 on a Chromecast). [register] files [item]
 * with the proxy and returns its loopback token URL (null when the proxy can't take it: no
 * fallback then). Anything that is not a plugin item is returned as it is. Pure.
 */
internal fun withPluginProxyFallback(
    request: com.arkiv.player.cast.CastRequest,
    item: PlayerData,
    lanIp: String?,
    register: (PlayerData) -> String?,
): com.arkiv.player.cast.CastRequest {
    if (item.kind != SourceKind.PLUGIN) return request
    if (item.mediaUrl.startsWith(LOOPBACK)) return request.copy(route = "proxy")
    val viaProxy = register(item)?.let { pluginCastUri(item.copy(mediaUrl = it), lanIp) }
        ?: return request.copy(route = "direct")
    return request.copy(route = "direct", fallback = request.copy(uri = viaProxy, route = "proxy"))
}

/** [withPluginProxyFallback] wired to the app's `PluginCastProxy`. Out of `PlayerContent` (ART's verifier limit). */
internal fun pluginCastFallback(
    request: com.arkiv.player.cast.CastRequest,
    item: PlayerData,
    graph: com.arkiv.player.AppGraph,
    lanIp: String?,
): com.arkiv.player.cast.CastRequest = withPluginProxyFallback(request, item, lanIp) { registerPluginCast(graph, it) }

/**
 * Files a plugin [item]'s own stream with `PluginCastProxy` (started if it isn't) and returns its
 * loopback token URL, or null when the proxy can't take it. The plugin's headers and hosts stay
 * on the phone.
 */
internal fun registerPluginCast(graph: com.arkiv.player.AppGraph, item: PlayerData): String? {
    val proxy = graph.pluginCastProxy
    runCatching { proxy.start() }
    return proxy.register(
        key = item.episodeId,
        origin = item.mediaUrl,
        headers = item.requestHeaders,
        hosts = item.pluginHosts,
        shape = com.arkiv.player.playback.PluginCastProxy.shapeFor(item.mime),
        mime = item.mime.ifBlank { "video/mp4" },
    )
}

/** A castable plugin item is a live channel: the receiver starts at the live edge, not a saved position. */
internal fun isPluginLiveCast(item: PlayerData): Boolean =
    item.kind == SourceKind.PLUGIN && PluginIds.isLiveEpisode(item.episodeId)

/**
 * Tells the cast session that a player screen is in the foreground showing [episodeId]: while it
 * is, a (re)connect replays no old load (the screen sends its own, newest wins), and outside a
 * cast a pending load for any other title -- or for anything at all once the screen is left -- is
 * dropped as stale. See `CastSessionManager.onScreenForeground` / `onScreenTitle`.
 *
 * A composable of its own so `PlayerContent`, at ART's verifier limit, carries one call instead of
 * the effects and the lifecycle observer.
 */
@androidx.compose.runtime.Composable
internal fun CastScreenPresence(session: com.arkiv.player.cast.CastSessionManager?, episodeId: String) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    PlayerOnScreenPresence(owner, episodeId)
    if (session == null) return
    PreferDownloadOnChromecast(session, episodeId)
    androidx.compose.runtime.DisposableEffect(session, owner) {
        var foreground = false
        fun set(open: Boolean) {
            if (open == foreground) return
            foreground = open
            session.onScreenForeground(open)
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> set(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> set(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            set(false)
            // Leaving the player: outside a cast nothing of it may replay at a later connect.
            session.onScreenTitle(null)
        }
    }
    androidx.compose.runtime.LaunchedEffect(session, episodeId) { session.onScreenTitle(episodeId) }
}

/**
 * [com.arkiv.player.cast.PlayerOnScreen]: which title the player screen in the foreground shows,
 * with or without a cast session (a phone with no Play services still casts over DLNA).
 */
@androidx.compose.runtime.Composable
private fun PlayerOnScreenPresence(owner: androidx.lifecycle.LifecycleOwner, episodeId: String) {
    androidx.compose.runtime.DisposableEffect(owner, episodeId) {
        val screen = com.arkiv.player.cast.PlayerOnScreen
        screen.episodeId = episodeId
        screen.foreground = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { screen.episodeId = episodeId; screen.foreground = true }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (screen.episodeId == episodeId) screen.foreground = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (screen.episodeId == episodeId) {
                screen.foreground = false
                screen.episodeId = null
            }
        }
    }
}

/**
 * A Chromecast session starting while the player shows a title that is on the phone but was opened
 * from the network (the download finished after it opened): the player is reopened from the file,
 * and the reopened player casts THAT to the session as it loads. See
 * [com.arkiv.player.cast.DownloadForTvPolicy.reopenAsLocal]; once per title ([LocalReopens]).
 */
@androidx.compose.runtime.Composable
private fun PreferDownloadOnChromecast(session: com.arkiv.player.cast.CastSessionManager, episodeId: String) {
    val graph = com.arkiv.player.ui.rememberGraph()
    val casting = session.casting.collectAsStateWithLifecycle().value
    androidx.compose.runtime.LaunchedEffect(casting, episodeId) {
        if (!casting) return@LaunchedEffect
        // The player's own load may still be opening the file: give it a moment to say so.
        kotlinx.coroutines.delay(1_000)
        val downloaded = graph.downloadForTv.downloaded
        if (!com.arkiv.player.cast.DownloadForTvPolicy.reopenAsLocal(episodeId, downloaded, com.arkiv.player.data.local.LocalFileUse.playingEpisode)) return@LaunchedEffect
        if (!LocalReopens.once(episodeId)) return@LaunchedEffect
        android.util.Log.w("ArkivCast", "casting a title that is downloaded but playing from the network → reopening it from the file")
        com.arkiv.player.cast.PlayerReopen.request(episodeId)
    }
}

/**
 * The cast card's text: "Preparándolo para la TV… NN%" with a spinner while the TV waits for the
 * remux to reach the phone's position, "Reproduciendo en Chromecast" otherwise. Out of
 * `PlayerContent` (ART's verifier limit). [progress] outside 0..100 means no figure to show.
 */
@androidx.compose.runtime.Composable
internal fun CastCardContent(preparing: Boolean, progress: Int) {
    if (preparing) {
        androidx.compose.material3.CircularProgressIndicator(
            color = androidx.compose.ui.graphics.Color.White,
            strokeWidth = 2.dp,
            modifier = androidx.compose.ui.Modifier.size(20.dp),
        )
        androidx.compose.material3.Text(castPreparingText(progress), color = androidx.compose.ui.graphics.Color.White)
    } else {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Default.Tv,
            contentDescription = null,
            tint = com.arkiv.player.ui.theme.ArkivRed,
        )
        androidx.compose.material3.Text("Reproduciendo en Chromecast", color = androidx.compose.ui.graphics.Color.White)
    }
}

/** "Preparándolo para la TV…", with the remux's [progress] when it is a percentage. */
internal fun castPreparingText(progress: Int): String =
    "Preparándolo para la TV…" + if (progress in 0..100) " $progress%" else ""

/** The app's cast session state ([CastSessionManager.casting]), false without one (a TV, no Play services). */
@androidx.compose.runtime.Composable
internal fun rememberCastingState(graph: com.arkiv.player.AppGraph): androidx.compose.runtime.State<Boolean> {
    val flow = androidx.compose.runtime.remember(graph) {
        graph.castSession?.casting ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    return flow.collectAsStateWithLifecycle()
}
