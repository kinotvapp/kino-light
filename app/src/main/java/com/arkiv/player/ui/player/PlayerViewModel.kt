package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.gateway.GatewayAudioTrack
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.plugin.blockedMessage
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.AdultContent
import com.arkiv.player.playback.LiveErrorKind
import com.arkiv.player.playback.LiveLog
import com.arkiv.player.playback.LiveReopenPolicy
import com.arkiv.player.playback.MagisEphemeral
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.live.LiveController
import com.arkiv.player.ui.live.LiveZapping
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.ModuleChannelOpen
import com.arkiv.player.ui.live.PluginLivePlay
import com.arkiv.player.ui.live.channelForLiveCode
import com.arkiv.player.ui.live.openModuleChannel
import com.arkiv.player.ui.live.pluginReopenStillWanted
import com.arkiv.player.ui.live.recentOf
import com.arkiv.player.ui.live.pluginLivePlay
import com.arkiv.player.ui.live.providerGone
import com.arkiv.player.ui.live.zappingListFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Data for one episode in the player. */
data class PlayerData(
    val episodeId: String,
    val itemId: String,
    val title: String,
    val subtitle: String,
    val mediaUrl: String,       // local playback (original mkv or downloaded file)
    val castUrl: String?,       // h.264 mp4 for Chromecast (compatible), or null
    val artworkUrl: String,     // cover art for the notification
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val kind: SourceKind,       // source (MAGIS/LOCAL/LIVE/UNKNOWN/PLUGIN) -- PlayerScreen reads it for live detection, the cast LAN URL and the cast-transcode origin
    val referer: String? = null,    // headers for the web stream (some hosts require Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,   // web: backup proxied URL if the direct one fails (403/geo/anti-leech)
    val prefersSoftware: Boolean = false, // magis's HEVC: the hardware decoder fails and drops tracks. See PlayerSourceTag.
    /**
     * Whether this came from an adults section: nothing playing with this mark gets logged to
     * history. See [com.arkiv.player.playback.AdultContent] and [shouldLogHistory].
     *
     * Travels on the ITEM and isn't checked on the fly for two reasons. One, the item is the only
     * thing that reaches here: `saveProgress` receives a bare `episodeId` and has nothing to infer
     * which section it came from. And two, adult content has NO library row -- that's the whole
     * point -- so there's nobody to ask afterward.
     *
     * `false` by default on purpose: it's the right value for every source that isn't the Magis
     * catalog (Caracol/Ditu, local, live, or a legacy id from a source removed in this branch's
     * pruning -- archive, torrent, web), where the notion doesn't exist.
     */
    val adult: Boolean = false,
    /** Headers the stream needs on every request (plugins); Magis's travel inside the proxy URL. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /**
     * PLUGIN only: the hosts the person approved for that plugin (installed record) plus the
     * servers they typed in its settings — never what `resolve()` returned. The player gates every
     * request of the stream — manifest, segments, keys, subtitles, redirect hops — to them; see
     * `streamHttpFor`.
     */
    val pluginHosts: com.arkiv.player.data.plugin.EffectiveHosts = com.arkiv.player.data.plugin.EffectiveHosts(emptyList()),
    /**
     * PLUGIN only: the stream is the recognized Xuper plugin's (`PluginAccess.Ready.xuper`, from
     * the INSTALLED record's address, never the id in [episodeId]), so the player's gate also
     * accepts the http CDN URLs its native bridge resolved; see `AppGraph.pluginStreamClient`.
     */
    val pluginXuper: Boolean = false,
    /** Container MIME the source declared ("" = let ExoPlayer sniff). */
    val mime: String = "",
    /**
     * PLUGIN only, CAST only: what a probe of the stream's first bytes found when neither its MIME
     * nor its URL said ("" = not probed, or nothing found). Never given to the phone's player,
     * which sniffs on its own; read by [pluginCastModeFor].
     */
    val probedMime: String = "",
    /**
     * PLUGIN only: the stream is DRM-protected (a Widevine license or a ClearKey key). It never goes
     * to a TV: the receiver can't reach the license through the plugin's gated client. See
     * [pluginCastModeFor].
     */
    val drm: Boolean = false,
    /**
     * Start position to resume (ExoPlayer, e.g. magisItem). The local player uses
     * PlaylistData.startPositionMs instead.
     */
    val startPositionMs: Long = 0L,
    /**
     * Start paused at [startPositionMs]: only a stream resolved again by the network recovery
     * ([PlayerViewModel.onVodNetworkReResolve]) for a player the person had paused.
     */
    val startPaused: Boolean = false,
)

/**
 * The access `loadPlugin` plays under. The built-in provider "Mis canales" ([com.arkiv.player.data.live.OwnLive.PLUGIN_ID])
 * is not an installed plugin, so the registry would answer `Uninstalled`: it gets its own access, whose
 * live hosts are any public host ([com.arkiv.player.data.live.OwnLive.access]). Everything else is the
 * registry's answer.
 */
internal fun pluginAccessFor(
    pluginId: String?,
    registry: com.arkiv.player.data.plugin.PluginPlayback?,
): com.arkiv.player.data.plugin.PluginAccess =
    if (pluginId == com.arkiv.player.data.live.OwnLive.PLUGIN_ID) com.arkiv.player.data.live.OwnLive.access()
    else registry?.accessFor(pluginId) ?: com.arkiv.player.data.plugin.PluginAccess.Uninstalled(pluginId ?: "desconocido")

/**
 * Should [episodeId]'s progress be logged to history?
 *
 * The question is answered against the playlist that's currently playing because `saveProgress`
 * receives a bare `episodeId`, not an item. Lives out here -- and not inside the ViewModel -- for
 * the same reason [com.arkiv.player.playback.AdultContent] lives outside `savePlayback`: it's
 * where its edges can be pinned down with tests.
 *
 * The edge that matters is the episode that is NOT in the playlist, and it's resolved by LOGGING
 * it. Not a theoretical case: the ViewModel survives navigation between chapters and `_playlist`
 * keeps publishing the previous chapter's while the new source resolves (see
 * [PlaylistData.requested]), so there are windows of seconds where the episode being asked about
 * isn't there yet. Reading that as "it's adult" would silently stop logging normal content's
 * progress.
 *
 * A live channel ([PlayerSource.isLiveChannel]: Caracol's, a plugin's) is never logged: it has no
 * library row and nothing to resume. `PlayerScreen` no longer saves its position (its `enVivo`
 * comes from the same rule), but its capture-on-pause doesn't check `enVivo`, and
 * `saveProgress`/`captureFrame` don't go through the `_magisItem` branch for Caracol: this remains
 * the guard that stops it in both. A plugin's channel plays through `_magisItem`, so its branch has
 * [savesProgress], the same rule.
 */
internal fun PlaylistData?.shouldLogHistory(episodeId: String): Boolean =
    !PlayerSource.isLiveChannel(episodeId) &&
        AdultContent.shouldLog(this?.items?.firstOrNull { it.episodeId == episodeId }?.adult)

/**
 * `saveProgress`/`captureFrame`'s decision for the item in `_magisItem` (Magis VOD, a plugin's
 * title or channel): adult content is not written, and neither is a live channel, which has no
 * "where you were" and no library row for a frame to hang off. Out here so it can be pinned down
 * with tests, like [shouldLogHistory].
 */
internal fun savesProgress(episodeId: String, adult: Boolean?): Boolean =
    !PlayerSource.isLiveChannel(episodeId) && AdultContent.shouldLog(adult)

/**
 * Should [episodeId] be marked "in progress" on opening (`ArkivRepository.markInProgress`)?
 *
 * `PlayerViewModel.load`'s decision, out here so it can be pinned down with tests. [adult] is the
 * ephemeral pending item's ([MagisEphemeral]), the only thing known before the source resolves,
 * and what isn't known gets logged, same as in [AdultContent.shouldLog]. A live channel (Caracol's,
 * a plugin's) is never marked: `markInProgress` would write a `playback` row with its id even
 * though there's no episode.
 */
internal fun shouldMarkInProgress(episodeId: String, adult: Boolean?): Boolean =
    !PlayerSource.isLiveChannel(episodeId) && AdultContent.shouldLog(adult)

/**
 * The section as a playlist: every episode + where/how to start. archive.org (removed in this
 * branch's pruning) was the only source that ever produced more than one item here -- every source
 * today (Magis, Ditu, live, local, and the legacy torrent/web rows) publishes a single-item
 * `PlaylistData(listOf(item), …)`. See `OutroSkip`'s KDoc in OutroSkip.kt.
 */
data class PlaylistData(
    val items: List<PlayerData>,
    val startIndex: Int,
    val startPositionMs: Long,
    /**
     * The episodeId that was requested when this playlist was built, i.e. WHICH CHAPTER it's for.
     *
     * Exists because the ViewModel survives navigation between chapters and this StateFlow keeps
     * publishing the previous chapter's playlist until the new source finishes resolving (seconds,
     * on magis/web). Without this mark, the screen had no way to tell "mine already arrived" from
     * "this is still the old one", and loaded the old one: choosing the next chapter in the
     * carousel would replay the one that was already playing. See [MediaReusePolicy.decide].
     *
     * NOT "the chapter playing right now" (that's answered by `episodioEnCurso` in PlayerScreen):
     * the distinction dates back to archive.org, which used to load the whole section and let the
     * player advance on its own within it without asking again. No source does that today (see
     * this class's own KDoc), but `requested` still exists for the survives-navigation race above.
     */
    val requested: String,
)

/**
 * A resolved subtitle (language + URL), to attach as an external track.
 *
 * Used to live in `com.arkiv.player.data.catalog.web.ResolvedSub` (torrent/web was removed in this
 * branch's pruning); [WebExtras] still needs it because magis also uses it, receiving its
 * subtitles from the gateway and not from any web resolver.
 */
data class ResolvedSub(
    val lang: String,
    val url: String,
    /** `"vtt"`/`"srt"` when the source declared it (plugins); "" = guess from the URL. */
    val format: String = "",
    /** The menu's name for the track ("" = named from [lang]): an online subtitle's own. */
    val label: String = "",
    /** Downloaded by the person from an online catalog, not given by the source. */
    val online: Boolean = false,
)

/** A downloaded online subtitle as a track: a local UTF-8 SRT. */
internal fun onlineSub(d: com.arkiv.player.data.subtitles.DownloadedSubtitle): ResolvedSub =
    ResolvedSub(lang = d.lang, url = java.io.File(d.path).toURI().toString(), format = "srt", label = d.label, online = true)

/** A plugin's subtitles, keeping the `format` it declared (its URLs rarely end in `.srt`). */
internal fun pluginSubtitles(subs: List<GatewaySubtitle>): List<ResolvedSub> =
    subs.map { ResolvedSub(lang = it.lang, url = it.url, format = it.format) }

/**
 * One of a plugin stream's own separately-hosted audio tracks (a dub, an alternate mix), to merge
 * into the video source as an external track -- see `StreamExoPlayer` and `TracksState`. [label], if
 * the plugin gave one, is shown in the menu verbatim; otherwise the menu names it from [lang].
 */
data class ResolvedAudioTrack(
    val lang: String,
    val url: String,
    val label: String = "",
)

/** A plugin's own audio tracks, unchanged (Magis never sends any -- it has none of its own to add). */
internal fun pluginAudioTracks(tracks: List<GatewayAudioTrack>): List<ResolvedAudioTrack> =
    tracks.map { ResolvedAudioTrack(lang = it.lang, url = it.url, label = it.label) }

/**
 * A plugin's Widevine-protected stream (apiVersion 2, the `drm` capability): where the player asks
 * for the license and what it sends along. Both came through `PluginOutput.stream` -- the license
 * URL checked exactly like the stream's own, the headers filtered like its `headers` -- never
 * straight from the plugin. See [PluginWidevine] for what the player does with it.
 */
data class ResolvedDrm(
    val licenseUrl: String,
    val licenseHeaders: Map<String, String> = emptyMap(),
)

/** A plugin stream's DRM, or null for a clear one (Magis and every v1 plugin leave the license blank). */
internal fun pluginDrm(playable: com.arkiv.player.data.gateway.GatewayPlayable): ResolvedDrm? =
    playable.drmLicenseUrl.takeIf { it.isNotBlank() }?.let { ResolvedDrm(it, playable.drmLicenseHeaders) }

/** Extras of a resolved source (subtitles + sniffed headers) to attach in the UI. Despite the
 *  "web" name, [PlayerViewModel.loadMagis] also uses it for the subtitles the portal brings. */
data class WebExtras(
    val episodeId: String,
    val headers: Map<String, String>,
    val subtitles: List<ResolvedSub>,
    /** Plugins only (apiVersion 1, optional); empty for Magis and every other source. */
    val audioTracks: List<ResolvedAudioTrack> = emptyList(),
    /** Plugins only (apiVersion 2, the `drm` capability); null for Magis and every clear stream. */
    val drm: ResolvedDrm? = null,
    /** Own M3U live channels only (a `#KODIPROP` ClearKey key); null for every other source. */
    val clearKey: ResolvedClearKey? = null,
) {
    /** The languages the SOURCE declared for its subtitles (the online ones, added after them, aside). */
    val declaredLanguages: List<String> get() = subtitles.filterNot { it.online }.map { it.lang }
}

/**
 * What's playing from Caracol: which episode it is, where to start from, and what the source
 * resolved (the manifest URL and the Widevine license). Goes in a single value so the screen never
 * sees one episode's URL with another's position.
 */

/**
 * What to show the person when a channel doesn't open.
 *
 * This used to guess "this TV isn't linked" by checking whether the gateway config was still at
 * its baked-in default. This branch has no gateway, and the real reason a channel doesn't open --
 * the only one the person can fix -- is not having a Magis account linked: the portal rejects live
 * with an anonymous session (`aaa100028`), even though VOD works fine with it. Hence asking about
 * the account and not the portal's error: it's the actionable thing, and doesn't depend on parsing
 * messages or codes.
 */
fun liveErrorMessage(
    hasMagisAccount: Boolean,
    channelName: String,
): String =
    if (!hasMagisAccount) {
        "El canal en vivo necesita una cuenta de Xuper vinculada (con el VOD alcanza sin ella). " +
            "Vincúlala en Ajustes, Cuenta."
    } else {
        "No se pudo abrir $channelName"
    }

/**
 * What a failed plugin `resolve()` means for the screen (spec §1.3, §3.6): which dialog, if any.
 * Pure and out here -- like [liveErrorMessage]/[shouldMarkInProgress] above -- on purpose:
 * `PlayerViewModel` can't be instantiated in a JVM unit test (its `repo: ArkivRepository` needs a
 * real `ArkivDatabase`, and this module has no Room or Robolectric infrastructure in its JVM unit
 * tests -- see `RecommendationQueryTest`'s KDoc), so this is where `loadPlugin`'s failure branches
 * can actually be pinned down with tests.
 */
internal sealed interface PluginLoadFailure {
    /** `geo_blocked`, or any portal/plugin region block: the same dialog as Magis's. */
    data class Blocked(val message: String) : PluginLoadFailure

    /** `auth_required`, or a required setting left empty: offers the plugin's Configurar screen. */
    data class SetupRequired(val pluginId: String, val message: String) : PluginLoadFailure

    /** Everything else: a timeout, an unknown typed error, a contract violation, a crash. */
    data class Generic(val message: String) : PluginLoadFailure

    companion object {
        /**
         * [pluginDisplayName] is only used for the two messages that don't already name the plugin; [place] is
         * where the Plugins screen lives on this device (`PluginsPlace`).
         */
        fun from(
            failure: Throwable?,
            pluginDisplayName: String,
            place: String = com.arkiv.player.data.plugin.PluginsPlace.current,
        ): PluginLoadFailure = when (failure) {
            is GatewayBlockedException -> Blocked(failure.message.orEmpty())
            is com.arkiv.player.data.plugin.PluginSetupRequiredException ->
                SetupRequired(failure.pluginId, failure.message ?: "Configura $pluginDisplayName en $place")
            // PluginContentSource already words these for the person ("<plugin>: …", "<plugin> no respondió a tiempo").
            else -> Generic(failure?.message?.takeIf { it.isNotBlank() } ?: "No se pudo abrir esto con $pluginDisplayName")
        }
    }
}

/**
 * Whether a PLUGIN item's ExoPlayer failure should resolve the stream again instead of failing
 * outright (spec §3.5): only when there's a tracked [expiry] AND it says so ([kind] narrows this
 * to plugin titles -- Magis/Ditu/local/live never carry a [PluginStreamExpiry]). Pure, next to
 * [PluginLoadFailure] for the same reason.
 */
internal fun shouldRetryPluginStream(
    kind: SourceKind?,
    expiry: com.arkiv.player.data.plugin.PluginStreamExpiry?,
    nowMs: Long,
): Boolean = kind == SourceKind.PLUGIN && expiry != null && expiry.shouldResolveAgain(nowMs)

/**
 * The message to stop playback with when the Xuper live gate is closed ([gateMessage] non-null,
 * see `XuperLiveGate.blockedMessage`), or null to leave it alone. Only a native Xuper channel
 * ([SourceKind.LIVE], `live:<code>`) is ever stopped: Caracol live, a plugin's live channel (a
 * `live` card, or the En vivo module's `live:plugin:<id>:<code>`) and any VOD title answer null
 * whatever the gate says. [loadedEpisodeId] is what the player has loaded (null before the first
 * load). Asked when the gate flips mid-playback and again right after a channel's open returns,
 * so an open racing the gate is never published.
 */
internal fun xuperLiveStopMessage(loadedEpisodeId: String?, gateMessage: String?): String? =
    if (loadedEpisodeId != null && PlayerSource.kindFor(loadedEpisodeId) == SourceKind.LIVE &&
        LiveChannelKeys.parse(loadedEpisodeId.removePrefix(PlayerSource.LIVE_PREFIX))?.first == LiveChannelKeys.XUPER
    ) gateMessage else null

// `internal constructor` because of [dituSource]: its type is internal to the module, and a public
// constructor can't take it.
class PlayerViewModel internal constructor(
    private val repo: ArkivRepository,
    private val archiveCacheProxy: ArchiveCacheProxy,
    private val localLibrary: com.arkiv.player.data.local.LocalLibrary,
    private val localFileServer: com.arkiv.player.playback.LocalFileServer,
    private val frameCapturer: com.arkiv.player.thumbnails.FrameCapturer,
    // Task 14 (live mode): stuck at the end so the positional params above don't get reordered
    // (PlayerScreen's call site passes them by position, not by name).
    private val liveController: LiveController,
    private val liveRecentDao: LiveRecentDao,
    // Does this process run on an Android TV? Read by the screens that draw differently.
    private val isTv: Boolean = false,
    // Sub-project 2A: what's playable comes from here, straight from the portal.
    private val source: com.arkiv.player.data.gateway.ContentSource,
    /** Whether a Magis account is linked on this device. Only decides what the error says when a
     *  live channel doesn't open (see [liveErrorMessage]): live requires it, VOD doesn't. */
    private val hasMagisAccount: () -> Boolean = { false },
    /** The fun facts (sub-project 4). Null in tests that don't use it: without it there's no button. */
    private val triviaFacts: com.arkiv.player.data.trivia.TriviaFacts? = null,
    /** Whether "Datos curiosos" is enabled in Settings. Read per load; when false the facts aren't
     *  even requested (no model call, no badge/panel/button). Defaults on for tests. */
    private val funFactsEnabled: () -> Boolean = { true },
    /** Installed plugins: whether a saved plugin title can play, and the plugin's name. */
    private val plugins: com.arkiv.player.data.plugin.PluginPlayback? = null,
    /**
     * Non-null while the native Xuper live channels are off (`AppGraph.xuperLiveBlocked`, the
     * Xuper plugin disabled/damaged/uninstalled): a Xuper channel on screen stops and shows this
     * message (see [stopXuperLive]). Caracol and plugin live channels never read it.
     */
    private val xuperLiveBlocked: StateFlow<String?> = MutableStateFlow(null),
    /** The En vivo module: opens a plugin channel from zapping and notices its provider leaving. Null in tests that don't play live. */
    private val liveModule: com.arkiv.player.data.live.LiveModule? = null,
    /**
     * Asks the person about a host the player reached while a plugin stream played that the plugin
     * never declared (`AppGraph.streamHostApproval`). Null (tests): such a refusal is a plain error.
     */
    hostDecider: com.arkiv.player.data.plugin.StreamHostDecider? = null,
    /**
     * Outlives this ViewModel (`AppGraph.applicationScope`): where the early history mark of an
     * attempt that never played is taken back once the player has closed (see [PlaybackAttempts]).
     */
    private val historyScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    /** Starts `AppGraph.pluginCastProxy` (idempotent), so a plugin title's cast shape has a port. */
    private val startPluginCastProxy: () -> Unit = {},
    /**
     * A plugin stream's MIME from its first bytes (`PluginCastProxy.probeMime`: the plugin's gated
     * client and headers), for a cast decision when neither its MIME nor its URL says. Blocking.
     */
    private val probePluginMime: (url: String, headers: Map<String, String>, hosts: com.arkiv.player.data.plugin.EffectiveHosts) -> String? =
        { _, _, _ -> null },
    /** Is a cast session up right now? A live channel's stream is only probed then (see [pluginCastProbe]). */
    private val castingNow: () -> Boolean = { false },
    /** The online subtitles a title got before (still on disk), re-added as tracks when it reopens. */
    private val onlineSubtitlesFor: (String) -> List<ResolvedSub> = { emptyList() },
) : ViewModel() {

    /**
     * An online subtitle the person just downloaded for [episodeId]: added after the source's own,
     * so the player adds it as a track in place (see `StreamExoPlayer`) and a cast offers it to the
     * TV (`CastSubtitlesSync`). Ignored when another title is on screen by now, or it is already there.
     */
    fun addOnlineSubtitle(episodeId: String, sub: ResolvedSub) {
        val extras = _webExtras.value ?: return
        if (extras.episodeId != episodeId || extras.subtitles.any { it.url == sub.url }) return
        _webExtras.value = extras.copy(subtitles = extras.subtitles + sub)
    }

    /** [episodeId]'s remembered online subtitles, read off the main thread; none on any failure. */
    private suspend fun rememberedOnlineSubtitles(episodeId: String): List<ResolvedSub> =
        withContext(Dispatchers.IO) { runCatching { onlineSubtitlesFor(episodeId) }.getOrDefault(emptyList()) }

    /** One question per host per playback attempt; see [onPluginHostRefused]. */
    private val playbackHostPrompts = hostDecider?.let { com.arkiv.player.data.plugin.PlaybackHostPrompts(it) }

    /** The question [onPluginHostRefused] is waiting on, if any: one at a time, cancelled by a new [load]. */
    private var hostPromptJob: kotlinx.coroutines.Job? = null

    /** Whether the player may ask about an undeclared host at all (PlayerScreen wires the callback only then). */
    val asksAboutPlaybackHosts: Boolean get() = playbackHostPrompts != null

    /**
     * The plugin stream on screen had a request refused ONLY because its host is undeclared and
     * askable (`UndeclaredPlaybackHostException`, see `StreamExoPlayer.onUndeclaredHost`): an HLS
     * playlist's segments on another CDN, a redirect hop. The player is stopped on that error; the
     * person is asked with the same dialog as for a returned Stream ([PlaybackHostPrompts], no time
     * limit, same per-plugin memory). A "yes" republishes the item with the plugin's hosts read
     * afresh and [positionMs] as its start, which rebuilds the player's gated client and resumes
     * there; a "no" (or a host refused before, a full cap, the same host again) is an error that
     * names the host. Leaving the player cancels the question with nothing written.
     */
    fun onPluginHostRefused(host: String, positionMs: Long) {
        val prompts = playbackHostPrompts ?: return
        val item = _magisItem.value ?: return
        if (item.kind != SourceKind.PLUGIN || hostPromptJob?.isActive == true) return
        val pluginId = com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(item.episodeId) ?: return
        val name = plugins?.nameOf(pluginId) ?: "Plugin"
        val live = com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(item.episodeId)
        Log.w(PLAY, "plugin playback: $host refused for $pluginId at ${positionMs}ms -> asking")
        hostPromptJob = viewModelScope.launch {
            // A movie or episode may be offered the broad video permission; a live channel never.
            val outcome = prompts.onRefused(pluginId, name, host, offerAnyVideoHost = !live)
            // Something else took the screen meanwhile (a new title, a re-resolve): this answer is stale.
            if (_magisItem.value !== item) return@launch
            when (outcome) {
                com.arkiv.player.data.plugin.PlaybackHostOutcome.Retry -> {
                    val ready = plugins?.accessFor(pluginId) as? com.arkiv.player.data.plugin.PluginAccess.Ready
                    if (ready == null) {
                        _error.value = "$name: el video no se pudo cargar desde $host"
                        offerOtherSources(item)
                        return@launch
                    }
                    Log.w(PLAY, "plugin playback: $host approved -> rebuilding at ${positionMs}ms")
                    // The broad video permission, if the person just granted it, rides in videoHosts.
                    _magisItem.value = item.afterHostApproved(ready.hosts.declared, positionMs, live, anyVideoHost = ready.videoHosts.anyPublicVideoHost)
                }
                is com.arkiv.player.data.plugin.PlaybackHostOutcome.Fail -> {
                    Log.w(PLAY, "plugin playback: $host -> ${outcome.message}")
                    _error.value = outcome.message
                    offerOtherSources(item)
                }
            }
        }
    }

    private val _playlist = MutableStateFlow<PlaylistData?>(null)
    val playlist: StateFlow<PlaylistData?> = _playlist.asStateFlow()

    /**
     * Magis item -- played by `StreamExoPlayer` through the local proxy, without going through
     * VLC. Also carries plugin items (`kind = SourceKind.PLUGIN`), which `StreamExoPlayer` plays
     * directly with the plugin's headers on the data source.
     */
    private val _magisItem = MutableStateFlow<PlayerData?>(null)
    val magisItem: StateFlow<PlayerData?> = _magisItem.asStateFlow()

    /**
     * Live channel (Task 1, light-magis pruning) -- played by ExoPlayer through
     * [LiveHlsProxy], without going through VLC. Replaces [_playlist] for [SourceKind.LIVE]: before
     * this task [openCurrentChannel] published a single-item `PlaylistData` for VLC to play, the
     * same way Magis VOD did before its own migration (see [_magisItem]).
     *
     * `startPositionMs` is always 0 -- a live stream has no "where you were" (see
     * [openCurrentChannel]'s KDoc), so unlike [_magisItem] this item needs no resume.
     */
    private val _liveItem = MutableStateFlow<PlayerData?>(null)
    val liveItem: StateFlow<PlayerData?> = _liveItem.asStateFlow()

    /** Resolution error (no source found on Magis, a legacy id from a removed source, etc.) for the screen to show. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Whether [error] is specifically "this live channel needs a linked Magis account" (see
     * [liveErrorMessage]): the app has no upfront account offer any more, so this is what tells
     * the screen to show a "Vincular cuenta" action next to the error, instead of a dead-end
     * message. Set alongside [_error] in [openCurrentChannel]'s failure path, and reset to `false`
     * at every point that resets [_error] to start a fresh load -- see those call sites.
     */
    private val _needsMagisAccount = MutableStateFlow(false)
    val needsMagisAccount: StateFlow<Boolean> = _needsMagisAccount.asStateFlow()

    /**
     * A [GatewayBlockedException] from either playback path (VOD's `loadMagis`, live's
     * `openCurrentChannel`): the portal itself is refusing (region-blocked, see
     * `PORTAL_ERROR_MESSAGES`'s KDoc), not a resolution failure on this end. Kept separate from
     * [_error] on purpose -- the screen shows this one as its own dialog, not the resolution-error
     * pill, so it doesn't read as a bug in Kino.
     */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked.asStateFlow()

    fun dismissBlocked() {
        _blocked.value = null
    }

    /**
     * A plugin title that can't open until the person configures its plugin (a typed
     * `auth_required`, or a required setting left empty): the screen shows [PluginSetupPrompt.message]
     * with a "Configurar" button to that plugin's Configurar screen.
     */
    private val _pluginSetup = MutableStateFlow<PluginSetupPrompt?>(null)
    val pluginSetup: StateFlow<PluginSetupPrompt?> = _pluginSetup.asStateFlow()

    fun dismissPluginSetup() {
        _pluginSetup.value = null
    }

    /** The loaded plugin stream's expiry (spec §3.5): one more `resolve` after it, see [onMagisExoError]. */
    private var pluginExpiry: com.arkiv.player.data.plugin.PluginStreamExpiry? = null

    /**
     * Whether what's in [_error] came from a player HICCUP and not from being unable to open the
     * source.
     *
     * Two different situations that used to share one channel. "No peers" or "couldn't resolve the
     * source" mean there is NOTHING playing: the banner has to stay. [onPlaybackFailed], on the
     * other hand, fires with a PlaybackException, and some of those fix themselves -- a network
     * blip, a rebuffer the player recovers from -- with the video carrying on. There the banner is
     * left lying about a video that's fine, and on top of that it covers the controls: the bar
     * composes with `loadError == null`, so while it's on screen the D-pad never reaches the
     * slider and playback can't even be paused. Seen on the Fire TV on 2026-08-12.
     *
     * See [onPlaybackHealthy], which is what turns it off.
     */
    private var playbackHiccup = false

    // Feedback while Magis/Caracol resolve the actual playable stream (can take a moment). Named
    // after the removed web resolver this originally covered; Magis and Ditu are what set it today.
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    // Subtitles + headers for PlayerScreen to attach. Named "web" from the removed web source;
    // today it's Magis's own subtitle languages coming from the gateway (see WebExtras's KDoc).
    private val _webExtras = MutableStateFlow<WebExtras?>(null)
    val webExtras: StateFlow<WebExtras?> = _webExtras.asStateFlow()

    /**
     * Fun facts about what's being watched, or empty. They're requested ALL AT ONCE on startup and
     * the screen advances between them by press (see [PlayerTrivia]): moving to the next one can't
     * cost the ~20 s the model takes, nor fail in the middle of a movie.
     */
    private val _trivia = MutableStateFlow<List<String>>(emptyList())
    val trivia: StateFlow<List<String>> = _trivia.asStateFlow()

    /** Cancelable: on jumping chapters, the previous one's batch is no longer any good. */
    private var triviaJob: kotlinx.coroutines.Job? = null

    /** Loads the episode as a playlist, branching by source (Magis vs unknown/legacy id). */
    fun load(episodeId: String) {
        clearTrivia()
        // A host question for the previous title belongs to nobody now.
        hostPromptJob?.cancel()
        // A new title gets its own network re-resolves (see [onVodNetworkReResolve]).
        networkReResolves.reset()
        // What the live gate asks about (see [xuperLiveStopMessage]): only a `live:` id is stoppable.
        loadedEpisodeId = episodeId
        _otherSources.value = null
        val attempt = attempts.open(episodeId)
        // Live mode (Task 14): CUTS OFF HERE, before touching anything on the VOD path below.
        if (PlayerSource.kindFor(episodeId) == SourceKind.LIVE) {
            viewModelScope.launch { withContext(NonCancellable) { attempts.begin(attempt, writeMark = false) } }
            loadLive(episodeId.removePrefix(PlayerSource.LIVE_PREFIX))
            return
        }
        viewModelScope.launch {
            // Before anything: so the detail screen knows which chapter you're on even if you
            // leave right away.
            //
            // Unless it shouldn't be recorded. This is the THIRD path that writes to history, the
            // one that slipped past the other two: it doesn't write position or duration -- the
            // row stays at 0 -- but it DOES write `lastPlayedAt`. Until Task 5 `playback` traveled
            // through cloud sync, so this also sent the record to the cloud and to other devices;
            // without sync the effect stays local, but the row still ends up marked with when this
            // was watched. Found playing for real on the Fire TV on 2026-08-14: the progress, frame
            // and library guards all held, and this row showed up anyway.
            //
            // [shouldLogHistory] doesn't work here: this runs BEFORE resolving the source, while
            // `_playlist` is still the previous episode's (or null), so asking it would answer
            // "don't know" → log, exactly the opposite of what's needed. What IS known at this
            // point is the ephemeral pending item, which the screen left before navigating.
            //
            // A Caracol live channel isn't marked either: see [shouldMarkInProgress].
            //
            // The mark belongs to this attempt ([attempts]): if the attempt ends without ever playing
            // -- the source failed, the person left while it resolved -- it's taken back. Not
            // cancellable: leaving mid-write must not leave a mark nobody knows to take back.
            val mark = shouldMarkInProgress(episodeId, MagisEphemeral.take(episodeId)?.adulto)
            withContext(NonCancellable) { attempts.begin(attempt, writeMark = mark) }
            _error.value = null
            _needsMagisAccount.value = false
            _magisItem.value = null
            // Navigating from a live channel to a VOD episode without going through another screen
            // (the same ViewModel survives, see the guard above): without this reset, `liveItem`
            // kept publishing the last channel and PlayerScreen (isLive/isLiveExo) still believed it.
            _liveItem.value = null
            // A new title starts with a fresh plugin-live reopen budget, and a reopen scheduled
            // for the previous channel must not fire into this one.
            pluginLiveReopenJob?.cancel()
            pluginLiveReopens.reset()
            playbackHiccup = false
            // If it's saved on the device, it wins over any streaming. Goes BEFORE branching by
            // source: no matter where the file came from, it's already here.
            //
            // UNKNOWN stays out on purpose: ids of sources removed from this branch always get
            // the "no longer available" error below, even if an old completed download for that
            // id is still on disk (LocalLibrary.fileFor doesn't filter by source). Playing that
            // old file would be a behavior change, not part of this cleanup.
            val kind = PlayerSource.kindFor(episodeId)
            if (kind != SourceKind.UNKNOWN) {
                val local = localLibrary.fileFor(episodeId)
                if (local != null) { loadLocal(episodeId, local); return@launch }
            }
            // After the download detour, on purpose: a file already on the device carries no
            // trivia (see [PlayerTrivia.wantsFacts]). Gated by the Settings toggle: off = not even
            // requested (no model call, nothing on screen).
            if (funFactsEnabled() && PlayerTrivia.wantsFacts(episodeId, kind)) loadTrivia(episodeId)
            Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
            when (kind) {
                SourceKind.UNKNOWN -> loadUnknownSource(episodeId)
                SourceKind.MAGIS -> loadMagis(episodeId)
                SourceKind.PLUGIN -> loadPlugin(episodeId)
                // kindFor() never returns LOCAL: a downloaded file is detected above by
                // localLibrary.fileFor(). The branch exists because the `when` is exhaustive.
                SourceKind.LOCAL -> loadUnknownSource(episodeId)
                // Unreachable: live channels return before this launch (see the guard above).
                SourceKind.LIVE -> Unit
            }
            // Nothing to play came out of it (resolve failed, a blocked plugin, a missing ref, a
            // refused host): the attempt is over now. A plugin title also offers its other sources,
            // read before the undo, which may take a card the pick just created with it.
            if (!publishedFor(episodeId, kind)) {
                if (kind == SourceKind.PLUGIN && !com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(episodeId) &&
                    attempts.isCurrent(attempt)
                ) {
                    _otherSources.value = runCatching { repo.sourceSearchTitle(episodeId) }.getOrNull()
                }
                withContext(NonCancellable) { attempts.failed(attempt) }
            }
        }
    }

    /** Whether the load of [episodeId] published something for a player to open. */
    private fun publishedFor(episodeId: String, kind: SourceKind): Boolean = when (kind) {
        SourceKind.MAGIS, SourceKind.PLUGIN -> _magisItem.value?.episodeId == episodeId
        SourceKind.UNKNOWN, SourceKind.LOCAL, SourceKind.LIVE -> false
    }

    /**
     * This player's play attempts and the early history mark each one writes; see [PlaybackAttempts].
     * The undo runs in [historyScope] when the player closes, since this ViewModel's scope is dead by then.
     */
    private val attempts = PlaybackAttempts(
        mark = { id -> runCatching { repo.markInProgress(id) }.getOrNull() },
        undo = { written ->
            Log.w(PLAY, "history: ${written.episodeId} never played -> taking back its early mark (had a row before: ${written.previous != null})")
            runCatching { repo.undoInProgress(written) }.onFailure { Log.w(PLAY, "history: undo failed: ${it.message}") }
        },
    ).also { it.followCastStarts(viewModelScope) }

    /**
     * A plugin title whose source didn't open: the title to look up in every source ("Ver otras
     * fuentes" on the error), or null. Reset on every [load].
     */
    private val _otherSources = MutableStateFlow<com.arkiv.player.data.SourceSearchTitle?>(null)
    val otherSources: StateFlow<com.arkiv.player.data.SourceSearchTitle?> = _otherSources.asStateFlow()

    /** The previous episode's can't stay on screen with the next one's. */
    private fun clearTrivia() {
        triviaJob?.cancel()
        _trivia.value = emptyList()
    }

    /**
     * Requests the batch of fun facts, best-effort. Swallows any failure: with no facts the button
     * isn't drawn, the good failure for something accessory. Doesn't swallow
     * `CancellationException`: that would leave a coroutine running that its scope already
     * considers dead.
     */
    private fun loadTrivia(episodeId: String) {
        clearTrivia()
        val facts = triviaFacts ?: return
        triviaJob = viewModelScope.launch {
            val subject = try {
                repo.triviaSubjectFor(episodeId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: couldn't identify the title: ${e.message}")
                null
            } ?: run {
                // Without this line, trivia would silently turn off: no button and nothing in the
                // log saying why (the item has neither a tmdbId nor a canonical title).
                Log.w(PLAY, "trivia: no titled work for $episodeId → not requested")
                return@launch
            }
            _trivia.value = try {
                facts.of(subject) { repo.workSheetFor(subject) }
                    .also { Log.w(PLAY, "trivia: ${it.size} facts for ${subject.key}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }
    }

    // --- Live mode (Task 14) ---------------------------------------------------------------
    // Isolated from the rest of the file on purpose (see the guard at the start of load()): none
    // of this participates in VOD playlists, casting, local downloads or resume -- concepts that
    // don't exist in live. See LiveController/LiveZapping's KDoc for the full reasoning.

    /** Zapping in progress -- null outside live mode. */
    private var zapping: LiveZapping? = null

    /** Cancelable job for preheating the neighbors -- see [preheatNeighbors]'s KDoc. */
    private var preheatJob: kotlinx.coroutines.Job? = null

    private val _liveChannel = MutableStateFlow<LiveChannel?>(null)

    /** The channel on screen right now (code/name/number/logo), for PlayerScreen's overlay. */
    val liveChannel: StateFlow<LiveChannel?> = _liveChannel.asStateFlow()

    /** The id of what's loaded now ([load], or the channel [goToChannel] picked): see [xuperLiveStopMessage]. */
    private var loadedEpisodeId: String? = null

    init {
        // The Xuper plugin switched off mid-channel: the channel stops right away (no restart,
        // no waiting for the next reopen) and the blocked dialog says why.
        viewModelScope.launch {
            xuperLiveBlocked.collect { gate -> xuperLiveStopMessage(loadedEpisodeId, gate)?.let(::stopXuperLive) }
        }
        // A plugin switched off or uninstalled mid-channel: its channel stops with the module's
        // blocked message. Xuper's own stop is the collector above (step 1's gate), untouched.
        liveModule?.let { module ->
            viewModelScope.launch {
                module.providers.collect { list ->
                    val channel = _liveChannel.value
                    if (channel != null && channel.provider != LiveChannelKeys.XUPER && providerGone(channel, list.map { it.id })) {
                        stopPluginChannel(module.blockedMessage(channel.provider))
                    }
                }
            }
        }
    }

    /**
     * Stops the plugin channel on screen because its provider left the module (spec R13): cancels
     * its open and its pending reopen, drops the published item and shows [message].
     */
    private fun stopPluginChannel(message: String) {
        Log.w(PLAY, "live: the provider of ${_liveChannel.value?.liveCode} is gone → stopping")
        pluginOpenJob?.cancel()
        pluginLiveReopenJob?.cancel()
        _resolving.value = false
        _magisItem.value = null
        _error.value = null
        _blocked.value = message
    }

    /**
     * Stops the Xuper channel on screen because the live gate closed: drops the published item
     * (PlayerScreen disposes its ExoPlayer), cancels preheats and pending reopens, and shows
     * [message] in the blocked dialog -- dismissing it leaves the player, like any blocked channel.
     */
    private fun stopXuperLive(message: String) {
        Log.w(PLAY, "live: the Xuper plugin is off → stopping ${zapping?.current?.code}")
        LiveLog.w("GATE: the Xuper plugin is off, the channel stops")
        preheatJob?.cancel()
        reopenJob?.cancel()
        _liveItem.value = null
        _error.value = null
        _needsMagisAccount.value = false
        _blocked.value = message
    }

    private val _liveGeneration = MutableStateFlow(0)

    /**
     * Bumps on EVERY live channel load: opening, zapping, and reopening after a cut.
     *
     * Exists because [playlist] isn't enough to signal a reopen: the `PlaylistData` published on
     * reopening the same channel is equal to the previous one and `StateFlow` doesn't emit equal
     * values. The screen watches both things, so a load always reaches it even when the content
     * hasn't changed by a single byte.
     */
    val liveGeneration: StateFlow<Int> = _liveGeneration.asStateFlow()

    /**
     * Starts zapping over the list the person ENTERED with ([LiveZappingSource]), narrowed to the
     * chosen channel's provider ([zappingListFor]). [liveCode] is the route's value: a Xuper code,
     * or `plugin:<id>:<code>` ([LiveChannelKeys]). With nothing set there (process recreated, a
     * companion send, a deep link) the list is the channel alone.
     */
    private fun loadLive(liveCode: String) {
        val chosen = channelForLiveCode(liveCode, LiveZappingSource.list)
            ?: run { _error.value = "No se encontró el canal"; return }
        val list = zappingListFor(LiveZappingSource.list, chosen)
        zapping = LiveZapping(list, list.indexOfFirst { it.liveCode == chosen.liveCode }.coerceAtLeast(0))
        openCurrentChannel()
    }

    /**
     * Opens zapping's current channel: resolves against [liveController] and publishes a single-
     * item [PlayerData] that always starts at 0 -- live has no "where you were" to resume. Does
     * NOT poll for duration (there isn't one) and does NOT save progress (see [saveProgress],
     * which PlayerScreen no longer calls in live mode). Logs the channel in [liveRecentDao] -- the
     * only thing that fills the grid's "Recientes" chip, which until this task nobody wrote to.
     *
     * Task 1 (light-magis pruning): publishes [_liveItem], not [_playlist] -- the live channel is
     * played by ExoPlayer through [LiveHlsProxy] (same pattern as [_magisItem] for VOD), without
     * going through VLC. `url` already comes out of [liveController] with the local proxy's
     * host/port/token injected; ExoPlayer needs no headers of its own because the proxy sets them
     * itself against the CDN -- that's [LiveHlsProxy]'s whole reason to exist (see its KDoc).
     */
    private fun openCurrentChannel() {
        // A zap still settling is superseded by this open (it goes straight through from here).
        zapSettle.cancel()
        // A plugin open still in flight belongs to the channel zapping just left; loadPlugin may
        // have turned `resolving` on, and nothing else would turn it off.
        pluginOpenJob?.let { if (it.isActive) { it.cancel(); _resolving.value = false } }
        // Likewise a host question the previous channel's player raised.
        hostPromptJob?.cancel()
        val channel = zapping?.current ?: return
        if (channel.provider != LiveChannelKeys.XUPER) { openPluginChannel(channel); return }
        _liveChannel.value = channel
        // Another channel is a new zap: a new id in the live log and a clock that runs until the first frame.
        // The SAME channel again is a reopen after a cut and stays inside the story it belongs to.
        if (channel.code != LiveLog.channel) {
            LiveLog.newSession(channel.code, channel.name)
        } else {
            LiveLog.reopen(liveReopens)
        }
        viewModelScope.launch {
            _error.value = null
            _needsMagisAccount.value = false
            playbackHiccup = false
            // Review fix (Task 1): just like loadMagis() discards the rival VOD source BEFORE
            // publishing its own, here EVERY VOD source has to be discarded before publishing
            // `_liveItem`. Without this, entering live without recomposing the screen
            // (goToChannel()/zapNext()/zapPrevious() call openCurrentChannel() directly, without going
            // through load()'s reset) left `_magisItem`/`_playlist` with the old value.
            // PlayerScreen.activePlayer checks magisItem BEFORE liveItem, so a stale `_magisItem`
            // would win that decision and the live channel would never show -- and a stale
            // `_playlist` could reactivate VOD's `LaunchedEffect(playlist, liveGeneration)` against
            // already-abandoned content.
            _playlist.value = null
            _magisItem.value = null
            val openStartedAt = System.currentTimeMillis()
            val url = runCatching { liveController.open(channel.code) }.getOrElse {
                Log.w(PLAY, "openCurrentChannel() failed for ${channel.code}: ${it.message}")
                LiveLog.e("open FAILED after ${System.currentTimeMillis() - openStartedAt}ms: ${it.javaClass.simpleName}: ${it.message}")
                if (zapping?.current?.liveCode == channel.liveCode) {
                    if (it is GatewayBlockedException) {
                        _blocked.value = it.message
                    } else {
                        val linked = hasMagisAccount()
                        _needsMagisAccount.value = !linked
                        _error.value = liveErrorMessage(linked, channel.name)
                    }
                }
                return@launch
            }
            // Fast zaps: if by the time this open() (~3s worst case) comes back the user already
            // zapped to ANOTHER channel, this late response must not override what's on screen --
            // same pattern (and same reason) as LiveViewModel.cargar()'s categoriaActiva, see its
            // KDoc.
            LiveLog.i("open: session resolved in ${System.currentTimeMillis() - openStartedAt}ms")
            if (zapping?.current?.liveCode != channel.liveCode) {
                LiveLog.w("open: the person already zapped to another channel, this late answer is dropped")
                return@launch
            }
            // The gate closed while this open was in flight (past LiveController's own check):
            // never publish a channel the person just switched off.
            xuperLiveStopMessage("${PlayerSource.LIVE_PREFIX}${channel.code}", xuperLiveBlocked.value)?.let { message ->
                _blocked.value = message
                return@launch
            }
            val item = PlayerData(
                episodeId = "${PlayerSource.LIVE_PREFIX}${channel.code}",
                itemId = "${PlayerSource.LIVE_PREFIX}${channel.code}",
                title = channel.name,
                subtitle = "",
                mediaUrl = url,
                // A live channel never has an h.264 mp4 backup -it's a live stream, not a file-, so
                // castUrl is always null. That does NOT mean it can't cast (Task 18):
                // PlayerScreen.castRequestFor resolves the local proxy's LAN-reachable URL
                // (LiveHlsProxy.lanUrl) on its own -- see CastRequestBuilder's KDoc.
                castUrl = null,
                artworkUrl = channel.logo.orEmpty(),
                openingStartMs = null, openingEndMs = null, endingStartMs = null,
                kind = SourceKind.LIVE,
            )
            // No `requested` (unlike the old PlaylistData): live never goes through
            // MediaReusePolicy, which was that mark's only consumer.
            _liveItem.value = item
            // And the notice that A LOAD HAPPENED HERE, even if the value above is identical to
            // what was already there. Reopening a cut channel produces a [PlayerData] **equal** to
            // the previous one -same channel, and `mediaUrl` is the local proxy's url, whose port
            // and token live as long as the socket does-, and a `StateFlow` drops equal values: the
            // screen never found out, never reloaded the MediaItem, and the reopen stayed in the
            // log with nothing actually playing. Measured on the Fire TV on 2026-08-14: `canal →`
            // at 22:19:20 and then silence, with the media session frozen at pos=99631ms.
            _liveGeneration.value++
            // An adult channel is NOT recorded. And it's solved by NOT WRITING instead of
            // filtering on read: what isn't written can't leak through a screen we forgot about
            // -- "Recents" is drawn in the guide, in the drawer and on the phone. Until Task 5 it
            // also never got uploaded to the cloud, so it wouldn't show up on the account's other
            // devices either; without cloud sync that specific risk is gone, but the risk on THIS
            // device's own screens (above) is still reason enough not to write it. Filtering on
            // read leaves the data sitting there, waiting for the first place that forgets to
            // filter.
            // Through [AdultContent] and not a standalone `!canal.adult`: the rule is the same as
            // progress's and frames', and keeping it written in one single place is what stops one
            // of the three from being fixed tomorrow while the other two aren't.
            recordRecent(channel)
            preheatNeighbors()
        }
    }

    /** The in-flight open of a plugin channel: a newer zap cancels it (see [openPluginChannel]). */
    private var pluginOpenJob: kotlinx.coroutines.Job? = null

    /**
     * A plugin channel from the En vivo module (spec §1): its provider finds the channel's live ref
     * or its known stream ([com.arkiv.player.data.live.PluginLiveProvider.open]). It is handed over
     * through [com.arkiv.player.playback.PluginLive] and played by [loadPlugin] on `StreamExoPlayer`:
     * the same path, host gate and [onPluginLiveError] recovery as a plugin's `live` card. None of
     * Xuper's proxy/seed/preheat machinery applies. A newer zap cancels this open, and a late
     * answer never replaces the channel now on screen. Recents are keyed by the live code (code +
     * provider), never by the plugin's ref.
     */
    private fun openPluginChannel(channel: LiveChannel) {
        _liveChannel.value = channel
        pluginOpenJob = viewModelScope.launch {
            _error.value = null
            _needsMagisAccount.value = false
            playbackHiccup = false
            _playlist.value = null
            _liveItem.value = null
            preheatJob?.cancel()
            reopenJob?.cancel()
            pluginLiveReopenJob?.cancel()
            pluginLiveReopens.reset()
            val plugin = when (val result = openModuleChannel(liveModule, channel)) {
                is ModuleChannelOpen.Gone -> {
                    _magisItem.value = null
                    _blocked.value = result.message
                    return@launch
                }
                is ModuleChannelOpen.Failed -> {
                    Log.w(PLAY, "openPluginChannel() ${channel.liveCode} failed: ${result.error.message}")
                    if (zapping?.current?.liveCode == channel.liveCode) {
                        _magisItem.value = null
                        when (val outcome = PluginLoadFailure.from(result.error, result.providerName, com.arkiv.player.data.plugin.PluginsPlace.of(isTv))) {
                            is PluginLoadFailure.Blocked -> _blocked.value = outcome.message
                            is PluginLoadFailure.SetupRequired -> _pluginSetup.value = PluginSetupPrompt(outcome.pluginId, outcome.message)
                            is PluginLoadFailure.Generic -> _error.value = outcome.message
                        }
                    }
                    return@launch
                }
                is ModuleChannelOpen.Opened -> result.opening as? com.arkiv.player.data.live.LiveOpening.Plugin ?: run {
                    // A plugin provider only ever answers Plugin; Xuper never reaches this path.
                    Log.w(PLAY, "openPluginChannel() ${channel.liveCode}: ${result.opening::class.simpleName} is not a plugin opening")
                    return@launch
                }
            }
            if (zapping?.current?.liveCode != channel.liveCode) return@launch
            // A reopen the previous channel scheduled while this one was opening must not fire now.
            pluginLiveReopenJob?.cancel()
            pluginLiveReopens.reset()
            // Through null first, like every plugin re-resolve: the previous channel's item must
            // not stay on screen while this one resolves.
            _magisItem.value = null
            com.arkiv.player.playback.PluginLive.leave(plugin.channel)
            loadPlugin(plugin.channel.episodeId)
            val mine = _magisItem.value?.episodeId == plugin.channel.episodeId
            if (zapping?.current?.liveCode != channel.liveCode) {
                if (mine) _magisItem.value = null
                return@launch
            }
            if (mine) recordRecent(recentOf(channel, plugin))
        }
    }

    /**
     * Logs [channel] in the recents, keyed by code + provider. An adult channel is NOT recorded,
     * through [AdultContent] (see the KDoc at [openCurrentChannel]'s call).
     */
    private suspend fun recordRecent(channel: LiveChannel) {
        if (AdultContent.shouldLog(channel.adult)) {
            runCatching {
                liveRecentDao.record(LiveRecentEntity(channel.code, channel.name, System.currentTimeMillis(), provider = channel.provider))
            }
        }
    }

    /**
     * A plugin channel's open waits for the zapping to settle ([ZAP_SETTLE_MS] without another zap): the channel card follows every
     * key at once, but the plugin is asked to resolve only the one the person stopped on. Xuper's channels open at once.
     */
    private val zapSettle = SettleDebounce(viewModelScope, ZAP_SETTLE_MS)

    private fun openAfterZap() {
        val channel = zapping?.current ?: return
        if (channel.provider == LiveChannelKeys.XUPER) { zapSettle.cancel(); openCurrentChannel(); return }
        _liveChannel.value = channel
        zapSettle.run { openCurrentChannel() }
    }

    /** Zapping: next/previous in the list entered with. No effect outside live mode. */
    fun zapNext() { zapping?.next() ?: return; openAfterZap() }
    fun zapPrevious() { zapping?.previous() ?: return; openAfterZap() }

    /** Consecutive reopens of the current channel with no picture back yet, and which channel they're for. */
    private var liveReopens = 0
    private var counterChannel: String? = null
    private var reopenJob: kotlinx.coroutines.Job? = null

    /**
     * When the picture-less gap we're trying to cover started (0 = none in progress).
     *
     * The number that measures what the person SEES. `pos` and the CDN's codes count what happened
     * internally; this counts how many seconds the screen went without advancing, which is the
     * only thing live's usability gets judged by.
     */
    private var cutSince = 0L

    /**
     * The live stream cut out: reopen it, because a live stream doesn't end.
     *
     * An `EndReached` in live is never "the content ended" -- it's that the player ran out of
     * data. Until now that left the channel dead right there: the screen froze and the only way
     * out was going back and entering again. Measured on the Fire TV on 2026-08-14, four times in
     * a row with RCN FHD: that signal's origin failed intermittently -- 404s on the segments and
     * even on the playlist -- and recovered on its own within seconds. So what was missing wasn't
     * a better guess at the failure, it was trying again.
     *
     * Three reopens with doubling waits (2 s, 4 s, 8 s): covers a gap of ~15 s, in the ballpark of
     * what was measured. On the fourth cut it warns on screen instead of continuing. Retrying with
     * no cap would leave a dead channel looping forever, burning data and never saying what's going
     * on -- silence is worse than the error.
     *
     * The budget is PER CHANNEL ([counterChannel]) and gets fully replenished as soon as the
     * channel plays again ([liveIsPlaying]): if it holds for an hour and then hiccups, it starts
     * from zero.
     */
    fun reopenLiveAfterCut() {
        val channel = zapping?.current ?: return
        if (channel.liveCode != counterChannel) {
            counterChannel = channel.liveCode
            liveReopens = 0
        }
        if (liveReopens >= MAX_LIVE_REOPENS) {
            Log.w(PLAY, "live: ${channel.code} didn't come back after $MAX_LIVE_REOPENS reopens → warning")
            LiveLog.e("GAVE UP: the channel didn't come back after $MAX_LIVE_REOPENS reopens, the person sees the error")
            _error.value = "Se cortó la señal de ${channel.name} y no volvió. " +
                "Puede ser un problema del canal: prueba de nuevo o mira otro."
            return
        }
        if (cutSince == 0L) cutSince = System.currentTimeMillis()
        liveReopens++
        val wait = LiveReopenPolicy.waitMs(liveReopens)
        Log.w(
            PLAY,
            "live: ${channel.code} cut out → reopening in ${wait}ms " +
                "(attempt $liveReopens/$MAX_LIVE_REOPENS)",
        )
        LiveLog.w("CUT: the stream ran out, reopening in ${wait}ms (attempt $liveReopens/$MAX_LIVE_REOPENS)")
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            delay(wait)
            // Zapping during the wait wins: reopening the old channel here would override the one
            // the person just chose.
            if (zapping?.current?.liveCode == channel.liveCode) openCurrentChannel()
        }
    }

    /**
     * The channel is genuinely playing: its reopen budget is replenished.
     *
     * Asks for the POSITION and not a boolean because `isPlaying` used to turn true as soon as VLC
     * opened the media, before the first frame: with that, a channel that reopened and died at
     * `pos=0ms` still replenished the budget, the ceiling never ran out, and
     * [reopenLiveAfterCut]'s warning was unreachable. [MIN_HEALTHY_LIVE_MS] is the line between
     * "it recovered" and "it reopened and dropped again".
     */
    fun liveIsPlaying(positionMs: Long) {
        if (liveReopens == 0 || positionMs < MIN_HEALTHY_LIVE_MS) return
        val gap = if (cutSince > 0L) System.currentTimeMillis() - cutSince else -1L
        Log.w(
            PLAY,
            "live: recovered after ${gap}ms with no picture and $liveReopens reopen(s) " +
                "(played ${positionMs}ms) → replenishing the budget",
        )
        LiveLog.i("RECOVERED after ${gap}ms without picture and $liveReopens reopen(s)")
        liveReopens = 0
        cutSince = 0L
    }

    /**
     * The channel drawer chose another channel: changes the channel AND the list zapping moves
     * through.
     *
     * Both together on purpose. The drawer lists the whole catalog by category, so the chosen
     * channel might not be in the list entered with -- keeping the old zapping would make the
     * first up-arrow jump to a channel from another category, unrelated to what was just chosen.
     * [list] is what the drawer had on screen (already filtered by search, if there was one),
     * which is exactly what's expected to be moved through afterward.
     *
     * Also set on [LiveZappingSource] so it survives a screen recreation, which is where
     * [loadLive] reads it from.
     */
    fun goToChannel(list: List<LiveChannel>, channel: LiveChannel) {
        val entryList = zappingListFor(list, channel)
        LiveZappingSource.list = entryList
        loadedEpisodeId = "${PlayerSource.LIVE_PREFIX}${channel.liveCode}"
        zapping = LiveZapping(entryList, entryList.indexOfFirst { it.liveCode == channel.liveCode }.coerceAtLeast(0))
        openCurrentChannel()
    }

    /**
     * Preheats zapping's neighbors ~1s after opening the current channel -- if the user zaps
     * before that second passes, [openCurrentChannel] cancels this job (next call) before
     * scheduling the next one. Resolving costs ~3s (two calls to a portal cut to 1 every 1.5s, see
     * LiveController's KDoc), so it's worth getting ahead of while the user isn't actively zapping.
     * Best-effort: a neighbor that fails doesn't stop the other one from being tried, and neither
     * blocks anything -- the playlist was already published before reaching here.
     */
    private fun preheatNeighbors() {
        preheatJob?.cancel()
        // Plugin channels are not preheated: their resolve belongs to the plugin, and a resolve
        // spent in advance may expire before the person gets there.
        val neighbors = zapping?.neighbors()?.filter { it.provider == LiveChannelKeys.XUPER } ?: return
        preheatJob = viewModelScope.launch {
            delay(1000)
            neighbors.forEach { neighbor -> launch { runCatching { liveController.preheat(neighbor.code) } } }
        }
    }

    /**
     * File saved on the device. `castUrl` points at the local HTTP server and NOT at `file://`:
     * the Chromecast does its own GET from another device and can't open a path on the phone's
     * filesystem.
     */
    private suspend fun loadLocal(episodeId: String, path: String) {
        val ep = repo.getEpisode(episodeId)
        val file = java.io.File(path)
        val castUrl = withContext(Dispatchers.IO) { runCatching { localFileServer.serve(file) }.getOrNull() }
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: file.name,
            subtitle = ep?.section ?: "",
            mediaUrl = "file://$path",
            castUrl = castUrl,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.LOCAL,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.LOCAL)
        _playlist.value = PlaylistData(listOf(item), 0, startPos, requested = episodeId)
        Log.w(PLAY, "loadLocal() $episodeId -> $path (cast=$castUrl)")
    }

    /**
     * Plays nothing and reports that the source is gone. Reached for ids whose source was
     * removed from this branch (old library rows with no known prefix).
     */
    private fun loadUnknownSource(episodeId: String) {
        Log.w(PLAY, "loadUnknownSource() episodeId=$episodeId → source not available in this branch")
        _playlist.value = null
        _webExtras.value = null
        _resolving.value = false
        _error.value = "Esta fuente ya no está disponible en esta versión"
    }

    fun onMagisExoError(message: String) {
        val item = _magisItem.value
        // A plugin said its URLs expire, and this one is past that: resolve once more instead of
        // failing (spec §3.5). The reload resumes from the saved position, like any open.
        val expiry = pluginExpiry
        if (item != null && expiry != null && shouldRetryPluginStream(item.kind, expiry, System.currentTimeMillis())) {
            Log.w(PLAY, "plugin stream failed after ${expiry.expiresInSeconds}s: resolving again")
            pluginExpiry = expiry.copy(retried = true)
            // A re-resolve can bring back the EXACT SAME url (routine on a fixed-path server, e.g.
            // the reference-server plugin's `/stream/<id>.mp4`): PlayerData is a data class, so
            // `_magisItem.value = <an equal PlayerData>` is silently dropped by StateFlow (it only
            // notifies collectors when the new value differs) and the screen would never see the
            // retry happen -- StreamExoPlayer stays stuck showing its old error, with no dialog
            // either, a dead and silent player. Going through null first forces a real rebuild,
            // exactly like a fresh load() already does before any source resolves.
            _magisItem.value = null
            viewModelScope.launch { loadPlugin(item.episodeId) }
            return
        }
        val label = if (item?.kind == SourceKind.PLUGIN) {
            pluginAccessFor(com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(item.episodeId), plugins).takeIf { it is com.arkiv.player.data.plugin.PluginAccess.Ready }?.name
                ?: plugins?.nameOf(com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(item.episodeId)) ?: "Plugin"
        } else {
            "Xuper"
        }
        _error.value = "$label: $message"
        if (item != null) offerOtherSources(item)
    }

    /** Network re-resolves spent on the title on screen; see [onVodNetworkReResolve]. */
    private val networkReResolves = NetworkReResolveBudget()

    /**
     * The VOD network recovery on screen (see [VodNetworkUi]): "Sin conexión, esperando la red…"
     * while an attempt waits for a network, and "Reintentar" on a network error, which clears the
     * error (and its "Ver otras fuentes") before the player reopens.
     */
    internal val vodNetworkUi = VodNetworkUi(onRetry = {
        _error.value = null
        _otherSources.value = null
    })

    /**
     * `StreamExoPlayer`'s VOD network recovery asks for a fresh Stream (its second attempt, see
     * [VodNetworkRecovery]): the connection died after the title had played -- a pause in the
     * background, the CDN closed the idle connection, the URL/token may have expired. Only a plugin's
     * VOD title has a source to ask ([VodNetworkRecovery.canReResolve]), and only
     * [NetworkReResolveBudget]'s few times. Resolved again through [loadPlugin] at [positionMs] --
     * where the player was, not the saved progress, which can be minutes older -- and paused if it
     * was ([playWhenReady] false). False: not taken, the player re-prepares in place instead.
     *
     * The player on screen stays until the new Stream IS there: a resolve that fails (the network
     * still down) calls [reprepare] instead of showing the plugin's error -- it used to drop the
     * item first and turn a passing outage into an error screen (review 2026-10-01).
     */
    fun onVodNetworkReResolve(positionMs: Long, playWhenReady: Boolean, reprepare: () -> Unit): Boolean {
        val item = _magisItem.value ?: return false
        val live = com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(item.episodeId)
        if (!VodNetworkRecovery.canReResolve(item.kind, live, hostQuestionOpen = hostPromptJob?.isActive == true)) return false
        if (!networkReResolves.tryTake(System.currentTimeMillis())) {
            Log.w(PLAY, "network recovery: ${item.episodeId} already resolved again recently -> re-preparing instead")
            return false
        }
        Log.w(PLAY, "network recovery: ${item.episodeId} lost its connection -> resolving again at ${positionMs}ms paused=${!playWhenReady}")
        viewModelScope.launch {
            // Another title loaded meanwhile: its own load decides.
            if (loadedEpisodeId != item.episodeId) return@launch
            loadPlugin(item.episodeId, resumeAt = positionMs, startPaused = !playWhenReady, onResolveFailed = {
                Log.w(PLAY, "network recovery: resolving ${item.episodeId} failed too -> re-preparing in place")
                if (_magisItem.value === item) reprepare()
            })
        }
        return true
    }

    /** A plugin title's stream failed for good: its error offers the title's other sources. */
    private fun offerOtherSources(item: PlayerData) {
        if (item.kind != SourceKind.PLUGIN || com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(item.episodeId)) return
        viewModelScope.launch {
            val title = runCatching { repo.sourceSearchTitle(item.episodeId) }.getOrNull()
            // A new title may have loaded meanwhile: its own load decides.
            if (_magisItem.value === item) _otherSources.value = title
        }
    }

    /** The reopen budget of the plugin live channel on screen ([PluginLiveReopens]); fresh on every [load]. */
    private val pluginLiveReopens = PluginLiveReopens()
    private var pluginLiveReopenJob: kotlinx.coroutines.Job? = null

    /**
     * `StreamExoPlayer` failed while playing a plugin's live channel. The same shape as Magis live
     * ([onLiveExoError]/[reopenLiveAfterCut]), not [onMagisExoError]'s VOD rule: a channel almost
     * always comes back, so the first hiccups are never an error dialog, and `expiresInSeconds`
     * plays no part. Falling behind the live window (or a playlist reset/stall) is fixed in place
     * by the player (REJOIN_EDGE, a few times a minute); any other cut resolves the channel again
     * through the plugin's `resolve()` with the same ref after 2 s, 4 s, 8 s ([LiveReopenPolicy]);
     * only past the third failed reopen does the person read that the signal was cut. Returns the
     * decision so the player can act on the in-place one; the other two are acted on here.
     */
    internal fun onPluginLiveError(kind: LiveErrorKind, message: String): PluginLiveRecovery {
        val item = _magisItem.value
        if (item == null) {
            Log.w(PLAY, "plugin live: error with no item on screen ($kind: $message)")
            return PluginLiveRecovery.GIVE_UP
        }
        val decision = pluginLiveReopens.decide(kind)
        when (decision) {
            PluginLiveRecovery.REJOIN_EDGE ->
                Log.w(PLAY, "plugin live: ${item.episodeId} $kind ($message) → rejoining the live edge")
            PluginLiveRecovery.RE_RESOLVE -> {
                val wait = pluginLiveReopens.reopen()
                Log.w(
                    PLAY,
                    "plugin live: ${item.episodeId} cut ($kind: $message) → resolving again in ${wait}ms " +
                        "(attempt ${pluginLiveReopens.count}/${LiveReopenPolicy.MAX_REOPENS})",
                )
                pluginLiveReopenJob?.cancel()
                pluginLiveReopenJob = viewModelScope.launch {
                    delay(wait)
                    // Leaving for another title during the wait wins: `load` replaced the item. So
                    // does zapping to another En vivo channel, even while the old stream is
                    // still on screen because the new one is still opening.
                    val moduleChannel = if (loadedEpisodeId?.let(PlayerSource::kindFor) == SourceKind.LIVE) zapping?.current else null
                    if (!pluginReopenStillWanted(item.episodeId, _magisItem.value?.episodeId, moduleChannel)) return@launch
                    // Through null first: a re-resolve may bring back the exact same Stream, and an
                    // equal PlayerData would be dropped by StateFlow (see [onMagisExoError]).
                    _magisItem.value = null
                    loadPlugin(item.episodeId)
                }
            }
            PluginLiveRecovery.GIVE_UP -> {
                Log.w(PLAY, "plugin live: ${item.episodeId} didn't come back after ${LiveReopenPolicy.MAX_REOPENS} reopens → warning")
                _error.value = "Se cortó la señal de ${item.title} y no volvió. " +
                    "Puede ser un problema del canal: prueba de nuevo o mira otro."
            }
        }
        return decision
    }

    /** Every clock reading while a plugin's channel plays: replenishes its reopen budget once it really played. See [PluginLiveReopens.playing] and [liveIsPlaying]. */
    fun pluginLiveIsPlaying(positionMs: Long) = pluginLiveReopens.playing(positionMs)

    /**
     * [DituExoPlayer] gave up: it exhausted its re-prepares, or the error wasn't one of the kind
     * fixed that way. Before warning, a new URL is requested from the plugin --it carries
     * another `playback_token`-- and playback resumes at [positionMs].
     *
     * [code] is the `PlaybackException`'s `errorCode`.
     *
     * [wantedToPlay] carries over to the reload: if what failed was paused, the new player is too.
     */
    fun onDituExoError(code: Int, positionMs: Long, wantedToPlay: Boolean) {
        val name = androidx.media3.common.PlaybackException.getErrorCodeName(code)
        Log.w(PLAY, "Caracol: $name → no longer supported (the player is gone with the native module)")
        _error.value = "Este video ya no está disponible"
    }

    /**
     * A live stream dropped on ExoPlayer's side (segment/playlist 502 after the proxy's retries
     * ran out, or any other `PlaybackException`).
     *
     * Unlike [onMagisExoError], this does NOT set `_error` directly: a live channel almost always
     * recovers on its own (see [reopenLiveAfterCut]'s KDoc), so showing an error banner on the
     * first hiccup would alarm over something that resolves itself in 2-8s. [reopenLiveAfterCut]
     * is the one that decides, after [MAX_LIVE_REOPENS] attempts, whether the person needs to be
     * warned.
     */
    fun onLiveExoError(message: String) {
        Log.w(PLAY, "live (exo) error for ${zapping?.current?.code}: $message")
        LiveLog.e("player error: $message")
        if (message == com.arkiv.player.playback.LiveDecoderRescue.GAVE_UP) {
            // The software decoder failed too: a reopen only hits the same wall.
            reopenJob?.cancel()
            _error.value = com.arkiv.player.playback.LiveDecoderRescue.message(zapping?.current?.name ?: "este canal")
            return
        }
        reopenLiveAfterCut()
    }

    /**
     * The channel's video is beyond every decoder on this device, with no smaller variant
     * ([com.arkiv.player.playback.DecoderCapability], ERRORES-AML): reopening it only fails again, so
     * no reopen is scheduled and the person reads why. Zapping away clears it as any live error.
     */
    fun onLiveFormatUnsupported() {
        Log.w(PLAY, "live: ${zapping?.current?.code} uses a format beyond this device's decoders")
        reopenJob?.cancel()
        _error.value = com.arkiv.player.playback.DecoderCapability.UNSUPPORTED_MESSAGE
    }

    /**
     * The player couldn't handle this episode.
     *
     * This didn't used to exist: a playback failure never reached [_error] --the only thing the
     * screen paints-- so the movie simply didn't start and no message appeared. Measured on
     * 2026-08-10: `EncounteredError` in the log and `error=false` in the UI.
     *
     * Up until this branch's archive.org pruning there used to be a self-healing path here for the
     * 404 (the app cached the file's name and, if archive.org renamed it, refreshed the metadata
     * and re-located the chapter -- see `sanarRenombre` in the history). Deleted along with the
     * rest of that source: there's no more archive.org to ask anything.
     */
    fun onPlaybackFailed(episodeId: String) {
        // Everything written from here down describes a player hiccup, not a source that couldn't
        // be opened: if the video recovers, it stops being true. See [playbackHiccup].
        playbackHiccup = true
        _error.value = "No se pudo reproducir este capítulo"
    }

    /**
     * The video is genuinely playing: if what's on screen was a playback hiccup, it no longer
     * describes anything and goes away.
     *
     * Called by the screen's polling loop on every tick while the player is ready and playing, and
     * not by the listener's `onIsPlayingChanged`, on purpose: some hiccups recover without
     * `isPlaying` ever dropping, so hanging it off that transition left the banner up in exactly
     * the most common case. It's idempotent and returns through the `if` as soon as there's
     * nothing to clear, which is always except the instant right after a failure.
     *
     * RESOLUTION errors are left untouched: there, there's no video playing (or what's playing is
     * the old item, while the new one couldn't open), and the banner is the only signal of what
     * happened.
     */
    fun onPlaybackHealthy() {
        // Playing: the loaded chapter's attempt really started, so the history mark written on
        // opening stays even if the person leaves before the first save. Off this existing tick on
        // purpose: `PlayerContent` can't take another call site (see `OtherSourcesAction`).
        // Only when what's published is that chapter: right after a new load() the previous item
        // can still be playing for a moment.
        val onScreen = _magisItem.value?.episodeId ?: _playlist.value?.requested
        if (onScreen != null && onScreen == loadedEpisodeId) attempts.started(onScreen)
        if (!playbackHiccup) return
        playbackHiccup = false
        _error.value = null
    }

    /**
     * Plays a Magis item.
     *
     * The CDN requires `Content-Auth` and `Content-License`; the stream goes through the local
     * proxy, which can put them on the request to the origin -- libVLC, back when it played this,
     * could only send Referer and User-Agent. The stored [ref] is sent as-is to
     * `MagisResolve.resolveVod`; the app never interprets it.
     */
    private suspend fun loadMagis(episodeId: String) {
        // Adult content has NO library row -- that's the whole point, see [MagisEphemeral] -- so
        // its `ref` can't be read from there: it travels outside.
        val ephemeral = MagisEphemeral.take(episodeId)
        val ref = ephemeral?.ref ?: repo.magisRefForEpisode(episodeId)
        Log.w(PLAY, "loadMagis() episodeId=$episodeId ephemeral=${ephemeral != null} ref=${ref?.take(12)}…")
        if (ref.isNullOrBlank()) { _error.value = "No se encontró la fuente de Xuper"; return }

        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        // STARTUP STOPWATCH. Every phase is measured separately and a one-LINE summary is emitted
        // at the end: magis's bottleneck moved three times while this was being optimized (VLC →
        // probe+preheat → gateway), and each move cost a round of "play something and watch the
        // logs" because the timings had to be inferred from the gaps between loose lines.
        val t0 = System.currentTimeMillis()
        val resolved = withContext(Dispatchers.IO) { runCatching { source.resolve(ref) } }
        val msResolve = System.currentTimeMillis() - t0
        _resolving.value = false
        Log.w(PLAY, "loadMagis() portal resolve=${msResolve}ms")

        val play = resolved.getOrNull()
        if (play == null) {
            val failure = resolved.exceptionOrNull()
            Log.w(PLAY, "loadMagis() failed: ${failure?.message}")
            if (failure is GatewayBlockedException) {
                _blocked.value = failure.message
            } else {
                _error.value = "No se pudo resolver esta fuente de Xuper"
            }
            return
        }

        // The languages the portal declares are the ONLY thing that allows picking a subtitle by
        // language in magis: its embedded tracks arrive with no language in any field (measured on
        // device, `language=null` on `IMedia.Track` and a bare name "Track 1", while the audio ones
        // do carry spa/eng/jpn). They travel through [webExtras]; PlayerTracks cross-references them
        // with the source via SubtitleDecision.decide.
        Log.w(PLAY, "loadMagis() portal subtitles=${play.subtitles.size} langs=${play.subtitles.map { it.lang }}")

        withContext(Dispatchers.IO) { archiveCacheProxy.start() }
        // With no library row there's no header to read: the title comes from the pending item
        // itself, which is what the categories screen had in hand when it was tapped.
        val header = if (ephemeral != null) null else repo.headerInfo(episodeId)
        // `direct`: the proxy forwards every Range to the CDN with no caching. With caching (the
        // archive path) a ~1 GB download gets cut, the proxy deletes the file and starts over at 0
        // while the player keeps reading at the old offset → the TS arrives with gaps, the clock
        // jumps by minutes, and the video dies. With no cache there's nothing to truncate.
        val localUrl = archiveCacheProxy.proxyUrl(play.url, play.headers, direct = true)
        // THE DURATION IS NO LONGER PROBED BEFORE STARTING. The player reports it on its own once
        // it opens.
        //
        // This used to be the fallback for when magis was demuxed with the native `ts` demuxer,
        // which over HTTP couldn't deduce the duration and left the bar full and stuck at 00:00.
        // Once the demuxer switched to avformat (the player's own duration probe) that fallback
        // stopped being needed: measured on the Fire TV on 2026-08-13, the player reported
        // `dur=7010048ms` for a movie and `dur=3831168ms` for a series episode, both on the first
        // beat and both matching what the probe returned (7009961 and 3831000). The player's own
        // duration is preferred whenever it exists, so whatever came from here was discarded a
        // second later.
        //
        // And it wasn't free: for that same episode, fetching it cost 9.2s of spinner -- two round
        // trips to the CDN before opening the video, against an origin that takes 0.2s to 20s per
        // range -- for a number that would arrive on its own anyway. If the gateway sends it
        // (movies get it for free in the resolve) it's used; otherwise playback starts without it
        // and the player fills it in.
        if (play.durationMs > 0) {
            Log.w(PLAY, "loadMagis() gateway duration=${play.durationMs}ms")
        }
        // THE HOT STARTUP: the ONLY thing waited on before opening the video.
        //
        // Preheats at byte 0, which is where the player always opens now that magis stopped
        // opening by window: it resumes by seeking in time, not by opening the stream further
        // ahead. Without it, if the first read took a while libVLC would give up identifying the
        // stream and be left WITH NO TRACKS forever (black and silent, with the clock running).
        //
        // The TAIL still gets downloaded behind the scenes --for the player's EOF probes, which
        // want the end of the file as soon as it opens-- but it never blocks the startup anymore:
        // `waitForTail=false` unconditionally. See ArchiveCacheProxy.preWarm and
        // PrecalentadoNoBloqueaTest.
        val tWarmup = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                archiveCacheProxy.preWarm(
                    play.url, play.headers, fraction = 0f, waitForTail = false,
                    // The container decides whether the file's tail needs to be fetched: an mp4
                    // opens without reading the end and downloading it is pure waste against the
                    // CDN. If the gateway doesn't send it, the URL's extension says the same thing
                    // for magis anyway.
                    container = play.container.ifBlank {
                        com.arkiv.player.playback.VideoContainer.videoExtension(play.url).orEmpty()
                    },
                )
            }
        }
        val msWarmup = System.currentTimeMillis() - tWarmup
        // If the gateway sent the duration, it's used; if not, playback starts without it and the
        // player fills it in on opening. None of this requests a single extra byte.
        val duration = play.durationMs
        Log.w(PLAY, "loadMagis() hot startup=${msWarmup}ms → duration=${duration}ms")
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = header?.itemTitle ?: ephemeral?.titulo?.takeIf { it.isNotBlank() } ?: "Xuper",
            subtitle = header?.episodeLabel.orEmpty(),
            mediaUrl = localUrl,
            // NOT the url the receiver is given -- `castUrl` is the raw CDN, which answers 401
            // without `Content-Auth`/`Content-License`, and the Cast Default Media Receiver cannot
            // send custom headers. It is kept because it is the only place the TRUE container
            // survives: `MagisResolve` builds it as `_media.ts` or `_media.mp4` from the portal's
            // `videoFormat`, while the proxy url this plays from has no extension at all, so
            // guessing from it always answers mp4. PlayerScreen reads the container from here and
            // casts the proxy over the LAN instead (see `ArchiveCacheProxy.lanUrl`).
            //
            // An older comment here said casting could not work because "el proxy escucha en
            // 127.0.0.1". That was wrong and it cost a full misdiagnosis: `ArchiveCacheProxy.start`
            // opens `ServerSocket(0)` with no bind address, which listens on EVERY interface --
            // only the url string was loopback.
            castUrl = play.url,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.MAGIS,
            // Read from here by [shouldLogHistory] on every player tick. It's the second turn of
            // the key: the first is that this has no library row.
            adult = ephemeral?.adulto == true,
        )
        // This used to force SOFTWARE for magis's HEVC, assuming the hardware decoder was dropping
        // tracks (`pistas=v0/a0`). That diagnosis was wrong: what was dropping them was the
        // external subtitle (see PlayerScreen, where magis doesn't attach it). Without it, the same
        // title starts with `v2/a3` on both hardware and software. Left to open on hardware --
        // faster and not burning CPU--; if some title genuinely fails there, today there's no
        // "hardware with no picture → fall back to software" rescue for magis (unlike downloaded
        // files, see [DecoderWatchdog]).
        //
        // Subtitles travel through the SAME channel as web's: PlayerScreen decides what to do with
        // them. The portal delivers them alongside the stream, so there's no need to request them
        // separately from any subtitle catalog.
        _webExtras.value = WebExtras(
            episodeId,
            play.headers,
            play.subtitles.map { ResolvedSub(lang = it.lang, url = it.url) } + rememberedOnlineSubtitles(episodeId),
        )
        // Ephemeral content ALWAYS starts at zero, and not by oversight: no progress was saved, so
        // there's nowhere to resume from. It's the direct consequence of the rule -- what we
        // decided not to log can't be resumed -- and that's preferred over leaving a trace.
        val startPos = if (ephemeral != null) 0L else safeStartPosition(episodeId, SourceKind.MAGIS)
        // RESUME: the proxy is told WHERE the player is about to jump, so it prepares that zone
        // while the video opens. The player always opens at byte 0 and only then seeks to the
        // saved minute: measured on the Fire TV, 2.5 MB from the movie's start got downloaded and
        // then thrown away between the two, costing 3.4 s with the picture frozen at second 0. See
        // ArchiveCacheProxy.preWarmSeek.
        //
        // The duration comes from the SAVED progress and not from the gateway: here the gateway
        // usually sends 0 (the duration is computed by the player on opening, too late for this),
        // while whoever already watched part of the chapter has the duration recorded from that time.
        if (startPos > 0L) {
            val saved = runCatching { repo.getPlayback(episodeId) }.getOrNull()
            val savedDuration = saved?.durationMs ?: 0L
            if (savedDuration > 0L) {
                archiveCacheProxy.preWarmSeek(
                    play.url, play.headers, startPos.toFloat() / savedDuration,
                )
            }
        }
        // The hot startup is already in hand (requested above, in parallel with the probe): the
        // CDN wait happened BEFORE opening the video, where the user sees the usual spinner,
        // instead of turning into a failure with no way back.
        _magisItem.value = item.copy(startPositionMs = startPos)
        // SUMMARY, in one line and in the order it's paid. What's left for the first frame is
        // however long the player takes to open, measured separately: the sum of the two is what
        // the user sees as the spinner.
        Log.w(
            PLAY,
            "loadMagis() ⏱ TOTAL=${System.currentTimeMillis() - t0}ms " +
                "[resolve=${msResolve}ms | startup=${msWarmup}ms] startPos=$startPos",
        )
    }

    /**
     * A plugin title (`plugin:<id>:…`). The ref comes from the episode's `torrentData` like
     * Caracol's [via the plugin]; playback goes through the same slot as Magis ([_magisItem], played by
     * `StreamExoPlayer`), but the URL is played directly with the plugin's headers on the data
     * source — see `StreamExoPlayer.requestHeaders` for why not through `archiveCacheProxy`.
     *
     * A disabled, damaged or uninstalled plugin never reaches `resolve`: the person gets the
     * spec's message naming the plugin instead of "No hay ninguna fuente que sepa abrir esto".
     */
    /**
     * [resumeAt]/[startPaused]: only the network recovery's re-resolve ([onVodNetworkReResolve]),
     * which resumes where the player was, and paused if it was; null = the saved progress, playing.
     */
    private suspend fun loadPlugin(
        episodeId: String,
        resumeAt: Long? = null,
        startPaused: Boolean = false,
        onResolveFailed: (() -> Unit)? = null,
    ) {
        val pluginId = com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(episodeId)
        val access = pluginAccessFor(pluginId, plugins)
        val blocked = access.blockedMessage()
        if (blocked != null) {
            Log.w(PLAY, "loadPlugin() $episodeId blocked: $blocked")
            _error.value = blocked
            return
        }
        val name = access.name
        // Ready is the only access that gets past `blocked` above; its hosts are the approved ones.
        val ready = access as? com.arkiv.player.data.plugin.PluginAccess.Ready
        val approvedHosts = ready?.hosts ?: com.arkiv.player.data.plugin.EffectiveHosts(emptyList())
        // The registry decides this from the installed record (XuperPrivilege.grants), not from
        // `pluginId`, which is only the manifest id any repo could claim.
        val xuper = ready?.xuper == true
        // A live channel (apiVersion 2) has no library row: its ref comes from the card's handoff
        // ([PluginLive]), like Caracol's channels. A reload after a cut or an expired URL comes
        // back through here with the same id and finds it again (`take` doesn't clear it).
        // A channel whose stream is already known (an inline `stream`, a playlist entry) plays it
        // with no plugin call, on the first open and on every reopen after a cut alike.
        val live = com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(episodeId)
        val channel = if (live) com.arkiv.player.playback.PluginLive.take(episodeId) else null
        val plan = if (live) pluginLivePlay(channel) else null
        val ref = when (plan) {
            null -> repo.magisRefForEpisode(episodeId)
            is PluginLivePlay.Resolve -> plan.ref
            is PluginLivePlay.Direct -> "direct"
            PluginLivePlay.Missing -> null
        }
        Log.w(PLAY, "loadPlugin() episodeId=$episodeId plugin=$pluginId live=$live ref=${ref?.take(16)}…")
        if (ref.isNullOrBlank()) { _error.value = if (live) "No se encontró el canal de $name" else "No se encontró la fuente de $name"; return }

        // The network recovery keeps the player on screen as it is (extras included: they rebuild
        // it) until the new Stream is there.
        if (onResolveFailed == null) {
            _playlist.value = null
            _webExtras.value = null
        }
        _resolving.value = true
        val resolved = if (plan is PluginLivePlay.Direct) {
            Result.success(plan.playable)
        } else {
            // InteractivePluginCall: the person is here waiting, so a Stream URL on a host the plugin
            // never declared is asked about (the host-approval dialog) instead of refused outright.
            // Nothing on this path times that wait out: the plugin's own 20 s limit ended when its
            // `resolve` returned, and there is no limit here. Leaving the player clears this
            // ViewModel, which cancels this coroutine and takes the dialog down (see StreamHostApproval).
            withContext(Dispatchers.IO + com.arkiv.player.data.plugin.InteractivePluginCall) { runCatching { source.resolve(ref) } }
        }
        _resolving.value = false
        val play = resolved.getOrNull()
        if (play == null) {
            val failure = resolved.exceptionOrNull()
            Log.w(PLAY, "loadPlugin() failed: ${failure?.message}", failure)
            // The network recovery's re-resolve: the player on screen stays and tries in place.
            if (onResolveFailed != null) {
                onResolveFailed()
                return
            }
            // geo_blocked: the same dialog as a portal-side region block (spec §3.6).
            when (val outcome = PluginLoadFailure.from(failure, name, com.arkiv.player.data.plugin.PluginsPlace.of(isTv))) {
                is PluginLoadFailure.Blocked -> _blocked.value = outcome.message
                is PluginLoadFailure.SetupRequired -> _pluginSetup.value = PluginSetupPrompt(outcome.pluginId, outcome.message)
                is PluginLoadFailure.Generic -> _error.value = outcome.message
            }
            return
        }
        // The network recovery's re-resolve may bring back the same URL at the same spot: an equal
        // PlayerData would be dropped by StateFlow, so through null first, like [onMagisExoError]'s retry.
        if (onResolveFailed != null) _magisItem.value = null
        // Every freshly-resolved stream starts `retried = false`, even one this same retry branch
        // just brought back: the age gate in `shouldResolveAgain` is what stops a retry loop, not an
        // extra "only the very first stream of this title" restriction -- a long movie whose URL
        // keeps expiring gets a retry every time, not just once ever (spec §3.5).
        // The hosts the player's gate gets are read again NOW, after `resolve`: a host the person
        // approved in the middle of that call (reactive host approval) is where its stream just came
        // from, and `access` above was read before it existed. Only ever the same plugin's own
        // record, freshly read; if it stopped being Ready meanwhile, the earlier answer stands.
        val hostsReady = (pluginAccessFor(pluginId, plugins) as? com.arkiv.player.data.plugin.PluginAccess.Ready) ?: ready
        // A fresh Stream is a new playback attempt: each host the player then meets may be asked about once.
        playbackHostPrompts?.newAttempt()
        pluginExpiry = com.arkiv.player.data.plugin.PluginStreamExpiry(System.currentTimeMillis(), play.expiresInSeconds)
        val header = if (live) null else repo.headerInfo(episodeId)
        _webExtras.value = WebExtras(
            episodeId, play.headers,
            pluginSubtitles(play.subtitles) + (if (live) emptyList() else rememberedOnlineSubtitles(episodeId)),
            pluginAudioTracks(play.audioTracks),
            drm = pluginDrm(play), clearKey = pluginClearKey(play),
        )
        // A live stream has no "where you were": it starts at the player's default position (the
        // live edge), and with 0 `StreamExoPlayer` doesn't seek.
        val startPos = if (live) 0L else resumeAt ?: safeStartPosition(episodeId, SourceKind.PLUGIN)
        // The official Xuper plugin's VOD titles cast like the native Magis ones did: through
        // `ArchiveCacheProxy`, which puts the CDN headers on for the receiver (see
        // `castableStreamItem`). The phone itself doesn't play through it, so it's only started
        // here, before publishing, so the cast shape PlayerScreen builds from this item has a port.
        if (xuper && !live) withContext(Dispatchers.IO) { runCatching { archiveCacheProxy.start() } }
        // Any other plugin's title casts through `PluginCastProxy` (see `pluginCastModeFor`), which
        // also needs its port before the cast shape is built; a protected stream never casts.
        if (!xuper && play.drmLicenseUrl.isBlank() && play.drmClearKey.isEmpty()) {
            withContext(Dispatchers.IO) { runCatching { startPluginCastProxy() } }
        }
        // A plugin's hosts, as the gated clients enforce them. A live channel's stream may reach
        // any public host only when the installed record approved liveStreamHosts "any", decided
        // from the RESOLVED ref's kind (LIVE, of this plugin), never from the episode id; anything
        // else stays strict. A direct stream came from this plugin's own live listing, which was
        // already checked against those same live hosts.
        val streamHosts = when {
            hostsReady == null || pluginId == null -> approvedHosts
            plan is PluginLivePlay.Direct -> hostsReady.liveHosts
            else -> hostsReady.streamHostsFor(pluginId, ref)
        }
        // Only for the cast: a stream nothing describes gets its first bytes looked at, so the TV
        // buttons know what it is (see pluginCastModeFor). Never Xuper's (its own path), never DRM.
        // While casting it is the cast's own decision, taken the moment the item is published: read
        // first, as before. Otherwise after publishing (see below).
        val casting = castingNow()
        val probe = !xuper && play.drmLicenseUrl.isBlank() && play.drmClearKey.isEmpty() &&
            pluginStreamFormat(play.url, play.mime).first == PluginStreamFormat.UNKNOWN &&
            pluginCastProbe(live, casting)
        val probedMime = if (probe && casting) castProbe(play.url, play.headers, streamHosts) else ""
        val published = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = channel?.title ?: header?.itemTitle ?: name,
            subtitle = header?.episodeLabel.orEmpty(),
            mediaUrl = play.url,
            // Null for every plugin: what gets cast is built at cast time by `castableStreamItem`,
            // which only the official Xuper plugin's VOD titles pass; any other plugin has no cast.
            castUrl = null,
            artworkUrl = channel?.logo.orEmpty(),
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.PLUGIN,
            requestHeaders = play.headers,
            pluginHosts = streamHosts,
            pluginXuper = xuper,
            mime = play.mime,
            probedMime = probedMime,
            drm = play.drmLicenseUrl.isNotBlank() || play.drmClearKey.isNotEmpty(),
            startPositionMs = startPos,
            startPaused = startPaused && !live,
        )
        _magisItem.value = published
        Log.w(PLAY, "loadPlugin() published · mime=${play.mime.ifBlank { "sniff" }} subs=${play.subtitles.size} drm=${play.drmLicenseUrl.isNotBlank()} startPos=$startPos")
        // AFTER publishing, off the way of the phone's own start: it used to hold every open up to
        // 2 s more on a stream with no extension (review 2026-10-01). Only the cast reads it, and a
        // copy differing in it alone does not rebuild the phone's player (same url, headers, mime).
        if (probe && !casting) {
            viewModelScope.launch {
                val mime = castProbe(play.url, play.headers, streamHosts)
                if (mime.isNotEmpty() && _magisItem.value === published) _magisItem.value = published.copy(probedMime = mime)
            }
        }
    }

    /** [probePluginMime] for the cast, off the main thread: "" when it found nothing. */
    private suspend fun castProbe(
        url: String,
        headers: Map<String, String>,
        hosts: com.arkiv.player.data.plugin.EffectiveHosts,
    ): String =
        withContext(Dispatchers.IO) { runCatching { probePluginMime(url, headers, hosts) }.getOrNull() }
            .orEmpty().also { Log.i(PLAY, "loadPlugin() cast format probe → ${it.ifBlank { "nothing" }}") }

    /**
     * Validated start position (safe resume): applies the saved position only when resuming makes
     * sense -- more than 10s in, and not near the end. See
     * [com.arkiv.player.playback.ResumePolicy] for why nothing more is needed: torrent (a source
     * removed in this branch's pruning) also required that fraction of the file to already be
     * downloaded, but Magis and Ditu are pure streaming and don't have that problem.
     */
    private suspend fun safeStartPosition(episodeId: String, kind: SourceKind): Long {
        val saved = runCatching { repo.getPlayback(episodeId) }.getOrNull() ?: return 0L
        return com.arkiv.player.playback.ResumePolicy.startPosition(saved.positionMs, saved.durationMs)
            .also { Log.i(PLAY, "resume $episodeId ($kind): saved=${saved.positionMs}ms → starts at ${it}ms") }
    }

    /** The hand correction of the times, for the current chapter or the series. See its KDoc. */
    private val markerEditor by lazy {
        com.arkiv.player.data.markers.MarkerEditor(dao = repo.skipMarkerDao())
    }

    override fun onCleared() {
        preheatJob?.cancel()
        // The person left: an attempt that never played takes its early mark back. Not in
        // viewModelScope, which is cancelled right here.
        historyScope.launch { attempts.close() }
        super.onCleared()
    }

    /**
     * Marks the intro's end by hand at [ms].
     *
     * [episodeId] says WHAT it's set on: one chapter, or `""` = the whole series (what this path
     * always did before). Being able to mark ONE chapter is what makes the correction usable: with
     * automatic per-chapter markers, a manual series-wide one would override the correct automatic
     * one on every other chapter (see [MarkerEditor]).
     */
    fun setOpeningEnd(ms: Long, episodeId: String = "") = editMarker(episodeId) { itemId ->
        markerEditor.setOpeningEnd(itemId, episodeId, ms)
    }

    /** Marks the outro's start by hand at [ms]. See [setOpeningEnd] for [episodeId]. */
    fun setEndingStart(ms: Long, episodeId: String = "") = editMarker(episodeId) { itemId ->
        markerEditor.setEndingStart(itemId, episodeId, ms)
    }

    /** "This one has no intro or outro". See [setOpeningEnd] for [episodeId]. */
    fun clearMarkers(episodeId: String = "") = editMarker(episodeId) { itemId ->
        markerEditor.clear(itemId, episodeId)
    }

    private fun editMarker(episodeId: String, block: suspend (String) -> Unit) {
        val itemId = _playlist.value?.items?.firstOrNull()?.itemId ?: return
        viewModelScope.launch {
            block(itemId)
            // The copy baked into the playlist: the screen reads the markers from Room (that's why
            // they show up without reloading anything), but these fields still feed the editor
            // panel and what gets sent to the receiver, so they're kept in sync with what ended up
            // saved.
            val saved = repo.getSkipMarker(itemId, episodeId)
            val current = _playlist.value ?: return@launch
            _playlist.value = current.copy(
                items = current.items.map {
                    if (episodeId.isNotEmpty() && it.episodeId != episodeId) {
                        it
                    } else {
                        it.copy(
                            openingStartMs = saved?.openingStartMs,
                            openingEndMs = saved?.openingEndMs,
                            endingStartMs = saved?.endingStartMs,
                        )
                    }
                },
            )
        }
    }

    fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // A save with a duration is a player that really opened this chapter: its early mark stays.
        attempts.started(episodeId)
        // Adult content progress is NOT written. "Continue watching" comes straight out of
        // `playback`, and it's drawn on this device's home screen and in the library too -- a row
        // here doesn't stay hidden, even though (unlike until Task 5) it no longer travels through
        // cloud sync to any OTHER device. See [shouldLogHistory], where the decision and its edge
        // cases live.
        // Magis ExoPlayer: the item is in _magisItem, not in _playlist -- and so is a plugin's
        // title or live channel, which [savesProgress] refuses (no "where you were" to save).
        // Caracol falls into the branch below: `loadDitu` leaves `_playlist` at null, and with that
        // [shouldLogHistory] logs it. Except a live channel, which isn't logged (see its KDoc):
        // `repo.savePlayback` would write the row even with no episode in the library.
        val currentMagisItem = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (currentMagisItem != null) {
            if (!savesProgress(episodeId, currentMagisItem.adult)) return
        } else {
            if (!_playlist.value.shouldLogHistory(episodeId)) return
        }
        if (saveLog.shouldLog(episodeId, durationMs, System.currentTimeMillis())) {
            com.arkiv.player.ui.ProgressDiagnostics.saved(
                com.arkiv.player.ui.ProgressDiagnostics.episodeKind(episodeId),
                positionMs,
                durationMs,
                com.arkiv.player.data.WatchedThreshold.isWatched(positionMs, durationMs),
            )
        }
        viewModelScope.launch { repo.savePlayback(episodeId, positionMs, durationMs) }
    }

    /** Throttles the progress-save diagnostic (first save, a changed duration, then once a minute). */
    private val saveLog = com.arkiv.player.ui.ProgressSaveLog()

    /**
     * Captures the frame currently being watched. Best-effort and off the critical path: if there's
     * no TextureView or the frame doesn't pass the guards, nothing happens.
     *
     * The TextureView comes as a parameter because the views live in `PlayerScreen` (the local
     * one and the in-screen players'); here only `viewModelScope` is needed so the capture doesn't
     * block the composition thread.
     */
    fun captureFrame(episodeId: String, positionMs: Long, textureView: android.view.TextureView?) {
        // Same guard as progress, and it matters more here: a frame isn't a number, it's an image
        // of what was being watched -- `FrameCapturer.publicar` writes the JPEG straight to local
        // storage. It's the 2026-08-14 leak again, but with a photo. (`episode_frame` used to sync
        // to other devices through PocketBase; that's gone with the rest of cloud sync, but a
        // locally-saved frame of adult content is still exactly the leak this guard exists to stop.)
        val currentMagisItem = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (currentMagisItem != null) {
            if (!savesProgress(episodeId, currentMagisItem.adult)) return
        } else {
            if (!_playlist.value.shouldLogHistory(episodeId)) return
        }
        viewModelScope.launch { frameCapturer.capture(episodeId, positionMs, textureView) }
    }

    private companion object {
        /** How long the zapping must be quiet before a plugin channel is opened; see [zapSettle]. */
        const val ZAP_SETTLE_MS = 350L

        /** How many times a cut live stream reopens before warning. See [reopenLiveAfterCut]; one policy with a plugin's channel ([LiveReopenPolicy]). */
        const val MAX_LIVE_REOPENS = LiveReopenPolicy.MAX_REOPENS

        /**
         * How long a reopened channel has to play to be considered recovered and get its whole
         * reopen budget back. See [liveIsPlaying].
         */
        const val MIN_HEALTHY_LIVE_MS = LiveReopenPolicy.MIN_HEALTHY_MS

        /** Tag for the player's load/replay flow (filter with `adb logcat -s ArkivPlay`). */
        const val PLAY = "ArkivPlay"
    }
}

/** See [PlayerViewModel.pluginSetup]. */
data class PluginSetupPrompt(val pluginId: String, val message: String)
