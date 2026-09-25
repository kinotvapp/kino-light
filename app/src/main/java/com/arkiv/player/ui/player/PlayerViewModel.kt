package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.ditu.CaracolFailure
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.plugin.blockedMessage
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.AdultContent
import com.arkiv.player.playback.DituLive
import com.arkiv.player.playback.MagisEphemeral
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.live.LiveController
import com.arkiv.player.ui.live.LiveZapping
import com.arkiv.player.ui.live.LiveZappingSource
import kotlinx.coroutines.Dispatchers
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
    val kind: SourceKind,       // source (MAGIS/DITU/LOCAL/LIVE/UNKNOWN/PLUGIN) -- PlayerScreen reads it for live detection, the cast LAN URL and the cast-transcode origin
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
    /** Container MIME the source declared ("" = let ExoPlayer sniff). */
    val mime: String = "",
    /**
     * Start position to resume (ExoPlayer, e.g. magisItem). The local player uses
     * PlaylistData.startPositionMs instead.
     */
    val startPositionMs: Long = 0L,
)

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
 * A Caracol live channel ([DituLive]) is never logged: it has no library row and nothing to
 * resume. `PlayerScreen` no longer saves its position (its `enVivo` comes from
 * `PlayerSource.isLiveChannel`, which includes it), but its capture-on-pause doesn't check
 * `enVivo`, and `saveProgress`/`captureFrame` don't go through the `_magisItem` branch for it:
 * this remains the guard that stops it in both.
 */
internal fun PlaylistData?.shouldLogHistory(episodeId: String): Boolean =
    !DituLive.isLive(episodeId) &&
        AdultContent.shouldLog(this?.items?.firstOrNull { it.episodeId == episodeId }?.adult)

/**
 * Should [episodeId] be marked "in progress" on opening (`ArkivRepository.markInProgress`)?
 *
 * `PlayerViewModel.load`'s decision, out here so it can be pinned down with tests. [adult] is the
 * ephemeral pending item's ([MagisEphemeral]), the only thing known before the source resolves,
 * and what isn't known gets logged, same as in [AdultContent.shouldLog]. A Caracol live channel is
 * never marked: `markInProgress` would write a `playback` row with its id even though there's no
 * episode.
 */
internal fun shouldMarkInProgress(episodeId: String, adult: Boolean?): Boolean =
    !DituLive.isLive(episodeId) && AdultContent.shouldLog(adult)

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
)

/** A plugin's subtitles, keeping the `format` it declared (its URLs rarely end in `.srt`). */
internal fun pluginSubtitles(subs: List<GatewaySubtitle>): List<ResolvedSub> =
    subs.map { ResolvedSub(lang = it.lang, url = it.url, format = it.format) }

/** Extras of a resolved source (subtitles + sniffed headers) to attach in the UI. Despite the
 *  "web" name, [PlayerViewModel.loadMagis] also uses it for the subtitles the portal brings. */
data class WebExtras(
    val episodeId: String,
    val headers: Map<String, String>,
    val subtitles: List<ResolvedSub>,
)

/**
 * What's playing from Caracol: which episode it is, where to start from, and what the source
 * resolved (the manifest URL and the Widevine license). Goes in a single value so the screen never
 * sees one episode's URL with another's position.
 */
data class DituReproducible(
    val episodeId: String,
    val playable: com.arkiv.player.data.gateway.GatewayPlayable,
    val startPositionMs: Long = 0L,
    /**
     * Publication number; set by [DituState.publish]. Exists so two publications are never equal:
     * a reload can bring the same URL and the same token, and the screen still has to rebuild the
     * player (it composes it inside a `key` with this integer value).
     */
    val generation: Int = 0,
    /**
     * Whether the player starts on its own with the first frame. `false` is a reload of something
     * that was paused: for example, a video that paused when the app went to the background and
     * failed there. `PlayerScreen` reads this with `collectAsStateWithLifecycle`, so that new
     * player is only built on return, and it can't start playing on its own. See
     * [StartOnFirstFrame.wantedToPlay].
     */
    val autoStart: Boolean = true,
    /**
     * The chapter is already downloaded to the device: where to read it from.
     *
     * `null` = play by streaming, as usual. When present, the segments come from the cache instead
     * of the CDN -- but the LICENSE is still requested over the network, because Caracol doesn't
     * grant persistent licenses (see [com.arkiv.player.data.caracol.CaracolDownload]). That's why
     * this coexists with [playable] instead of replacing it: that's where the fresh
     * `playback_token` comes from.
     */
    val localDownload: com.arkiv.player.data.caracol.CaracolDownload? = null,
)

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
        /** [pluginDisplayName] is only used for the two messages that don't already name the plugin. */
        fun from(failure: Throwable?, pluginDisplayName: String): PluginLoadFailure = when (failure) {
            is GatewayBlockedException -> Blocked(failure.message.orEmpty())
            is com.arkiv.player.data.plugin.PluginSetupRequiredException ->
                SetupRequired(failure.pluginId, failure.message ?: "Configura $pluginDisplayName en Ajustes ▸ Plugins")
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
    // Caracol apart from [source]: its live channels aren't part of the common contract (see
    // `AppGraph.dituSource`). [loadDitu] resolves them with `DituSource.resolveChannel`.
    private val dituSource: com.arkiv.player.data.ditu.DituSource,
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
) : ViewModel() {

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

    /**
     * Current Caracol episode, or `null` if what's playing is from another source. When not null,
     * `PlayerScreen` plays it with [DituExoPlayer] instead of VLC or Magis's player. Published by
     * [DituState], which discards late arrivals and tracks the re-prepare and reload caps.
     */
    private val ditu = DituState()
    val dituPlayable: StateFlow<DituReproducible?> = ditu.current

    /** Resolution error (no source found on Magis/Caracol, a legacy id from a removed source, etc.) for the screen to show. */
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

    /** Loads the episode as a playlist, branching by source (Magis vs Ditu vs unknown/legacy id). */
    fun load(episodeId: String) {
        // Before everything else, live included: a Caracol resolution still in flight has to know
        // it's no longer the active one. See [DituState].
        ditu.newRequest(episodeId)
        clearTrivia()
        // Live mode (Task 14): CUTS OFF HERE, before touching anything on the VOD path below --
        // neither markInProgress nor localLibrary. It's the flag that isolates ALL of the different
        // behavior: a live channel has no duration to poll (see LiveZapping/LiveController's KDoc
        // -- polling it is what broke Magis VOD), no progress to save, and no "next chapter" for
        // series -- the only "next" that exists in live is zapping.
        if (PlayerSource.kindFor(episodeId) == SourceKind.LIVE) {
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
            if (shouldMarkInProgress(episodeId, MagisEphemeral.take(episodeId)?.adulto)) {
                runCatching { repo.markInProgress(episodeId) }
            }
            _error.value = null
            _needsMagisAccount.value = false
            _magisItem.value = null
            // Navigating from a live channel to a VOD episode without going through another screen
            // (the same ViewModel survives, see the guard above): without this reset, `liveItem`
            // kept publishing the last channel and PlayerScreen (isLive/isLiveExo) still believed it.
            _liveItem.value = null
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
                SourceKind.DITU -> loadDitu(episodeId)
                SourceKind.PLUGIN -> loadPlugin(episodeId)
                // kindFor() never returns LOCAL: a downloaded file is detected above by
                // localLibrary.fileFor(). The branch exists because the `when` is exhaustive.
                SourceKind.LOCAL -> loadUnknownSource(episodeId)
                // Unreachable: live channels return before this launch (see the guard above).
                SourceKind.LIVE -> Unit
            }
        }
    }

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
     * Starts zapping over the list the user ENTERED with (see [LiveZappingSource]), not the whole
     * catalog -- it's the one they have in mind. With nothing set there (process recreated
     * mid-live-player, or a caller that didn't go through the grid) it falls back to a single-
     * channel list: zapping is lost, but the chosen channel still plays.
     */
    private fun loadLive(code: String) {
        val entryList = LiveZappingSource.list.ifEmpty { listOf(LiveChannel(code, code, 0, null)) }
        val index = entryList.indexOfFirst { it.code == code }.coerceAtLeast(0)
        zapping = LiveZapping(entryList, index)
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
        val channel = zapping?.current ?: return
        _liveChannel.value = channel
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
            ditu.clear()
            val url = runCatching { liveController.open(channel.code) }.getOrElse {
                Log.w(PLAY, "openCurrentChannel() failed for ${channel.code}: ${it.message}")
                if (zapping?.current?.code == channel.code) {
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
            if (zapping?.current?.code != channel.code) return@launch
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
            if (AdultContent.shouldLog(channel.adult)) {
                runCatching {
                    liveRecentDao.record(LiveRecentEntity(channel.code, channel.name, System.currentTimeMillis()))
                }
            }
            preheatNeighbors()
        }
    }

    /** Zapping: next/previous in the list entered with. No effect outside live mode. */
    fun zapNext() { zapping?.next() ?: return; openCurrentChannel() }
    fun zapPrevious() { zapping?.previous() ?: return; openCurrentChannel() }

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
        if (channel.code != counterChannel) {
            counterChannel = channel.code
            liveReopens = 0
        }
        if (liveReopens >= MAX_LIVE_REOPENS) {
            Log.w(PLAY, "live: ${channel.code} didn't come back after $MAX_LIVE_REOPENS reopens → warning")
            _error.value = "Se cortó la señal de ${channel.name} y no volvió. " +
                "Puede ser un problema del canal: prueba de nuevo o mira otro."
            return
        }
        if (cutSince == 0L) cutSince = System.currentTimeMillis()
        liveReopens++
        val wait = REOPEN_WAIT_MS shl (liveReopens - 1)
        Log.w(
            PLAY,
            "live: ${channel.code} cut out → reopening in ${wait}ms " +
                "(attempt $liveReopens/$MAX_LIVE_REOPENS)",
        )
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            delay(wait)
            // Zapping during the wait wins: reopening the old channel here would override the one
            // the person just chose.
            if (zapping?.current?.code == channel.code) openCurrentChannel()
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
        val entryList = list.ifEmpty { listOf(channel) }
        LiveZappingSource.list = entryList
        zapping = LiveZapping(entryList, entryList.indexOfFirst { it.code == channel.code }.coerceAtLeast(0))
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
        val neighbors = zapping?.neighbors() ?: return
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
            plugins?.nameOf(com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(item.episodeId)) ?: "Plugin"
        } else {
            "Xuper"
        }
        _error.value = "$label: $message"
    }

    /**
     * [DituExoPlayer] gave up: it exhausted its re-prepares, or the error wasn't one of the kind
     * fixed that way. Before warning, a new URL is requested from Caracol --it carries another
     * `playback_token`-- and playback resumes at [positionMs]. Capped: see
     * [DituState.requestReload].
     *
     * [code] is the `PlaybackException`'s `errorCode`: [CaracolFailure.onPlayback] uses it to tell
     * the person what happened. Its technical name goes to the log.
     *
     * [wantedToPlay] carries over to the reload: if what failed was paused, the new player is too
     * (see [DituReproducible.autoStart]).
     */
    fun onDituExoError(code: Int, positionMs: Long, wantedToPlay: Boolean) {
        val name = androidx.media3.common.PlaybackException.getErrorCodeName(code)
        val episode = ditu.requestReload()
        if (episode == null) {
            Log.w(PLAY, "Caracol: $name and no reloads left → notifying the person")
            _error.value = CaracolFailure.onPlayback(code, isTv)
            return
        }
        Log.w(
            PLAY,
            "Caracol: $name → requesting a new URL for $episode from ${positionMs}ms " +
                "(wantedToPlay=$wantedToPlay)",
        )
        viewModelScope.launch { loadDitu(episode, resumeAtMs = positionMs, autoStart = wantedToPlay) }
    }

    /** [DituExoPlayer] had an error fixed by re-preparing: any left? See
     *  [DituState.requestReprepare]. */
    fun dituCanReprepare(): Boolean = ditu.requestReprepare()

    /** Every clock reading from [DituExoPlayer]. See [DituState.advanced]. */
    fun dituAdvanced(positionMs: Long, playing: Boolean) = ditu.advanced(positionMs, playing)

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
        reopenLiveAfterCut()
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
            play.subtitles.map { ResolvedSub(lang = it.lang, url = it.url) },
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
     * Plays a Caracol episode, or one of its live channels.
     *
     * Unlike [loadMagis], it doesn't go through [archiveCacheProxy]: the headers Caracol requires
     * are set by [DituExoPlayer] itself. Here it's only resolved and published to [dituPlayable],
     * along with the position to resume from.
     *
     * `ref` comes from [ArkivRepository.magisRefForEpisode], which despite the name reads the ref
     * saved on the episode's row (or, if it doesn't have one, on its item's) without checking which
     * source it's from.
     *
     * A live channel ([DituLive.isLive]) has no library row or `ref`: the channel left by Caracol's
     * section is resolved with `DituSource.resolveChannel`, and goes through the same [DituState]
     * guards as VOD. A reload ([onDituExoError]) comes back in through here with the same
     * `episodeId` and resolves the channel again: that's why [DituLive.take] doesn't empty it.
     *
     * [resumeAtMs] is for reloads: playback resumes where it was, not from the saved position.
     * Without it, the same resume as Magis. A live stream always starts at 0, and with 0
     * [DituExoPlayer] doesn't `seekTo`: it stays at the live stream's default position.
     *
     * [autoStart] is also for reloads: see [DituReproducible.autoStart].
     */
    private suspend fun loadDitu(episodeId: String, resumeAtMs: Long? = null, autoStart: Boolean = true) {
        val live = DituLive.isLive(episodeId)
        val channel = if (live) DituLive.take(episodeId) else null
        val ref = if (live) null else repo.magisRefForEpisode(episodeId)
        Log.w(
            PLAY,
            "loadDitu() episodeId=$episodeId ref=${ref?.take(16)}… channel=${channel?.channelId} " +
                "reload=${resumeAtMs != null}",
        )
        // What follows touches state shared by every source: if another episode was already
        // requested while the ref was being read, this belongs to nobody. See [DituState].
        if (!ditu.isActive(episodeId)) return
        val resolver: suspend () -> com.arkiv.player.data.gateway.GatewayPlayable = when {
            channel != null -> suspend { dituSource.resolveChannel(channel) }
            !ref.isNullOrBlank() -> suspend { source.resolve(ref) }
            else -> {
                _error.value = if (live) "No se encontró el canal de Caracol" else "No se encontró la fuente de Caracol"
                return
            }
        }

        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        val resolved = withContext(Dispatchers.IO) { runCatching { resolver() } }
        // Turned off even if it's no longer the active one: if what got requested next is a live
        // channel, that path doesn't touch this flag and it would stay on.
        _resolving.value = false
        if (!ditu.isActive(episodeId)) {
            Log.w(PLAY, "loadDitu() discarded: $episodeId is no longer the current request")
            return
        }
        val play = resolved.getOrNull()
        if (play == null) {
            val failure = resolved.exceptionOrNull()
            // The detail goes to the log; the person gets what `CaracolFailure` makes of it.
            Log.w(PLAY, "loadDitu() failed: ${failure?.message}", failure)
            _error.value = CaracolFailure.onOpen(failure)
            return
        }
        // The same resume as Magis: [safeStartPosition] over the saved progress. A live stream has
        // no "where you were", not even on a reload.
        val startPos = if (live) 0L else resumeAtMs ?: safeStartPosition(episodeId, SourceKind.DITU)
        Log.w(PLAY, "loadDitu() drm=${play.drmLicenseUrl.isNotBlank()} startPos=$startPos")
        // `publish` checks again whether it's still the active one: `safeStartPosition` also
        // suspends. If it's downloaded, this says where to read from. Looked up AFTER resolving and
        // not before because resolving is needed anyway: it's the only thing that brings the token
        // the license is requested with.
        val download = if (live) null else runCatching { localLibrary.caracolDownload(episodeId) }.getOrNull()
        if (download != null) {
            Log.w(PLAY, "loadDitu() $episodeId is on the device (${download.height}p); media comes off the disk")
        }
        if (!ditu.publish(
                DituReproducible(episodeId, play, startPos, autoStart = autoStart, localDownload = download),
            )
        ) {
            Log.w(PLAY, "loadDitu() discarded on publish: $episodeId is no longer the current request")
        }
    }

    /**
     * A plugin title (`plugin:<id>:…`). The ref comes from the episode's `torrentData` like
     * Caracol's ([loadDitu]); playback goes through the same slot as Magis ([_magisItem], played by
     * `StreamExoPlayer`), but the URL is played directly with the plugin's headers on the data
     * source — see `StreamExoPlayer.requestHeaders` for why not through `archiveCacheProxy`.
     *
     * A disabled, damaged or uninstalled plugin never reaches `resolve`: the person gets the
     * spec's message naming the plugin instead of "No hay ninguna fuente que sepa abrir esto".
     */
    private suspend fun loadPlugin(episodeId: String) {
        val pluginId = com.arkiv.player.data.plugin.PluginIds.pluginIdOfEpisode(episodeId)
        val access = plugins?.accessFor(pluginId)
            ?: com.arkiv.player.data.plugin.PluginAccess.Uninstalled(pluginId ?: "desconocido")
        val blocked = access.blockedMessage()
        if (blocked != null) {
            Log.w(PLAY, "loadPlugin() $episodeId blocked: $blocked")
            _error.value = blocked
            return
        }
        val name = access.name
        // Ready is the only access that gets past `blocked` above; its hosts are the approved ones.
        val approvedHosts = (access as? com.arkiv.player.data.plugin.PluginAccess.Ready)?.hosts
            ?: com.arkiv.player.data.plugin.EffectiveHosts(emptyList())
        val ref = repo.magisRefForEpisode(episodeId)
        Log.w(PLAY, "loadPlugin() episodeId=$episodeId plugin=$pluginId ref=${ref?.take(16)}…")
        if (ref.isNullOrBlank()) { _error.value = "No se encontró la fuente de $name"; return }

        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        val resolved = withContext(Dispatchers.IO) { runCatching { source.resolve(ref) } }
        _resolving.value = false
        val play = resolved.getOrNull()
        if (play == null) {
            val failure = resolved.exceptionOrNull()
            Log.w(PLAY, "loadPlugin() failed: ${failure?.message}", failure)
            // geo_blocked: the same dialog as a portal-side region block (spec §3.6).
            when (val outcome = PluginLoadFailure.from(failure, name)) {
                is PluginLoadFailure.Blocked -> _blocked.value = outcome.message
                is PluginLoadFailure.SetupRequired -> _pluginSetup.value = PluginSetupPrompt(outcome.pluginId, outcome.message)
                is PluginLoadFailure.Generic -> _error.value = outcome.message
            }
            return
        }
        // Every freshly-resolved stream starts `retried = false`, even one this same retry branch
        // just brought back: the age gate in `shouldResolveAgain` is what stops a retry loop, not an
        // extra "only the very first stream of this title" restriction -- a long movie whose URL
        // keeps expiring gets a retry every time, not just once ever (spec §3.5).
        pluginExpiry = com.arkiv.player.data.plugin.PluginStreamExpiry(System.currentTimeMillis(), play.expiresInSeconds)
        val header = repo.headerInfo(episodeId)
        _webExtras.value = WebExtras(episodeId, play.headers, pluginSubtitles(play.subtitles))
        val startPos = safeStartPosition(episodeId, SourceKind.PLUGIN)
        _magisItem.value = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = header?.itemTitle ?: name,
            subtitle = header?.episodeLabel.orEmpty(),
            mediaUrl = play.url,
            // No cast for plugin titles in v1: without a cast URL no cast path has anything to send.
            castUrl = null,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.PLUGIN,
            requestHeaders = play.headers,
            pluginHosts = approvedHosts,
            mime = play.mime,
            startPositionMs = startPos,
        )
        Log.w(PLAY, "loadPlugin() published · mime=${play.mime.ifBlank { "sniff" }} subs=${play.subtitles.size} startPos=$startPos")
    }

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
        // Adult content progress is NOT written. "Continue watching" comes straight out of
        // `playback`, and it's drawn on this device's home screen and in the library too -- a row
        // here doesn't stay hidden, even though (unlike until Task 5) it no longer travels through
        // cloud sync to any OTHER device. See [shouldLogHistory], where the decision and its edge
        // cases live.
        // Magis ExoPlayer: the item is in _magisItem, not in _playlist.
        // Caracol falls into the branch below: `loadDitu` leaves `_playlist` at null, and with that
        // [shouldLogHistory] logs it. Except a live channel, which isn't logged (see its KDoc):
        // `repo.savePlayback` would write the row even with no episode in the library.
        val currentMagisItem = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (currentMagisItem != null) {
            if (!AdultContent.shouldLog(currentMagisItem.adult)) return
        } else {
            if (!_playlist.value.shouldLogHistory(episodeId)) return
        }
        viewModelScope.launch { repo.savePlayback(episodeId, positionMs, durationMs) }
    }

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
            if (!AdultContent.shouldLog(currentMagisItem.adult)) return
        } else {
            if (!_playlist.value.shouldLogHistory(episodeId)) return
        }
        viewModelScope.launch { frameCapturer.capture(episodeId, positionMs, textureView) }
    }

    private companion object {
        /** How many times a cut live stream reopens before warning. See [reopenLiveAfterCut]. */
        const val MAX_LIVE_REOPENS = 3

        /** Wait for the FIRST reopen; each next one doubles it (2 s → 4 s → 8 s). */
        const val REOPEN_WAIT_MS = 2_000L

        /**
         * How long a reopened channel has to play to be considered recovered and get its whole
         * reopen budget back. See [liveIsPlaying].
         */
        const val MIN_HEALTHY_LIVE_MS = 5_000L

        /** Tag for the player's load/replay flow (filter with `adb logcat -s ArkivPlay`). */
        const val PLAY = "ArkivPlay"
    }
}

/** See [PlayerViewModel.pluginSetup]. */
data class PluginSetupPrompt(val pluginId: String, val message: String)
