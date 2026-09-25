package com.arkiv.player.ui.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.arkiv.player.ui.live.DrawerAction
import com.arkiv.player.ui.live.DrawerDpad
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arkiv.player.cast.CastProgress
import com.arkiv.player.cast.localAudioFormat
import com.arkiv.player.cast.localVideoFormat
import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.ui.settings.MagisLinkOffer
import com.arkiv.player.ui.tv.TvMagisLinkOffer
import com.arkiv.player.ui.tv.library.SAFE_H
import com.arkiv.player.ui.tv.library.SAFE_V
import com.arkiv.player.playback.AutoAdvance
import com.arkiv.player.playback.DecoderWatchdog
import com.arkiv.player.playback.FirstFrameWait
import com.arkiv.player.playback.LoadedMedia
import com.arkiv.player.playback.MediaReusePolicy
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.playback.PlaybackEngine
import com.arkiv.player.playback.PlaybackService
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.PlayerSourceTag
import com.arkiv.player.playback.ReloadPositionPolicy
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.VideoAttachPolicy
import com.arkiv.player.playback.setPlayerSourceTag
import com.arkiv.player.playback.toIpcBundle
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Playback speed steps (ported from TorrentPlayerScreen). */

/** Zoom steps: 0 = fit the screen; >0 = crop that trims the black bars (see PlayerGestures). */

/** Minimum vertical swipe (px) for live mode (Task 14) to take it as zapping on the phone. */
private const val ZAP_THRESHOLD_PX = 80f

// Controls hidden in the PHONE's top bar: it got up to 8 elements and they looked crammed. The
// code is kept -- not deleted -- so they can be turned back on with a single change here. On TV
// none of the three existed. Subtitles aren't hidden: they moved down to the right.
private const val SHOW_MARKERS_ON_PHONE = false
private const val SHOW_SPEED_AND_ZOOM_ON_PHONE = true

// Night mode: the black veil sits ON TOP of the video, with opacity level/DIM_MAX_LEVEL -- 0 =
// normal brightness (no veil), DIM_MAX_LEVEL = fully black. The screen's real brightness isn't
// used because on the Fire TV Stick it's a no-op (the TV controls brightness, not Android), and
// libVLC 3.x didn't expose the `adjust` filter. Changing the step's granularity = changing only
// this line.

/**
 * How long to wait, untouched, before confirming a burst of incremental jumps (see `seekBy`).
 * Short on purpose: a lone press still feels immediate and only bursts get merged, which is where
 * the cost was -- a Range request and its rebuffer for every press.
 */
private const val SEEK_INCREMENTAL_DEBOUNCE_MS = 350L


/** Builds the local MediaItems for the controller, propagating the source/marker tag. */
private fun localMediaItems(items: List<PlayerData>): List<MediaItem> = items.map { d ->
    val tag = PlayerSourceTag(
        kind = d.kind,
        openingStartMs = d.openingStartMs,
        openingEndMs = d.openingEndMs,
        endingStartMs = d.endingStartMs,
        castUrl = d.castUrl,
        referer = d.referer,
        userAgent = d.userAgent,
        proxyUrl = d.proxyUrl,
        preferSoftware = d.prefersSoftware,
    )
    MediaItem.Builder()
        .setUri(d.mediaUrl)
        .setMediaId(d.episodeId)
        // The URI in localConfiguration is LOST crossing MediaController→MediaSession, so we keep
        // it in requestMetadata too (which does survive the IPC) for
        // PlaybackService.MediaItemResolverCallback.onAddMediaItems to rebuild on the session.
        // The TAG (kind/referer/etc.) is LOST crossing controller→session just like the URI, so we
        // keep it in extras (which DO survive the IPC, see PlayerSourceTagIpc) to rebuild it in
        // PlaybackService.
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(d.mediaUrl))
                .setExtras(tag.toIpcBundle())
                .build(),
        )
        .setPlayerSourceTag(tag)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(d.title).setArtist(d.subtitle)
                .apply { if (d.artworkUrl.isNotEmpty()) setArtworkUri(Uri.parse(d.artworkUrl)) }
                .build(),
        )
        .build()
}

/**
 * The exact player operations the decoder watchdog's software reload performs (see
 * [reloadInSoftware]), narrowed from `Player` so a test can pin the call ORDER with a small
 * recording fake instead of implementing all of `Player`'s members. [MediaController] satisfies it
 * through [asSoftwareReloadPlayer].
 */
internal interface SoftwareReloadPlayer {
    fun stop()
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun prepare()
    var playWhenReady: Boolean
}

private fun MediaController.asSoftwareReloadPlayer(): SoftwareReloadPlayer =
    object : SoftwareReloadPlayer {
        override fun stop() = this@asSoftwareReloadPlayer.stop()
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
            this@asSoftwareReloadPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        override fun prepare() = this@asSoftwareReloadPlayer.prepare()
        override var playWhenReady: Boolean
            get() = this@asSoftwareReloadPlayer.playWhenReady
            set(value) { this@asSoftwareReloadPlayer.playWhenReady = value }
    }

/**
 * Performs the decoder watchdog's software reload on [player]: `stop()` BEFORE `setMediaItems(…)`,
 * then `prepare()` at the preserved position.
 *
 * The order matters: on a media-item change media3 1.5.1 KEEPS the video codec (it flushes and
 * re-uses it -- `releaseCodec()` only runs from the renderer's reset) and `prepare()` returns
 * immediately outside `STATE_IDLE` -- and this failure sits in `BUFFERING` -- so the software-first
 * selector would never be consulted without `stop()` first. `stop()` resets the renderers, which
 * releases the codec; the reload then starts from `IDLE` and the selector runs again. The position
 * is not lost: it travels to `setMediaItems`. This was a Critical review finding: without the
 * ordering, the rescue is a silent no-op. Split out from `watchLocalDecoder` so a recording fake
 * [SoftwareReloadPlayer] can pin it without needing the whole composition.
 */
/** The Format of the currently-SELECTED video track, for telemetry. `Player.getVideoFormat()` only
 *  exists on ExoPlayer, not on the base Player/MediaController, so it's read from the tracks. */
internal fun selectedVideoFormat(tracks: Tracks): androidx.media3.common.Format? =
    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }

internal fun reloadInSoftware(
    player: SoftwareReloadPlayer,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
) {
    player.stop()
    player.setMediaItems(mediaItems, startIndex, startPositionMs)
    player.prepare()
    player.playWhenReady = true
}

@Composable
private fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/** Numbers the player's compositions. See the DisposableEffect for `screenId`. */
private val SCREEN_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    episodeId: String,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onNextEpisode: (String) -> Unit = {},
    isTv: Boolean = false,
) {
    val controller = rememberMediaController()
    // The service's ExoPlayer, used only to bind the local video surface (see PlaybackEngine).
    // By the time the controller connects, the service exists.
    val serviceExo = PlaybackEngine.player
    if (controller == null || serviceExo == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    PlayerContent(episodeId, onBack, onOpenEpisodes, onNextEpisode, controller, serviceExo, isTv)
}

@OptIn(UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    episodeId: String,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onNextEpisode: (String) -> Unit,
    controller: MediaController,
    serviceExo: ExoPlayer,
    isTv: Boolean,
) {
    val graph = rememberGraph()
    val subtitleStyle by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val castContext = remember { graph.castContext }
    val dlna = remember { graph.dlna }

    // Keep the screen on while playing (on TV, the app's root handles it).
    val view = LocalView.current
    if (!isTv) {
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    val vm: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    graph.repository, graph.archiveCacheProxy,
                    graph.localLibrary, graph.localFileServer, graph.frameCapturer,
                    graph.liveController, graph.database.liveRecentDao(),
                    isTv = isTv,
                    source = graph.contentSource,
                    dituSource = graph.dituSource,
                    hasMagisAccount = { graph.magisSession.hasAccountLinked },
                    triviaFacts = graph.triviaFacts,
                    funFactsEnabled = { graph.settings.funFactsEnabled.value },
                )
            }
        },
    )
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val magisItem by vm.magisItem.collectAsStateWithLifecycle()
    var magisPlayer by remember { mutableStateOf<Player?>(null) }
    var magisTextureView by remember { mutableStateOf<android.view.TextureView?>(null) }
    // Task 1 (light-magis pruning): live channel, same pattern as magisItem/magisPlayer.
    val liveItem by vm.liveItem.collectAsStateWithLifecycle()
    var livePlayer by remember { mutableStateOf<Player?>(null) }
    // Caracol: same pattern as magisItem/magisPlayer. Played by DituExoPlayer (DASH + Widevine).
    val dituPlay by vm.dituPlayable.collectAsStateWithLifecycle()
    var dituPlayer by remember { mutableStateOf<Player?>(null) }
    /**
     * Whether the ExoPlayer already put a frame on screen.
     *
     * Needed by the spinner: `mirror.buffering` says whether data is MISSING, which isn't the
     * same as whether there IS a picture. ExoPlayer declares itself READY as soon as its buffer is
     * full, but the first frame can take a lot longer -- 6.5 s were measured on ditu, waiting for
     * the surface -- and in that gap the spinner had already left: a black screen with nothing to
     * explain the wait. libVLC did distinguish it with `esperandoPrimeraImagen`, and that
     * distinction was lost moving to ExoPlayer.
     */
    var exoRenderedSomething by remember { mutableStateOf(false) }
    val liveGeneration by vm.liveGeneration.collectAsStateWithLifecycle()
    val loadError by vm.error.collectAsStateWithLifecycle()
    // On-demand account prompt (no upfront offer any more, see ArkivRoot/ArkivTvRoot): true when
    // `loadError` is specifically "this live channel needs a linked Magis account", so the screen
    // can offer a way to fix it instead of a dead-end message.
    val needsMagisAccount by vm.needsMagisAccount.collectAsStateWithLifecycle()
    var showMagisLinkOffer by remember { mutableStateOf(false) }
    // TV: the "Vincular cuenta" button lives outside the pause overlay's own focus system
    // (`OverlayFocusPoints`), same situation as the "Saltar intro/outro" button -- see its own
    // `SkipButtonFocus`/`retryFocus` KDoc for why. Without grabbing focus explicitly here,
    // Compose's directional focus search has nothing nearby to land on (the transport controls
    // are usually hidden right where this shows), and a TV guest hitting NO_ACCOUNT would be
    // stranded with no D-pad path to it.
    val linkAccountFocus = remember { FocusRequester() }
    var linkAccountFocused by remember { mutableStateOf(false) }
    LaunchedEffect(needsMagisAccount, isTv) {
        if (!isTv || !needsMagisAccount) return@LaunchedEffect
        retryFocus(
            isAlreadyFocused = { linkAccountFocused },
            wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
            request = { linkAccountFocus.requestFocus() },
        )
    }
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    // Web source: while blog's resolver sniffs the stream, and the sniffed subtitles to attach.
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    val webExtras by vm.webExtras.collectAsStateWithLifecycle()
    val trivia by vm.trivia.collectAsStateWithLifecycle()

    // How the source is named in the "Resolviendo…" banner. `vm.resolving` is turned on by loads
    // that resolve against the network —`loadMagis` and `loadDitu`; `loadUnknownSource` (ids from
    // sources removed in this branch's pruning) only turns it off—, but the text assumed it was
    // web: playing a Magis chapter announced a web source that doesn't exist on that path.
    // No other source turns on that flag (archive had its own banner, and it was removed in this
    // branch's pruning).
    val resolvingSourceName = remember(episodeId) {
        when (PlayerSource.kindFor(episodeId)) {
            SourceKind.MAGIS -> "de Xuper"
            SourceKind.DITU -> "de Caracol"
            else -> "web"
        }
    }
    // Live mode (Task 14): isolates ALL of VOD's different behavior (no progress bar or seek, its
    // own overlay, zapping) behind these flags, computed ONCE from the episodeId the screen was
    // composed with. Zapping changes the channel WITHIN the ViewModel's playlist; it never
    // navigates to a new episodeId (see the LaunchedEffect(playlist) below), so they can never go
    // stale during a live session.
    //
    // Two of them. `isLive` is ANY live channel, Magis's or Caracol's
    // ([PlayerSource.isLiveChannel]): everything that makes no sense on a live stream hangs off it
    // (the progress bar and VOD's overlay, seeking by gesture and D-pad, saving the position,
    // auto-advance on end). `isMagisLive` is only Magis's: zapping, the drawer and the channel
    // card, reopening on cuts, and "Cambiando de canal…". A Caracol channel has none of that: it
    // opens from its own section, and `loadDitu` builds no zapping.
    val isLive = remember(episodeId) { PlayerSource.isLiveChannel(episodeId) }
    val isMagisLive = remember(episodeId) { PlayerSource.kindFor(episodeId) == SourceKind.LIVE }

    // Index of the item playing WITHIN the ViewModel's playlist. Lives up here -- and not with the
    // rest of the transport state, further below -- because `currentEpisode` needs it.
    var currentIndex by remember { mutableIntStateOf(0) }

    /**
     * The chapter that's playing RIGHT NOW, which isn't always the `episodeId` the screen was
     * opened with: archive.org (source removed in this branch's pruning) used to load the whole
     * section as a playlist -the only multi-item source that ever existed-, so when a chapter
     * ended the player advanced to the next one internally —or "Skip outro" did it with its
     * `seekToNextMediaItem()`— without navigating to a new route. The navigation argument was left
     * with the old chapter forever. No current source builds a playlist with more than one item
     * (see [OutroSkip]), but the read-by-index stays: it's the same source of truth that avoids
     * this whole class of bug if it's ever needed again.
     *
     * Hanging the neighbors and the header off that argument had visible consequences: after
     * auto-advance, "Next episode" led to the chapter that was ALREADY playing, "Previous chapter"
     * to the one that had just ended, the header kept naming the old one, and the carousel
     * highlighted the wrong chip. The rest of the screen (saving progress, capturing the frame)
     * already identified itself this way, by the playlist and not by the argument.
     */
    val currentEpisode = playlist?.items?.getOrNull(currentIndex)?.episodeId ?: episodeId

    // Overlay header and neighboring episodes: in `HeaderState.kt`, all three come from the same
    // query and change together on jumping chapters.
    val header = rememberHeaderState(graph.repository)
    HeaderEffect(header, currentEpisode)

    // D-pad focus (TV) for the pause overlay's controls: the twelve landing points live together
    // in `OverlayFocusPoints.kt`, see its KDoc.
    val focusPoints = rememberOverlayFocusPoints()
    // Chapter carousel (TV): one step below the icon row. Shows up with every episode of the
    // series in horizontal scroll, with the current one centered and focused. All its state and
    // its three effects live in `ChapterCarousel.kt`.
    val chaptersState = rememberChaptersState(graph.repository)
    ChaptersEffects(chaptersState, episodeId, currentEpisode, isTv)

    // The CastPlayer lives in the AppGraph, not here: releasing it ends the Chromecast session, so
    // while it was the screen's, leaving the player killed the cast.
    val castSession = remember { graph.castSession }
    val castPlayer = castSession?.player
    // The `remember` on the backing flow is necessary: without it a new MutableStateFlow would be
    // created on every recomposition and the collector would restart over and over.
    val castingFlow = remember(castSession) {
        castSession?.casting ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val casting by castingFlow.collectAsStateWithLifecycle()

    // What the screen knows about the active player (position, duration, playing, buffering) lives
    // together in `PlayerMirror.kt`: the values almost the whole interface reads at once.
    val mirror = rememberPlayerMirror()

    // --- Fun facts ---
    // The fact advances by PRESS, not by the clock: each `up` (or the "i" button, or tapping the
    // badge on the phone) shows the next one, and past the last one it goes back to the first. The
    // state and the two interface pieces live in `PlayerTrivia.kt`.
    val triviaState = rememberTriviaState()
    TriviaEffects(triviaState, trivia.size, episodeId)

    var loaded by remember { mutableStateOf(false) }
    // Episode this screen already sent to the receiver. Coordinates the two paths that cast (the
    // playlist load and the local→cast jump in LaunchedEffect(casting)): if the user connects
    // right on the frame the playlist arrives, both effects run and without this the receiver
    // would reload the same thing twice. Cleared on disconnect.
    var castToReceiver by remember { mutableStateOf<String?>(null) }

    // Titles whose remux failed. They fall back to HLS segments immediately instead of waiting for
    // something that will not arrive: a muxer error left the screen with nothing cast at all
    // (measured 2026-09-12, `code=7002` after seven minutes), and stuttery beats blank every time.
    val remuxFailed = remember { mutableStateListOf<String>() }

    /**
     * Where each title's remux is clipped, in ms, already snapped to a real keyframe.
     *
     * Computed once per title because finding it costs a couple of reads from the CDN, and read
     * back everywhere the remux is looked up so the key always matches the one it was filed under.
     */
    val remuxStartPoint: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Long> =
        remember { mutableStateMapOf() }

    /**
     * Which episode is on the receiver AS A REMUX, as opposed to as HLS segments.
     *
     * Separate from [castToReceiver] because they answer different questions, and conflating
     * them broke the upgrade: the HLS cast goes out first and marks the episode as cast, so the
     * loop waiting for the remux saw "already cast" and gave up -- the TV stayed on the stuttering
     * segments forever while a perfectly good mp4 finished behind it.
     */
    var castAsRemux by remember { mutableStateOf<String?>(null) }

    // The player we're driving right now: the Chromecast's while there's a session, the local one
    // if not. Both implement Player, so the controls don't need to know which one it is.
    // The `?: controller` covers the case with no Google Play Services (castContext and castPlayer null).
    // For downloaded files it is `controller`: the local player lives in PlaybackService (see the
    // background rule in the ON_STOP observer below).
    val activePlayer: Player = when {
        casting -> castPlayer ?: controller
        magisItem != null && magisPlayer != null -> magisPlayer!!
        liveItem != null && livePlayer != null -> livePlayer!!
        dituPlay != null && dituPlayer != null -> dituPlayer!!
        else -> controller
    }

    /**
     * Content position and duration, whether local or cast.
     *
     * Without a transcoder the receiver always counts from the same point as the file, but a live
     * stream can still send `TIME_UNSET` as duration: reading it raw would leave the bar showing a
     * negative number instead of "no duration". The translation lives in CastProgress (with tests)
     * so there isn't a second copy that can drift.
     */
    /**
     * Where the remux being cast BEGINS inside the title, in ms, or 0.
     *
     * A remux is clipped to start where playback was, so the receiver counts from ITS zero while
     * the title is minutes further along. Without adding this back, the phone's bar mixes two
     * different clocks.
     */
    fun remuxOffset(): Long {
        if (!casting) return 0L
        val ep = magisItem?.episodeId ?: return 0L
        val cdn = magisItem?.castUrl?.takeIf { it.isNotBlank() } ?: return 0L
        // From the stored, keyframe-aligned point -- NOT from the local player's live position,
        // which keeps moving and would make the bar jump every time it was read.
        val key = com.arkiv.player.playback.RemuxPolicy.keyFrom(cdn, remuxStartPoint[ep] ?: 0L)
        return com.arkiv.player.playback.RemuxPolicy.fromInKey(key)
    }

    fun contentPositionMs(): Long =
        CastProgress.contentPosition(activePlayer.currentPosition) + remuxOffset()

    /**
     * Does the position the player reports describe what THIS screen opened?
     *
     * On entering a new episode the controller still has the previous one: it stays READY and
     * keeps returning its position and its duration. Adopting them painted the new video's bar
     * with the old one's progress -- a freshly opened movie started out marked at 30 minutes --
     * until the playlist arrived and fixed it on its own. Holds when this screen already loaded
     * its playlist, or when what's playing IS ALREADY this episode (re-entering what was already
     * playing, where the position is correct from the first frame and hiding it would be the
     * opposite flicker).
     */
    fun positionBelongsToThisScreen(): Boolean =
        magisItem != null ||
        liveItem != null ||
        dituPlay != null ||
        loaded || runCatching { controller.currentMediaItem?.mediaId }.getOrNull() == episodeId

    /**
     * How long the title runs, for the bar.
     *
     * Casting a remux, the receiver reports no duration at all: the file is announced as a live
     * stream, which is what stopped it inventing an end and stalling against it, and a live stream
     * has none. The bar then had a position and nothing to divide it by, so it filled and emptied
     * at random. The phone does know the real duration -- the local player has been showing it all
     * along -- so it uses that instead of the receiver's non-answer.
     */
    fun contentDurationMs(): Long {
        // Casting a REMUX, the receiver's duration is never usable and "is it greater than zero"
        // is not a good enough test of that. It reports nothing at all for a file announced as
        // live, and when it does report something it is whatever it worked out from the fragments
        // that had arrived -- measured at 6592 ms for a title running one hour fifty, which drew
        // the bar at 55077% and is exactly the "bar goes crazy" being chased here. The phone knows
        // the real figure and has been drawing its own bar with it all along.
        val local = runCatching {
            (magisPlayer ?: controller).duration.takeIf { it > 0 } ?: 0L
        }.getOrDefault(0L)
        if (casting && local > 0L && remuxOffset() >= 0L && magisItem != null) return local
        val fromReceiver = CastProgress.contentDuration(activePlayer.duration)
        if (fromReceiver > 0L) return fromReceiver
        return local
    }

    // Custom controls (torrent style): visible on tap, auto-hide while playing.
    // Starts HIDDEN: on opening you see the loading spinner and then the clean video, without the
    // pause overlay/bar on top. The user taps the screen to show the controls.
    val controls = rememberControlsState()

    // Live mode (Task 14): its OWN overlay, doesn't reuse controls.visible/controls.activityTick --
    // those govern VOD's progress bar/transport row, which don't exist in live. All its state
    // (channel card, EPG and drawer) lives in `PlayerLive.kt`; from here only the video's key
    // listener moves it, which stays this screen's own.
    val liveState = rememberLiveState()
    val liveChannel by vm.liveChannel.collectAsStateWithLifecycle()
    // Task 15: publish the channel name for NowPlayingPublisher (only runs on TV, but it costs
    // nothing to have it also set here on the phone). Without this the remote miniplayer's bar, on
    // sending a channel to the TV, stays blank: "live:<code>" isn't a library episodeId, so
    // ArkivRepository.headerInfo() has no title to return.
    LaunchedEffect(liveChannel?.name) {
        com.arkiv.player.playback.NowPlaying.liveChannelName = liveChannel?.name
    }

    // ExoPlayer (Magis): NowPlaying isn't updated by onMediaItemTransition.
    LaunchedEffect(magisItem?.episodeId) {
        val epId = magisItem?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }
    LaunchedEffect(liveItem?.episodeId) {
        val epId = liveItem?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }
    LaunchedEffect(dituPlay?.episodeId) {
        val epId = dituPlay?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }

    // Intro/outro marker editor: the source that used it (archive.org) was removed in this branch
    // (that source's ids fall through to `loadUnknownSource` today, which only reports an error)
    // and the button that opens it sits behind `SHOW_MARKERS_ON_PHONE = false`, but the
    // state stays alive because the rest of the overlay (the `markers.marking` guards, the
    // `BackHandler`, the key listener) reads it.
    val markers = rememberMarkersState()

    // Audio/subtitle picker. Tracks come from the bound in-screen ExoPlayer or, by default, from the
    // local (service) player through `controller`. The whole block lives in `PlayerTracks.kt`; from
    // here only `hasSubtitle` is checked, for the CC icon.
    val tracksState = rememberTracksState(controller, graph, episodeId)


    // Night mode: level of the black veil over the video, 0..DIM_MAX_LEVEL. Persisted in
    // SettingsStore (survives closing the app). Clamped on reading it in case an old out-of-range
    // value was left saved.
    val dimLevel by graph.settings.dimLevel.collectAsStateWithLifecycle()
    val clampedDimLevel = dimLevel.coerceIn(0, DIM_MAX_LEVEL)
    // Moving through the bar -- the slider drag and incremental jumps -- lives in `SeekState.kt`.
    // Who actually fires the seek stays here: it depends on the active player and whether the cast
    // is transcoding.
    val seek = rememberSeekState()

    // The local player's TextureView: where downloaded files paint, what their frames are captured
    // from, and (TV) the view that holds the D-pad key listener and gets the focus back after a dialog.
    var videoView by remember { mutableStateOf<android.view.TextureView?>(null) }
    // What the screen knows about the local player's picture (first frame, aspect, surface). See
    // PlayerVideoLocal.kt.
    val localVideo = remember { LocalVideoState() }
    // Embedded subtitles of a downloaded file: libVLC painted them itself, ExoPlayer hands the cues
    // to whoever draws them (same as MagisExoPlayer's SubtitleView). Created by its AndroidView
    // factory, like the video view: a remembered View can't be re-parented when it is mounted again.
    var localSubtitles by remember { mutableStateOf<SubtitleView?>(null) }

    /**
     * A local item started loading: the first-frame wait restarts and the track menu forgets the
     * previous item, whose tracks the service player is still reporting (it keeps playing in the
     * background) and which would otherwise spend the one-shot language auto-pick.
     */
    fun markLocalLoad(prefersSoftware: Boolean) {
        localVideo.onLoad(android.os.SystemClock.elapsedRealtime(), prefersSoftware)
        tracksState.onLocalItemLoad()
    }

    /**
     * The TextureView the video is being painted on, for frame captures.
     *
     * Magis (ExoPlayer): MagisExoPlayer sets up SURFACE_TYPE_TEXTURE_VIEW and hands it to us via
     * `onTextureViewReady` → `magisTextureView`.
     *
     * Local (downloaded files): this screen's own TextureView, bound to the service's ExoPlayer. It
     * belongs to the screen, not to the player, so it is still here for the exit capture even when
     * the AndroidView's `onRelease` already unbound it.
     */
    fun videoTextureView(): android.view.TextureView? = when {
        magisItem != null -> magisTextureView
        // Caracol paints on PlayerView's SurfaceView, not on a TextureView (see DituExoPlayer):
        // nothing to capture from.
        dituPlay != null -> null
        else -> videoView
    }

    // Re-bind the local video when coming back from another app (see VideoAttachPolicy). The decoder
    // only paints again from the next keyframe, and without a notice that gap looks like a hang.
    // Only if there WAS a picture before and it is playing: audio-only content never paints, and a
    // paused player has nothing new to paint.
    var waitingForVideo by remember { mutableStateOf(false) }

    // Different from [waitingForVideo], which is "there WAS a picture and it was lost coming back
    // from the background". This is "there hasn't been one yet": the black-screen-with-sound
    // startup. For the local player it is decided by [FirstFrameWait] from [localVideo] and the
    // controller's tracks (see the polling loop).
    var noFirstFrame by remember { mutableStateOf(false) }

    // Identity of THIS composition of the player. On the screen being recreated (returning from
    // the background, navigation) two can end up coexisting, each with its own layout and its own
    // lifecycle observer; without being able to name them, they look the same in the log and
    // there's no way to know which one binds the video and which one releases it.
    val screenId = remember { SCREEN_SEQ.incrementAndGet() }
    DisposableEffect(Unit) {
        android.util.Log.w("ArkivVout", "SCREEN #$screenId enters")
        onDispose {
            android.util.Log.w("ArkivVout", "SCREEN #$screenId exits (dispose)")
            // LEAVING THE PLAYER ENDS THE CAST. A Cast session outlives this screen, so walking
            // out of an episode and opening another one used to arrive with casting already on:
            // the new title went to the TV without anyone asking, and every remux and wait that
            // implies started on its own. Casting is a thing the person does on purpose, once,
            // for what they are watching -- so it ends with what they were watching.
            //
            // Only when the screen really goes away. Rotating or the app going to the background
            // does not come through here with the activity kept, and auto-advance to the next
            // episode replaces the item WITHOUT disposing this, so a series still plays on
            // through to the TV.
            if (runCatching { graph.castSession?.casting?.value }.getOrNull() == true) {
                android.util.Log.w("ArkivCast", "leaving the player → ending the cast session")
                runCatching { graph.castSession?.stopIntentionally() }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, serviceExo) {
        var hadVideo = false
        val policy = VideoAttachPolicy(
            attach = {
                val v = videoView
                if (v == null) {
                    android.util.Log.w("ArkivVout", "ON_START #$screenId but videoView=null → nothing to bind")
                } else {
                    android.util.Log.w("ArkivVout", "ATTACH ON_START#$screenId view=#${Integer.toHexString(System.identityHashCode(v))}")
                    serviceExo.setVideoTextureView(v)
                    localVideo.onSurfaceAttached(android.os.SystemClock.elapsedRealtime())
                }
                waitingForVideo = hadVideo && controller.playWhenReady
            },
            detach = {
                // Only the local player's own picture counts: with an in-screen player on, the
                // service player has nothing loaded.
                hadVideo = localVideo.renderedFirstFrame &&
                    magisItem == null && liveItem == null && dituPlay == null
                android.util.Log.w("ArkivVout", "DETACH ON_STOP#$screenId hadVideo=$hadVideo")
                videoView?.let { serviceExo.clearVideoTextureView(it) }
                localVideo.onSurfaceDetached()
            },
        )
        // The raw events are logged apart from what the policy decides: the policy deliberately
        // ignores the first ON_START (see VideoAttachPolicy), so "the event arrived" and "there
        // was a re-bind" are two different facts and need to be seen separately.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    android.util.Log.w("ArkivVout", "CYCLE #$screenId ON_START (owner=${lifecycleOwner.hashCode()})")
                    policy.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    android.util.Log.w("ArkivVout", "CYCLE #$screenId ON_STOP (owner=${lifecycleOwner.hashCode()})")
                    policy.onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            android.util.Log.w("ArkivVout", "CYCLE #$screenId observer removed")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Polls until the local player paints on the re-bound surface (ExoPlayer notifies
    // onRenderedFirstFrame again for each new surface). The timeout is a safety net: if no frame comes
    // back (error, no video), the spinner goes away anyway instead of hanging forever.
    LaunchedEffect(waitingForVideo) {
        if (!waitingForVideo) return@LaunchedEffect
        withTimeoutOrNull(15_000) {
            while (!localVideo.paintedSinceAttach) delay(150)
        }
        waitingForVideo = false
    }

    // DLNA state: lives entirely in `PlayerDlna.kt` (state, actions, and its three UI pieces). Of
    // all that, this screen only checks `active`, because having a renderer running hides the
    // local controls.
    val dlnaState = rememberDlnaState(dlna, graph.applicationScope)

    val d = playlist?.items?.getOrNull(currentIndex)
    val playlistRef = rememberUpdatedState(playlist)

    /**
     * Builds what has to be sent to the receiver for item [idx] of [pl], starting at
     * [startPositionMs]. One single place on purpose: the TWO paths that cast use it -- opening a
     * chapter while already casting, and connecting the Chromecast to the chapter already playing
     * on the phone. If they diverged, what reaches the TV would depend on which way you came in.
     */
    /**
     * Is this Magis title an MPEG-TS? Only those need the HLS wrapper for cast.
     *
     * Read from the CDN url, which is the only place the true container survives: the proxy url
     * the phone plays from has no extension at all, so guessing from it always answers mp4.
     * `MagisResolve` builds the CDN url as `_media.ts` or `_media.mp4` straight from the portal's
     * `videoFormat`.
     */
    fun magisIsTs(item: PlayerData): Boolean =
        com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty()) == "video/mp2t"

    fun castRequestFor(pl: PlaylistData, idx: Int, startPositionMs: Long): com.arkiv.player.cast.CastRequest? {
        val item = pl.items.getOrNull(idx) ?: return null
        // TEMPORARY DIAGNOSTIC (Magis cast): what's about to be sent to the receiver and with what URL.
        android.util.Log.w(
            "ArkivCast",
            "castRequestFor · ep=${item.episodeId} kind=${item.kind} from=${startPositionMs}ms " +
                "castUrl=${item.castUrl?.take(120)} mediaUrl=${item.mediaUrl.take(120)}",
        )
        val isLiveItem = item.kind == SourceKind.LIVE
        // What audio this carries and whether the receiver can handle it. Read from the LOCAL
        // player, which is the one that already parsed the file. A "can't decode it" here explains
        // the mute video that used to leave no trace at all: AC-3 (Avatar) and DTS (Naruto), both
        // with H.264, which is why the picture showed. Live (Task 18) uses EXACTLY the same
        // gatekeeper: it reads the audio that's ALREADY playing on the phone -the channel is
        // playing by the time this is reached, never before- so no list of allowed channels or
        // guessing by name/category is needed.
        val audio = localAudioFormat(controller.currentTracks)
        // Why a remux costs minutes instead of being a copy. `TransformerUtil.shouldTranscodeVideo`
        // re-encodes unconditionally when `pixelWidthHeightRatio != 1`, and broadcast transport
        // streams very often declare a non-square pixel. This is the one number that says whether
        // a true transmux is even reachable for this title.
        val videoLocal = localVideoFormat(controller.currentTracks)
            ?: magisPlayer?.let { runCatching { localVideoFormat(it.currentTracks) }.getOrNull() }
        android.util.Log.w(
            "ArkivCast",
            "source video · mime=${videoLocal?.sampleMimeType} ${videoLocal?.width}x${videoLocal?.height} " +
                "par=${videoLocal?.pixelWidthHeightRatio} → transmux ${
                    if (videoLocal?.pixelWidthHeightRatio == 1f) "possible" else "BLOCKED by a non-square pixel"
                }",
        )
        // .coerceAtLeast(0): media3 reports an unset channel count as Format.NO_VALUE (-1), which
        // would otherwise show up in the log below as "canales=-1". Doesn't change the decodable
        // decision (receiverDecodes only compares it against AAC's <=2 stereo cap).
        val channelCount = (audio?.channelCount ?: 0).coerceAtLeast(0)
        val decodable = com.arkiv.player.cast.CastAudioSupport.receiverDecodes(
            sampleMimeType = audio?.sampleMimeType,
            channelCount = channelCount,
        )
        android.util.Log.i(
            "ArkivCast",
            "source audio · mime=${audio?.sampleMimeType ?: "unknown"} " +
                "channels=$channelCount → ${if (decodable) "goes straight through" else "might play mute"}",
        )

        // The URL the receiver can reach: live's (LiveHlsProxy) LAN proxy -- same reason as the
        // rest of this method, see CastRequestBuilder's KDoc. `graph.lanIp()` is a generic helper
        // (the phone's LAN IP), nothing specific to any source.
        //
        // MAGIS is the same shape for a different reason: its origin is remote, but the CDN wants
        // `Content-Auth`/`Content-License` and the Cast receiver cannot send custom headers, so it
        // gets 401 from the CDN and has to come through our proxy like live does. No socket is
        // widened to do this -- `ArchiveCacheProxy.start()` already listens on every interface; it
        // is the same loopback url the phone is playing from, respelled. See its `lanUrl` KDoc.
        val lanIp = graph.lanIp()
        // Finished OR still being written: a fragmented MP4 is playable before it is complete,
        // which is what turns "wait minutes, then cast" into "cast now, it fills in behind you".
        var magisRemuxGrowing = false
        val remuxMagis = if (item.kind == SourceKind.MAGIS) {
            item.castUrl?.let { cdn ->
                // COMPLETE only. Serving one while it grew was the plan, and the receiver
                // settled it: it recomputes the duration from the fragments it has and reports a
                // new one every second or two (`kDurationChanged 75.25 … 80.25`, read off its own
                // log), ignoring the duration we send it. So playback chases an end that keeps
                // moving just ahead of it, reaches it, stalls, gets more, resumes -- the "loading"
                // that came back no matter how large the head start was, 64 s of cushion included.
                // A finished file has one duration and stays still.
                // The same key the remux was filed under: the one that says where it begins.
                // The SAME key the remux was filed under: the keyframe-aligned point, not the
                // raw position, which drifts as the local player keeps its own time.
                graph.tsRemuxer.inProgress(
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(
                        cdn,
                        remuxStartPoint[item.episodeId] ?: 0L,
                    ),
                )?.let { (file, complete) ->
                    // ALWAYS chunked, finished or not. Measured 2026-09-12, and it is the
                    // difference between playing and not: served while it grew -- chunked, no
                    // Content-Length, no ranges -- the receiver had nothing to do but play from
                    // the start, and it played. Served complete, with a length and range support,
                    // it went hunting through 1.4 GB for an index a fragmented MP4 does not carry
                    // (`range=bytes=308510720-`, 4 MB, broken pipe, a slightly later range, over
                    // and over) and never produced a frame. Withholding the ability to seek is
                    // what makes it work, which is backwards but it is what the device does.
                    graph.localFileServer.growing = true
                    magisRemuxGrowing = !complete
                    android.util.Log.w(
                        "ArkivCast",
                        "magis → remuxed mp4 (${if (complete) "complete" else "still growing, ${file.length()}B"})",
                    )
                    graph.localFileServer.serve(file)
                }
            }
        } else {
            null
        }
        val lanUrl = when (item.kind) {
            SourceKind.LIVE -> lanIp?.let { graph.liveHlsProxy.lanUrl(it) }
            // Magis: through the proxy either way, because the CDN wants headers the receiver
            // cannot send. WHICH proxy url depends on the container -- see `magisIsTs` below.
            // A finished remux wins over both: it is an MP4 served off this device, so the CDN's
            // headers stop mattering and the receiver gets per-sample timing. Keyed by the CDN url
            // because the proxy url carries tokens that change on every resolve.
            SourceKind.MAGIS -> remuxMagis ?: lanIp?.let {
                if (magisIsTs(item)) {
                    com.arkiv.player.playback.ArchiveCacheProxy.lanPlaylistUrl(item.mediaUrl, it)
                } else {
                    com.arkiv.player.playback.ArchiveCacheProxy.lanUrl(item.mediaUrl, it)
                }
            }
            else -> null
        }
        if (item.kind == SourceKind.MAGIS) {
            android.util.Log.w(
                "ArkivCast",
                "magis cast url · lanIp=${lanIp ?: "NONE"} " +
                    "proxyLocal=${item.mediaUrl.take(60)} → lan=${lanUrl?.take(60) ?: "NULL (cannot cast)"}",
            )
        }

        // A downloaded file is the one case where the container can be KNOWN instead of guessed:
        // the bytes are on this device. The cast URL is the local server's ("…/file", no extension),
        // so guessing by extension always answered mp4 while the server served what the bytes say --
        // the receiver was told one container and handed another. `mediaUrl` is "file://<path>" for
        // LOCAL, which is where the path comes from.
        // SPIKE: a local file is cast as a one-segment HLS playlist, not as a bare MPEG-TS. The
        // receiver refuses the latter (measured 2026-09-12: fetched 5.3 MB, broken pipe) and accepts
        // the former, whose segments are that very same MPEG-TS.
        // A downloaded file that has already been remuxed is cast as the MP4, which is what the
        // receiver wants: explicit per-sample timing instead of deriving it from the transport
        // stream's PTS/DTS. Only when there is no remux yet does it fall back to serving the
        // original as HLS segments. The remux itself is kicked off by the effect below -- this
        // function stays synchronous because every cast path calls it.
        val remuxLocal = if (item.kind == SourceKind.LOCAL) {
            graph.tsRemuxer.alreadyDone(item.mediaUrl)?.let {
                graph.localFileServer.growing = true
                graph.localFileServer.serve(it)
            }
        } else {
            null
        }
        if (item.kind == SourceKind.LOCAL) {
            android.util.Log.i(
                "ArkivCast",
                "local file → ${if (remuxLocal != null) "remuxed mp4" else "HLS segments (no remux yet)"}",
            )
        }
        val hlsLocal = if (item.kind == SourceKind.LOCAL && remuxLocal == null) {
            graph.localFileServer.playlistUrl()
        } else {
            null
        }
        val mimeLocal = if (item.kind == SourceKind.LOCAL) {
            runCatching {
                com.arkiv.player.playback.VideoContainer
                    .ofFile(java.io.File(item.mediaUrl.removePrefix("file://"))).mime
            }.getOrNull()
        } else {
            null
        }
        if (mimeLocal != null) {
            android.util.Log.i(
                "ArkivCast",
                "local container from its bytes: $mimeLocal (url guess was ${
                    com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl ?: item.mediaUrl)
                })",
            )
        }

        // Only an MPEG-TS needs the playlist, and it is the container that decides -- not the
        // source. The receiver refuses a bare transport stream served progressively
        // (`FFmpegDemuxer: open context failed`, read off its own log 2026-09-12) so that one is
        // announced as HLS and served in segments. An mp4 it accepts as-is, and wrapping one
        // would only add the segmenter's cost and its rough edges for nothing: Magis serves both
        // (`MagisResolve` picks `_media.ts` vs `_media.mp4` from the portal's `videoFormat`).
        val mimeMagis = if (item.kind == SourceKind.MAGIS) {
            when {
                remuxMagis != null -> "video/mp4"
                magisIsTs(item) -> "application/vnd.apple.mpegurl"
                else -> com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty())
            }
        } else {
            null
        }
        if (mimeMagis != null) {
            android.util.Log.i(
                "ArkivCast",
                "magis container=${com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty())} " +
                    "→ ${if (magisIsTs(item)) "HLS playlist (only the announcement changes)" else "straight through, no playlist needed"}",
            )
        }
        val request = com.arkiv.player.cast.CastRequestBuilder.build(
            episodeId = item.episodeId,
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.artworkUrl,
            mediaUrl = item.mediaUrl,
            castUrl = remuxLocal ?: hlsLocal ?: item.castUrl,
            lanUrl = lanUrl,
            // HLS segments keep the resume position -- every segment boundary is a real entry
            // point since TsSegmenter cuts them on keyframes. A REMUX does not: a fragmented MP4
            // carries no seek index, that being the price of playing while it is written. Asking
            // the receiver to start at minute 4:52 of one sent it hunting through the file blind
            // -- `range=bytes=308510720-`, 4 MB, broken pipe, a slightly later range, again,
            // without ever playing a frame (measured 2026-09-12). Starting at zero is what makes
            // it play. Losing "where you were" is the cost, and getting it back means writing a
            // real index.
            startPositionMs = if (remuxLocal != null || remuxMagis != null) 0L else startPositionMs,
            isLive = isLiveItem,
            mimeOverride = when {
                remuxLocal != null -> "video/mp4"
                hlsLocal != null -> "application/vnd.apple.mpegurl"
                mimeMagis != null -> mimeMagis
                else -> mimeLocal
            },
            // Magis has no usable fallback: `castUrl` is the CDN, which answers 401 without headers
            // the receiver cannot send, and `mediaUrl` is loopback. Either `lanUrl` or nothing --
            // ALWAYS, including when a remux exists, because the remux's url is what `lanUrl`
            // holds in that case. Letting this go false when there was a remux sent the receiver
            // the raw CDN url instead (measured 2026-09-12: `uri=http://…_media.ts mime=video/mp4`),
            // since the builder falls back to `castUrl` whenever it is not required to use the LAN.
            requiresLanUrl = item.kind == SourceKind.MAGIS,
            // While the remux is still being written it IS a live stream, and saying so is what
            // keeps the receiver from inventing an end and stalling against it.
            asLive = magisRemuxGrowing,
            // Where the remux begins, so a saved position lands on the right minute of the title.
            offsetMs = if (item.kind == SourceKind.MAGIS) {
                com.arkiv.player.playback.RemuxPolicy.fromInKey(
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(
                        item.castUrl.orEmpty(),
                        remuxStartPoint[item.episodeId] ?: 0L,
                    ),
                )
            } else {
                0L
            },
            // The local player already knows how long this runs -- it has been showing it on the
            // bar. A remux still being written cannot state it, so without this the receiver
            // invents one from the fragments it has (5 s for a two-hour film) and stalls on that
            // imaginary end every few seconds.
            durationMs = runCatching {
                (magisPlayer ?: controller).duration.takeIf { it > 0 } ?: 0L
            }.getOrDefault(0L),
        )
        // No transcoder: audio the receiver can't decode still gets cast, muted, instead of not
        // casting at all. The warning is the only thing that tells that case apart from a normal cast.
        if (!decodable && request != null) {
            android.widget.Toast.makeText(
                context,
                "Este audio podría no sonar en el Chromecast",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        // TEMPORARY DIAGNOSTIC (Magis cast): what it comes out as — null means "no reachable URL".
        android.util.Log.w(
            "ArkivCast",
            "castRequestFor → ${if (request == null) "NULL" else "uri=${request.uri.take(120)} mime=${request.mimeType}"}",
        )
        return request
    }

    /**
     * Cast-to-TV of the live channel when ExoPlayer plays it (Task 1, light-magis pruning).
     *
     * This used to be triggered by `LaunchedEffect(playlist, liveGeneration)` (below) because the
     * channel traveled in `_playlist`; now it travels in `liveItem` (see
     * PlayerViewModel.openCurrentChannel) and that effect only runs for VOD. A synthetic
     * single-item `PlaylistData` is built to reuse [castRequestFor] as-is -- that function reads
     * nothing from `PlaylistData` but `items`/the index, so there's no need to duplicate the
     * lanUrl/audio-reader logic.
     *
     * `liveGeneration` in the key: reopening the SAME channel after a cut produces a `PlayerData`
     * equal to the previous one (same reason as `_liveGeneration` in the ViewModel, see its KDoc),
     * so without this key a re-zap to the channel already on screen wouldn't push the receiver again.
     *
     * The local player's audio read means nothing here (the channel plays on LiveExoPlayer, not on
     * the service player, so it reads nothing or a stale item), so `castRequestFor` falls back to its
     * conservative default ("don't know → send it straight through", see its own KDoc), the same one
     * it already accepts for Magis VOD. A channel with AC-3/DTS audio can cast mute: a known
     * limitation, the same category as Magis's.
     */
    LaunchedEffect(casting, liveItem, liveGeneration) {
        if (!casting || castSession == null) return@LaunchedEffect
        val item = liveItem ?: return@LaunchedEffect
        val pl = PlaylistData(listOf(item), 0, 0L, requested = item.episodeId)
        val req = castRequestFor(pl, 0, 0L)
        if (req == null) {
            // Same warning VOD already gives when castRequestFor finds no URL the TV can reach
            // (see the identical Toast further below in this file) -- before this migration,
            // live-via-VLC showed it too; it got lost porting the block to ExoPlayer.
            android.util.Log.w("ArkivCast", "live (exo): no URL the receiver can reach")
            android.widget.Toast.makeText(
                context,
                "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return@LaunchedEffect
        }
        castSession.setMedia(req)
        castToReceiver = item.episodeId
        NowPlaying.episodeId = item.episodeId
    }

    /**
     * Remuxes a downloaded MPEG-TS to MP4 while it is being cast, and re-casts it when done.
     *
     * The first cast of a title goes out as HLS segments, which plays but leaves the receiver
     * deriving every frame's presentation time from PTS/DTS -- hundreds of
     * `Failed to get frame timestamps` a minute on the KALLEY, and visible judder with the decoder
     * otherwise healthy. The remux removes that entirely, and it costs almost no CPU because
     * nothing is re-encoded.
     *
     * It runs WHILE the cast plays rather than before it, so the person waits for nothing: the
     * segments carry the picture meanwhile, and the swap happens when the MP4 is whole. Done once
     * per file -- `alreadyDone` short-circuits every later cast of the same title.
     *
     * Only for a LOCAL file. A remote title would mean downloading all of it before the MP4 could
     * be finalised (the index lands at the end), which is the wait a fragmented MP4 exists to
     * avoid; that path is separate.
     */
    LaunchedEffect(casting, d?.episodeId, d?.kind, magisItem?.episodeId) {
        if (!casting || castSession == null) return@LaunchedEffect
        // Magis travels in `magisItem`, everything else in the playlist.
        val item = magisItem?.takeIf { it.kind == SourceKind.MAGIS } ?: d ?: return@LaunchedEffect

        // What to feed the remuxer, and what to key it by. They differ for Magis: the input is the
        // loopback proxy (which puts the CDN's auth headers on), while the key is the CDN url,
        // stable across resolves -- keying by the proxy url would remux the same title again every
        // time its tokens were refreshed.
        val (input, key, mime) = when (item.kind) {
            SourceKind.LOCAL -> {
                val file = java.io.File(item.mediaUrl.removePrefix("file://"))
                if (!file.exists()) return@LaunchedEffect
                Triple(
                    item.mediaUrl,
                    item.mediaUrl,
                    runCatching { com.arkiv.player.playback.VideoContainer.ofFile(file).mime }.getOrNull(),
                )
            }
            SourceKind.MAGIS -> {
                val cdn = item.castUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
                // Where it must start, SNAPPED TO A KEYFRAME. Clipping anywhere else leaves the
                // tracks misaligned -- measured at 1.57 s of audio with no picture -- because the
                // muxer moves video back to a keyframe while audio begins exactly where asked.
                // Where playback actually is. NOT `magisPlayer ?: controller`: when this runs the
                // Magis player may not exist yet -- it is created from the item and the cast can
                // beat it -- and `controller` is the service player, which for Magis holds nothing
                // and answers 0. That silently cast from the beginning every time the race went
                // that way. `startPositionMs` is where the item was told to open, which is the
                // right answer whenever the live position is not available yet.
                val livePosition = runCatching { magisPlayer?.currentPosition }.getOrNull()?.takeIf { it > 0 }
                val requestedPosition = (livePosition ?: item.startPositionMs).coerceAtLeast(0L)
                android.util.Log.w(
                    "ArkivCast",
                    "resume point: ${requestedPosition}ms (${if (livePosition != null) "live position" else "the item's startPosition, player not ready"})",
                )
                // ALWAYS FROM ZERO. Clipping works -- the cut lands exactly on a keyframe now,
                // verified in the log -- and the audio still ran ahead of the picture. The cause
                // measured earlier (1.57 s of audio with no video in the opening fragment) was
                // fixed and the symptom survived it, so something else misaligns the tracks when
                // the remux does not start at the beginning. The likeliest remaining suspect is
                // outside our reach: media3's fragmented muxer writes no `tfdt`, the box that
                // anchors each fragment in time, so nothing ever re-syncs what starts out skewed.
                //
                // Starting at zero has no such problem and is measured good: real time, no stalls,
                // audio correct. Resuming is a convenience; watchable sound is not. The keyframe
                // search and the clipping stay in the code -- they are correct and they are what a
                // receiver that can seek would need.
                val aligned = 0L
                remuxStartPoint[item.episodeId] = 0L
                if (requestedPosition > 0L) {
                    android.util.Log.w(
                        "ArkivCast",
                        "starting the cast from zero, not from ${requestedPosition}ms: clipping desynchronises the audio",
                    )
                }
                Triple(
                    item.mediaUrl,
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(cdn, aligned),
                    com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(cdn),
                )
            }
            else -> return@LaunchedEffect
        }

        // Why this remux will cost minutes instead of being a copy. `TransformerUtil`
        // re-encodes unconditionally when `pixelWidthHeightRatio != 1`, and that is the only
        // condition left that can be firing here: the in-app muxer does accept H265 and AAC.
        // Logged before starting, because by the time the export runs the answer is already baked.
        val fmt = localVideoFormat(controller.currentTracks)
            ?: magisPlayer?.let { runCatching { localVideoFormat(it.currentTracks) }.getOrNull() }
        android.util.Log.w(
            "ArkivCast",
            "source video · mime=${fmt?.sampleMimeType} ${fmt?.width}x${fmt?.height} " +
                "par=${fmt?.pixelWidthHeightRatio} → transmux ${
                    when (fmt?.pixelWidthHeightRatio) {
                        null -> "unknown, no video format available"
                        1f -> "possible"
                        else -> "BLOCKED by a non-square pixel"
                    }
                }",
        )

        if (!com.arkiv.player.playback.RemuxPolicy.needsRemux(mime)) {
            android.util.Log.i("ArkivCast", "${item.kind} is $mime, no remux needed")
            return@LaunchedEffect
        }
        if (graph.tsRemuxer.alreadyDone(key) != null) return@LaunchedEffect

        // One growing fragmented mp4, announced as LIVE.
        //
        // The chase that broke every earlier attempt was ours to cause: a file still being written
        // was announced as "buffered", which tells the receiver the media has a definite end. It
        // then works one out from the fragments that have arrived and reports a new one every
        // second or two (`kDurationChanged 75.25 … 80.25`, off its own log), plays toward it, and
        // stalls each time it catches up. No head start fixed that -- 64 s of cushion stalled the
        // same as 6 MB -- because the end moves with the file.
        //
        // A live stream has no end to reach. That is both the truth about a file being written and
        // the thing that stops the chase. Cutting the title into a queue of finished chunks also
        // worked around it, but the receiver announces every queue entry with a countdown
        // ("Your video will play in N"), twice a minute.
        if (item.kind == SourceKind.MAGIS) {
            graph.applicationScope.launch(Dispatchers.Main) {
                repeat(600) {
                    delay(1000)
                    if (!casting || castSession == null) return@launch
                    if (castAsRemux == item.episodeId) return@launch
                    val partial = graph.tsRemuxer.inProgress(key) ?: return@repeat
                    // 40 MB, not 12. Measured 2026-09-12: casting at 14 MB stalled seven times
                    // in the first forty-five seconds and then never again -- the remux is still
                    // getting up to speed at that point, so playback catches it repeatedly, and
                    // once it is running (about 22x faster than playback consumes) it pulls away
                    // and the problem disappears on its own. Waiting for a bigger head start
                    // spends a few more seconds once and skips that whole stretch.
                    if (partial.first.length() < 40_000_000L) return@repeat
                    val retryPl = PlaylistData(listOf(item), 0, 0L, requested = item.episodeId)
                    val retryReq = castRequestFor(retryPl, 0, 0L) ?: return@repeat
                    android.util.Log.w(
                        "ArkivCast",
                        "remux has ${partial.first.length() / 1_000_000}MB → casting it as a live stream",
                    )
                    castSession.setMedia(retryReq)
                    castToReceiver = item.episodeId
                    castAsRemux = item.episodeId
                    return@launch
                }
            }
        }

        val res = graph.tsRemuxer.remux(input, key)
        if (res !is com.arkiv.player.playback.TsRemuxer.RemuxResult.Done) {
            // Remember the failure so this title stops waiting for a remux that will not come, and
            // fall back to the segments NOW. For Magis that fallback is the only thing standing
            // between the person and a blank screen, because nothing was cast while it prepared.
            android.util.Log.w("ArkivCast", "remux failed → falling back to HLS segments for this title")
            remuxFailed.add(key)
            if (item.kind == SourceKind.MAGIS && casting && castSession != null) {
                val now = runCatching { contentPositionMs() }.getOrDefault(0L).coerceAtLeast(0L)
                val fallbackPl = PlaylistData(listOf(item), 0, now, requested = item.episodeId)
                castRequestFor(fallbackPl, 0, now)?.let {
                    castSession.setMedia(it)
                    castToReceiver = item.episodeId
                }
            }
            return@LaunchedEffect
        }
        // Still casting the same thing? The export takes a while and the person may have moved on.
        if (!casting || castSession == null) return@LaunchedEffect
        val from = runCatching { contentPositionMs() }.getOrDefault(0L).coerceAtLeast(0L)
        // Magis has no playlist -- same synthetic one-item PlaylistData the rest of this screen
        // uses for it, so castRequestFor stays the single place that decides what goes to the TV.
        val pl = if (item.kind == SourceKind.MAGIS) {
            PlaylistData(listOf(item), 0, from, requested = item.episodeId)
        } else {
            playlistRef.value ?: return@LaunchedEffect
        }
        val idx = pl.items.indexOfFirst { it.episodeId == item.episodeId }.coerceAtLeast(0)
        val req = castRequestFor(pl, idx, from) ?: return@LaunchedEffect
        android.util.Log.w("ArkivCast", "remux ready → re-casting as mp4 from ${from}ms")
        castSession.setMedia(req)
        castToReceiver = item.episodeId
        castAsRemux = item.episodeId
    }

    /**
     * "Preparándolo para la TV — NN%" while a streaming MPEG-TS is being remuxed.
     *
     * The wait is the price of reading the title once instead of twice (see the cast effect), and
     * a still screen for a few minutes with no sign of life reads as a hang. Only for the case
     * that actually waits: a downloaded file casts immediately and swaps later, and an mp4 never
     * waits at all.
     */
    val remuxProgress by graph.tsRemuxer.progress.collectAsStateWithLifecycle()
    val preparingForTv = casting &&
        magisItem?.let { magisIsTs(it) && graph.tsRemuxer.alreadyDone(it.castUrl.orEmpty()) == null } == true
    if (preparingForTv) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Preparándolo para la TV" + if (remuxProgress in 0..100) " — $remuxProgress%" else "",
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Solo la primera vez de cada título",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /**
     * What the progress bar is being drawn from, while casting.
     *
     * The bar is computed from three numbers that come from different places, and when it goes
     * wrong it is never obvious which one is lying: the position comes from the RECEIVER (counting
     * from its own zero, because a remux is clipped), the offset says where that zero sits inside
     * the title, and the duration comes from the PHONE when the receiver reports none -- which it
     * does for anything announced as a live stream. Printing all three together is what tells
     * "the receiver reset" from "the offset is wrong" from "there is no duration to divide by".
     *
     * Every two seconds and only while casting: enough to see a bar jump, quiet the rest of the time.
     */
    LaunchedEffect(casting) {
        if (!casting) return@LaunchedEffect
        while (true) {
            val raw = runCatching { activePlayer.currentPosition }.getOrDefault(0L)
            val offset = runCatching { remuxOffset() }.getOrDefault(0L)
            val pos = runCatching { contentPositionMs() }.getOrDefault(0L)
            val dur = runCatching { contentDurationMs() }.getOrDefault(0L)
            val fromReceiver = runCatching { activePlayer.duration }.getOrDefault(0L)
            android.util.Log.i(
                "ArkivBarra",
                "receiver=${raw}ms + offset=${offset}ms = ${pos}ms · dur=${dur}ms " +
                    "(receiver said ${fromReceiver}ms) → ${
                        if (dur > 0) "%.1f%%".format(pos * 100.0 / dur) else "NO FRACTION (no duration)"
                    }",
            )
            delay(2000)
        }
    }

    fun bump() = controls.bump()

    // Speed, zoom, night mode, and the central HUD: all in `PlayerGestures.kt`. The `bump()` it
    // receives is the only thing tying them to this screen -- every adjustment counts as activity
    // and resets the controls' auto-hide. Goes down here, and not with the rest of the state,
    // because it needs `bump` already declared.
    val gestures = rememberGesturesState(controller, graph.settings) { bump() }
    BrightnessHudEffect(gestures)




    LaunchedEffect(controls.visible, mirror.buffering, casting, dlnaState.active, markers.mode, loadError) {
        android.util.Log.i(
            "ArkivCast",
            "UI bar · controls=${controls.visible} buffering=${mirror.buffering} casting=$casting " +
                "dlna=${dlnaState.active != null} marking=${markers.marking} error=${loadError != null} " +
                "→ overlay=${controls.visible && loadError == null && dlnaState.active == null && !markers.marking}",
        )
    }

    // The LOADING SPINNER, which is a different thing from the controls overlay above (that log
    // says `overlay=` and is the transport bar; confusing them costs a round of measuring).
    //
    // Logged separately because the failure that matters is invisible from outside: the black
    // startup with sound is exactly the instant when `mirror.buffering` is already false and
    // there's still no picture, so none of the old signals gives it away. With `noImage` you can
    // see whether the spinner covered that gap or the screen was left black.
    LaunchedEffect(playlist == null, magisItem == null, liveItem == null, dituPlay == null, mirror.buffering, noFirstFrame, waitingForVideo, casting) {
        val spinner = shouldShowSpinner(
            noPlaylist = playlist == null && magisItem == null && liveItem == null && dituPlay == null,
            buffering = mirror.buffering,
            noFirstFrame = noFirstFrame,
            lostVideoOutput = waitingForVideo,
            casting = casting,
        )
        android.util.Log.w(
            "ArkivSpinner",
            "spinner=$spinner " +
                "· noPlaylist=${playlist == null && magisItem == null && liveItem == null && dituPlay == null} buffering=${mirror.buffering} noImage=$noFirstFrame " +
                "lostVideo=$waitingForVideo",
        )
    }

    // On opening different content from what's loaded, cut the previous playback BEFORE resolving
    // the new source. Without this, the web resolver (slow, ~10s) left the previous video playing
    // behind the "Resolviendo…" overlay, and going back the player resumed the old video. If the
    // episode is ALREADY part of the loaded playlist (navigating within an archive series), it
    // isn't cut: the effect below reuses the buffer and jumps within the playlist.
    LaunchedEffect(episodeId) {
        val kind = PlayerSource.kindFor(episodeId)
        val loadedIds = (0 until controller.mediaItemCount).mapNotNull { controller.getMediaItemAt(it).mediaId }
        val alreadyLoaded = episodeId in loadedIds
        android.util.Log.w("ArkivPlay", "PlayerScreen enter episodeId=$episodeId kind=$kind alreadyInController=$alreadyLoaded loaded=$loaded loadedIds=$loadedIds")
        if (!alreadyLoaded) {
            // stop() cuts the old video; the setMediaItems below replaces the playlist once the
            // new source finishes resolving.
            android.util.Log.w("ArkivPlay", "stop() + loaded=false (new episodeId)")
            controller.stop()
            loaded = false
        }
        vm.load(episodeId)
    }

    // Initial load of the playlist into the controller (once only; editing markers doesn't
    // reload). `liveGeneration` in addition to `playlist`: reopening a cut channel republishes a
    // PlaylistData EQUAL to the previous one and the StateFlow drops it, so without this key the
    // effect never ran again and the reopen loaded nothing. See its KDoc in PlayerViewModel.
    LaunchedEffect(playlist, liveGeneration) {
        val pl = playlist ?: run { android.util.Log.w("ArkivPlay", "playlist=null (still resolving or discarded)"); return@LaunchedEffect }
        // Live (Task 14) NO LONGER goes through here (Task 1, light-magis pruning):
        // `openCurrentChannel` stops publishing `_playlist` and publishes `liveItem` -- see
        // LiveExoPlayer/isLiveExo above and the LaunchedEffect(casting, liveItem, liveGeneration)
        // that replaces the cast-to-TV that used to live here. `isLive` still exists for the rest
        // of the screen (overlay/gestures/D-pad), but this effect is purely VOD from now on.
        // Is this what THIS screen requested, or is it still the previous chapter's playlist? The
        // ViewModel survives navigation between chapters, so on entering the next one what's
        // published stays the previous one's for the whole resolve (~4 s on magis). Asked BEFORE
        // touching `loaded`, the position, or the cast: taking it as good would play the previous
        // chapter from the start and -worse- leave `loaded=true`, with which the good playlist
        // would never get in. See PlaylistData.requested's KDoc and MediaReusePolicy.decide.
        // Captured once and reused below by ReloadPositionPolicy: it needs the exact same
        // "what's the controller currently on" identity that the reuse decision itself used.
        val actualMediaId = controller.currentMediaItem?.mediaId
        val decision = MediaReusePolicy.decide(
            episodeId = episodeId,
            // What's loaded, with its URI. The URI is read from requestMetadata and NOT from
            // localConfiguration: this side is the controller, and localConfiguration is lost
            // crossing the IPC (see PlaybackService.MediaItemResolverCallback). Without the URI, "it's the same episode" was
            // the only signal to reuse it — and that signal alone isn't enough: the local server or
            // the origin URL can change from one load to the next even if the episode is the same
            // (it used to be torrent's case, now removed; today it's the live server's and Magis's
            // token's).
            loaded = (0 until controller.mediaItemCount).map { i ->
                val mi = controller.getMediaItemAt(i)
                LoadedMedia(mi.mediaId, mi.requestMetadata.mediaUri?.toString().orEmpty())
            },
            currentMediaId = actualMediaId,
            fresh = pl.items.map { LoadedMedia(it.episodeId, it.mediaUrl) },
            requested = pl.requested,
            // Did we land back on a NEW screen? Reusing the media with a new surface kills the
            // decoder (see MediaReusePolicy.decide for the measured numbers). The loaded media
            // already painted (the service player's decoder counters) but not on THIS screen, so it
            // painted on another screen's surface: the same question libVLC's
            // `superficieDistintaALaDelVideo` used to answer. False while it never painted.
            newScreen = (serviceExo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                !localVideo.renderedFirstFrame,
        )
        if (decision == MediaReusePolicy.Decision.WAIT) {
            android.util.Log.w("ArkivPlay", "playlist for ANOTHER episode (requested=${pl.requested} ≠ $episodeId) → waiting for mine")
            return@LaunchedEffect
        }
        if (loaded) {
            android.util.Log.w("ArkivPlay", "playlist ready but loaded=true → skip reload (guard). items=${pl.items.map { it.episodeId }}")
            return@LaunchedEffect
        }
        loaded = true
        mirror.jumpTo(pl.startPositionMs)
        android.util.Log.w("ArkivPlay", "playlist ready → load. decision=$decision startPos=${pl.startPositionMs}")
        if (casting && castSession != null) {
            val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
            val req = castRequestFor(pl, idx, pl.startPositionMs)
            if (req != null) {
                // The screen advanced to this episode: NowPlaying is what the notification and the
                // remote between devices read, not local bookkeeping — it can't stay pointing at
                // the previous chapter.
                NowPlaying.episodeId = episodeId
                android.util.Log.w("ArkivPlay", "branch=CAST → the episode goes to Chromecast, the local one stays primed in pause")
                // The local one loads JUST THE SAME —setMediaItems() already fires loadMedia() and
                // opens the file/URL, prepare() doesn't avoid that— but does NOT start: playWhenReady=false
                // is what keeps it from competing with the receiver for the stream. Left unprepared
                // on purpose: the real prepare() happens on resuming (LaunchedEffect(casting)) or,
                // if the screen was destroyed before resuming, in the STATE_IDLE guard of
                // sameEpisodePlaying/samePlaylist further below. Loading it is necessary anyway: if
                // not, it would keep holding the previous chapter and on disconnecting would resume
                // that one instead of what was being watched.
                currentIndex = idx
                controller.setMediaItems(localMediaItems(pl.items), idx, pl.startPositionMs)
                controller.playWhenReady = false
                castSession.setMedia(req)
                castToReceiver = episodeId
                return@LaunchedEffect
            }
            // No URL the receiver can reach. NOT cut here: if it were, the app would be left
            // lying forever —`loaded` is already true so nobody retries, and the local one would
            // keep the PREVIOUS chapter while the screen says this one is playing—.
            // Falls back to normal local playback: the user requested a chapter, we give it to
            // them on the phone, and the Toast already explains that it couldn't be sent to the TV.
            android.util.Log.w("ArkivCast", "casting but there's no URL to send the receiver → plays on the phone instead")
            android.widget.Toast.makeText(
                context,
                "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        when (decision) {
            // Unreachable: cut above, as soon as the decision is computed. The branch exists
            // because the `when` over Decision is exhaustive.
            MediaReusePolicy.Decision.WAIT -> Unit
            // Same episode already playing AND with the same URL: re-hook (reuses the buffer).
            MediaReusePolicy.Decision.REUSE_CURRENT -> {
                android.util.Log.w("ArkivPlay", "branch=REUSE_CURRENT → controller.play() (does NOT reload media)")
                currentIndex = controller.currentMediaItemIndex
                // The controller can arrive here primed-but-not-prepared: the CAST branch above
                // loads it with setMediaItems() without prepare(), and if the cast disconnected
                // WHILE OUTSIDE the player (cast button / notification) the screen was destroyed in
                // between — LaunchedEffect(casting)'s prepare() never got to run because wasCasting
                // is a `remember` of that composition, not of the player. play() on an IDLE player
                // starts nothing; preparing it first is harmless if it was already prepared.
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                    markLocalLoad(prefersSoftware = false)
                }
                controller.play()
            }
            // Same section already loaded (same URLs), another episode: jump within the playlist.
            MediaReusePolicy.Decision.SKIP_IN_PLAYLIST -> {
                // `actualMediaId` is guaranteed different from `episodeId` here -- decide() would
                // have returned REUSAR_ACTUAL/RECARGAR otherwise -- so this always resolves to
                // Source.PLAYLIST. Routed through the same policy as RECARGAR below anyway: it's
                // the single place that knows the rule, and the guarantee is decide()'s, not this
                // call site's to re-derive.
                val resume = ReloadPositionPolicy.resumePosition(
                    episodeId = episodeId,
                    currentMediaId = actualMediaId,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    playlistPositionMs = pl.startPositionMs,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "branch=SKIP_IN_PLAYLIST → seekTo within the playlist (does NOT reload media). " +
                        "resume pos=${resume.positionMs}ms source=${resume.source} (playlist had ${pl.startPositionMs}ms)",
                )
                val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
                currentIndex = idx
                controller.seekTo(idx, resume.positionMs)
                // Same case as REUSE_CURRENT above: can arrive primed-but-not-prepared.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.playWhenReady = true
                markLocalLoad(prefersSoftware = false)
            }
            // New content, or the URL changed under the same episodeId (before: torrent re-served
            // on another port, source now removed; today: magis token renewed on re-resolution):
            // load the playlist with the fresh URL.
            MediaReusePolicy.Decision.RELOAD -> {
                // The stale-playlist race (see ReloadPositionPolicy's KDoc): the screen can reach
                // RECARGAR on a re-mount whose `playlist` StateFlow value is minutes old while the
                // controller kept playing THIS episode in the background the whole time. Resuming
                // at the playlist's `startPositionMs` in that case would throw away real progress
                // -- ask the controller's own clock instead of trusting the playlist blindly.
                val resume = ReloadPositionPolicy.resumePosition(
                    episodeId = episodeId,
                    currentMediaId = actualMediaId,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    playlistPositionMs = pl.startPositionMs,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "branch=new → setMediaItems + prepare (opens the local player with the fresh URL). " +
                        "resume pos=${resume.positionMs}ms source=${resume.source} (playlist had ${pl.startPositionMs}ms)",
                )
                currentIndex = pl.startIndex
                controller.setMediaItems(localMediaItems(pl.items), pl.startIndex, resume.positionMs)
                controller.playWhenReady = true
                controller.prepare()
                markLocalLoad(prefersSoftware = pl.items.getOrNull(pl.startIndex)?.prefersSoftware == true)
            }
        }
        NowPlaying.episodeId =
            controller.currentMediaItem?.mediaId ?: pl.items.getOrNull(currentIndex)?.episodeId
    }

    // Chapter whose end was ALREADY handled, so two advances don't chain off the same end: the
    // player can repeat its STATE_ENDED and, while casting, the CastPlayer also emits its own.
    var endHandled by remember { mutableStateOf<String?>(null) }

    /**
     * End of the chapter → move on to the next one.
     *
     * Until now this didn't exist and only archive.org (source removed in this branch's pruning)
     * advanced, as a side effect: it was the only multi-item source (it loaded the whole section
     * as a playlist), so media3 did the advancing on its own, internally. The others —magis, Ditu,
     * local, and the legacy web/torrent— publish ONE item: when it ended, the player was left in
     * STATE_ENDED with the bar full and nothing else happened.
     *
     * Goes through the same path as the transport's "Next episode" button ([onNextEpisode]):
     * navigating to the new chapter's route, which is what restarts resolving the source.
     */
    fun onEndOfChapter() {
        android.util.Log.w("ArkivPlay", "onEndOfChapter · pos=${mirror.positionMs} dur=${mirror.durationMs} live=$isLive endHandled=$endHandled ep=$episodeId")
        // A live stream doesn't end: its end is the stream cutting out, and there's no valid "next
        // chapter" there (the only "next" live mode has is zapping). Reopening it isn't
        // hooked here: live channels play on LiveExoPlayer, whose error goes to
        // `vm.reopenLiveAfterCut` (see `onLiveExoError`).
        if (isLive) return
        val current = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            ?: episodeId
        if (endHandled == current) return
        // A cut stream announces itself the same way a finished chapter does: see AutoAdvance.
        if (!AutoAdvance.isEndOfChapter(mirror.positionMs, mirror.durationMs)) {
            android.util.Log.w("ArkivPlay", "end at pos=${mirror.positionMs} of ${mirror.durationMs} → not actually the end, not advancing")
            return
        }
        endHandled = current
        val next = header.next
        android.util.Log.w("ArkivPlay", "end of $current → next=$next")
        if (next != null) onNextEpisode(next)
    }

    /**
     * Whether it makes sense to offer "fix this chapter's times".
     *
     * The answer to the gap the feature left: the old editor is for phone, sits behind a flag and
     * edits the SERIES, so on the Fire TV a bad automatic time could NOT be fixed on the device.
     * And the automatic source genuinely gets it wrong (for one chapter it returned the credits
     * labeled as the opening), with no reliable way to detect that from here.
     *
     * Requires an identifiable chapter and a known duration: without a duration, the position
     * that would get marked still means nothing.
     */
    val hasMarkersToFix = !isLive && d != null && currentEpisode.isNotBlank() && mirror.durationMs > 0

    /**
     * Saves the current position as the intro's end / the outro's start **for this chapter**.
     *
     * Marks by position and not with a slider on purpose: on the TV the progress bar is already
     * D-pad (left/right seek), so "put the chapter where the opening ends and confirm" happens
     * with the same controls as always and no new panel to navigate.
     */
    fun markTime(mode: MarkMode) {
        val position = mirror.positionMs
        markers.closeChapterMenu()
        if (mode == MarkMode.INTRO) vm.setOpeningEnd(position, currentEpisode)
        else vm.setEndingStart(position, currentEpisode)
        val which = if (mode == MarkMode.INTRO) "Intro" else "Outro"
        android.widget.Toast.makeText(
            context,
            "$which de este capítulo guardado en ${formatDuration(position)}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    fun removeChapterMarkers() {
        markers.closeChapterMenu()
        vm.clearMarkers(currentEpisode)
        android.widget.Toast.makeText(
            context,
            "Este capítulo queda sin intro ni outro",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    // Transport's index/buffering/state. Follows the active player: on connecting or disconnecting
    // the cast, the effect relaunches itself and the listener re-hooks to the right one.
    val isMagis = magisItem != null   // MagisExoPlayer handles its own errors.
    val isLiveExo = liveItem != null  // LiveExoPlayer maneja sus propios errores (→ reopenLiveAfterCut).
    val isDitu = dituPlay != null     // DituExoPlayer maneja sus propios errores (→ onDituExoError).
    val isExo = isMagis || isLiveExo || isDitu     // Any in-screen ExoPlayer (vs the local player behind `controller`).
    // The local player's picture, tracks and cues, from the controller. Apart from the transport
    // listener below on purpose: that one follows `activePlayer` (the Chromecast while casting), and
    // these belong to the local player whatever is active.
    DisposableEffect(controller) {
        controller.videoSize.let { localVideo.onVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        tracksState.onLocalTracksChanged(controller.currentTracks)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val sinceLoad = localVideo.msSinceLoad(android.os.SystemClock.elapsedRealtime())
                android.util.Log.i("ArkivPlay", "local first frame · ${sinceLoad}ms after load · screen #$screenId")
                localVideo.onFirstFrame()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                localVideo.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksState.onLocalTracksChanged(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                localSubtitles?.setCues(stackOverlappingCues(cueGroup.cues))
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // Telemetry for the in-screen players (Magis VOD, Ditu, live): playing audio but never painting
    // a frame -- "suena pero no se ve". Restarts per item, so it reports once each. Nothing crashes
    // in this case, so this Crash.report is the only signal; it carries the codec/resolution so we
    // can see WHICH content fails. Gated on playWhenReady (a silent decoder can hold BUFFERING with
    // audio running or a frozen clock -- either way the user wanted to play and sees no picture).
    LaunchedEffect(isExo, magisItem, dituPlay, liveItem, casting) {
        if (!isExo || casting) return@LaunchedEffect
        kotlinx.coroutines.delay(com.arkiv.player.playback.DecoderWatchdog.NO_VIDEO_REPORT_MS)
        val p = activePlayer
        val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!exoRenderedSomething && p.playWhenReady && p.playerError == null && hasVideo) {
            val f = selectedVideoFormat(p.currentTracks)
            val audio = p.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
            com.arkiv.player.crash.Crash.report(
                com.arkiv.player.playback.NoVideoFrame(
                    "in-screen audio-only after ${com.arkiv.player.playback.DecoderWatchdog.NO_VIDEO_REPORT_MS}ms · " +
                        "codec=${f?.sampleMimeType} ${f?.width}x${f?.height} audioTracks=$audio " +
                        "src=${if (isMagis) "magis" else if (isDitu) "ditu" else "live"} state=${p.playbackState}",
                ),
                "video-no-frame",
            )
        }
    }

    /**
     * Local counterpart of `exoRenderedSomething`: the first-frame spinner rule, fed by [localVideo].
     *
     * `requestedMs`/`positionMs` keep [FirstFrameWait]'s resume-landing branch wired: this may
     * well be a no-op today, because ExoPlayer's `setMediaItems(…, startPositionMs)` opens straight
     * at the requested position instead of opening at 0 and seeking there the way libVLC did (an
     * upcoming device test will settle that) -- but the rule is cheap insurance meanwhile.
     */
    fun localWaitsForFirstFrame(): Boolean {
        val tracks = controller.currentTracks
        return FirstFrameWait.shouldWait(
            loadedMsAgo = localVideo.msSinceLoad(android.os.SystemClock.elapsedRealtime()),
            hadFrame = localVideo.renderedFirstFrame,
            videoTracks = tracks.groups.count { it.type == C.TRACK_TYPE_VIDEO },
            audioTracks = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
            requestedMs = playlistRef.value?.startPositionMs ?: 0L,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
        )
    }

    /**
     * Decoder watchdog for downloaded files (see [DecoderWatchdog]): a load that never painted gets
     * ONE reload preferring a software decoder, at the same position. The preference travels in the
     * item's `preferSoftware` extra, which the service player's codec selector honours; the
     * `software-first decoders for …` line it logs is the proof the rescue actually took effect.
     */
    fun watchLocalDecoder() {
        val now = android.os.SystemClock.elapsedRealtime()
        val waitMs = localVideo.msWithSurface(now)
        val videoTracks = controller.currentTracks.groups.count { it.type == C.TRACK_TYPE_VIDEO }

        // Telemetry (once per load): the player has had a surface for a long time, wants to play,
        // isn't in error, yet never painted a frame -- even the software reload above didn't help.
        // The user is stuck with audio and a black screen ("suena pero no se ve"). Nothing crashes,
        // so this is the only signal; carry the codec/resolution so we can see WHICH content fails.
        if (!localVideo.renderedFirstFrame && !localVideo.noVideoReported &&
            controller.playWhenReady && localVideo.hasSurface && controller.playerError == null &&
            waitMs >= DecoderWatchdog.NO_VIDEO_REPORT_MS
        ) {
            localVideo.markNoVideoReported()
            val f = selectedVideoFormat(controller.currentTracks)
            val audioTracks = controller.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
            com.arkiv.player.crash.Crash.report(
                com.arkiv.player.playback.NoVideoFrame(
                    "audio-only after ${waitMs}ms · codec=${f?.sampleMimeType} ${f?.width}x${f?.height} " +
                        "videoTracks=$videoTracks audioTracks=$audioTracks software=${localVideo.loadPrefersSoftware} " +
                        "state=${controller.playbackState}",
                ),
                "video-no-frame",
            )
        }

        val reload = DecoderWatchdog.shouldReloadInSoftware(
            waitingMs = waitMs,
            renderedFirstFrame = localVideo.renderedFirstFrame,
            videoTracks = videoTracks,
            wantsToPlay = controller.playWhenReady,
            hasSurface = localVideo.hasSurface,
            // A file that fails to open also sits with playWhenReady and no frame: that is the error
            // overlay's business, and a software reload would only reload the failure.
            hasError = controller.playerError != null,
            alreadySoftware = localVideo.loadPrefersSoftware,
        )
        if (!reload) return
        val pl = playlistRef.value ?: return
        val pos = controller.currentPosition.coerceAtLeast(0L)
        android.util.Log.w(
            "ArkivPlay",
            "decoder watchdog: no frame ${waitMs}ms with a surface (videoTracks=$videoTracks " +
                "state=${controller.playbackState}) → reloading ${pl.items.getOrNull(currentIndex)?.episodeId} " +
                "in software at ${pos}ms",
        )
        // stop() BEFORE setMediaItems(), or the reload changes nothing -- see reloadInSoftware's KDoc.
        reloadInSoftware(
            controller.asSoftwareReloadPlayer(),
            localMediaItems(pl.items.map { it.copy(prefersSoftware = true) }),
            currentIndex,
            pos,
        )
        markLocalLoad(prefersSoftware = true)
    }

    DisposableEffect(activePlayer) {
        // Snapshot of the ExoPlayer state at the moment the listener is mounted.
        // If isExo=true when the controller takes over, the local player's STATE_ENDED must not
        // fire onEndOfChapter (the ExoPlayer handles its own end).
        val exoActiveOnMount = isExo
        android.util.Log.w("ArkivPlay", "DisposableEffect mounted · activePlayer=${activePlayer::class.simpleName} isExo=$exoActiveOnMount ep=$episodeId")
        mirror.syncTransport(
            buffering = activePlayer.playbackState == Player.STATE_BUFFERING,
            playing = activePlayer.isPlaying,
            wantsToPlay = activePlayer.playWhenReady,
        )
        if (activePlayer.playbackState == Player.STATE_READY && positionBelongsToThisScreen()) {
            mirror.readClock(contentPositionMs(), contentDurationMs())
        }
        currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The IDENTITY of what's playing is always decided by the local playlist: the
                // CastPlayer has a single item loaded and its index would always be 0.
                currentIndex = controller.currentMediaItemIndex
                val epId = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
                NowPlaying.episodeId = epId
            }

            override fun onPlaybackStateChanged(state: Int) {
                mirror.updateBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) {
                    android.util.Log.w("ArkivPlay", "STATE_ENDED · activePlayer=${activePlayer::class.simpleName} exoActiveOnMount=$exoActiveOnMount pos=${mirror.positionMs} dur=${mirror.durationMs} ep=$episodeId")
                    // Don't fire auto-advance if an ExoPlayer was active when this listener was
                    // mounted: the STATE_ENDED belongs to the local player that had no media, not to a real end.
                    if (!exoActiveOnMount) onEndOfChapter()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                mirror.updatePlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                mirror.updateWantsToPlay(playWhenReady)
            }

            // Without this, a playback failure went NOWHERE: the local player publishes it as a
            // PlaybackException, but the screen only paints `vm.error` -- resolution errors -- so
            // the movie wouldn't start and no message would appear. Measured on 2026-08-10 on the
            // Fire TV: `EncounteredError` in the log and `error=false` in the UI.
            // The ViewModel decides what to do with this: some failures repair themselves (the
            // historical example was the 404 of a file renamed on archive.org, whose
            // self-repair path was removed along with that source) and others can only be counted.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isExo) return  // MagisExoPlayer / LiveExoPlayer / DituExoPlayer already called their onError
                val id = playlistRef.value?.items
                    ?.getOrNull(controller.currentMediaItemIndex)?.episodeId ?: episodeId
                android.util.Log.w("ArkivPlay", "onPlayerError episodeId=$id → ${error.message}")
                com.arkiv.player.crash.Crash.report(error, "local-playback-${androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode)}")
                vm.onPlaybackFailed(id)
            }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    // Polling: position/duration (0.5 s) and persisted progress (5 s).
    // Key = activePlayer: on connecting/disconnecting the cast, whichever one is playing needs to be polled again.
    LaunchedEffect(activePlayer) {
        var tick = 0
        while (true) {
            delay(500)
            // Fraction buffered ahead for the bar: % of the file cached by the proxy. Doesn't
            // apply while casting: what buffers is the receiver, not us — showing the local
            // buffer would be a bar that lies.
            mirror.readBuffer(
                when {
                    casting -> 0f
                    else -> {
                        val url = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.mediaUrl
                        if (url != null) graph.archiveCacheProxy.bufferedFraction(url) else 0f
                    }
                },
            )
            val ready = activePlayer.playbackState == Player.STATE_READY && positionBelongsToThisScreen()
            if (ready) {
                mirror.readClock(contentPositionMs(), contentDurationMs())
                // The reopen budget is replenished by POSITION, not by `mirror.playing`.
                // Measured on the Fire TV on 2026-08-14: `mirror.playing` used to turn true as soon
                // as VLC opened, before the first frame, so a channel that reopened and died at pos=0ms
                // would still replenish all three reopens -- the cap never ran out and the on-screen
                // warning could never appear. Replenishing only once it actually played for a while
                // is what distinguishes "it recovered" from "it reopened and died again".
                if (isMagisLive) vm.liveIsPlaying(mirror.positionMs)
            }
            tracksState.syncSubsOn()
            // "Starts black with sound": while the player already lets the audio through but
            // hasn't given the first frame yet, `playbackState` is NOT BUFFERING and the screen
            // was left with no spinner and no picture. Doesn't apply while casting: the TV puts up
            // the picture, not us.
            noFirstFrame = !casting && if (isExo) !exoRenderedSomething else localWaitsForFirstFrame()
            if (!isExo && !casting) watchLocalDecoder()
            // If the video is playing, a previous playback failure no longer describes anything
            // (and on top of that it would be covering these very controls). No-op except right
            // after one.
            if (ready && activePlayer.isPlaying) vm.onPlaybackHealthy()
            tick++
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            // mediaId is read TOGETHER with position and duration: it says who those numbers
            // belong to. While casting, the local index already points at the new chapter as soon
            // as setMediaItems() is called while the receiver is still on the previous one
            // (CastPlayer.setMediaItemsInternal only does queueLoad, leaves no seek pending: it
            // keeps reporting the old item, ready and playing). Without this check, tapping "next
            // episode" near the end of chapter N marked N+1 as WATCHED before it even started.
            val mediaId = activePlayer.currentMediaItem?.mediaId
            // ExoPlayer (Magis): local playlist empty; the episode comes straight from the item.
            // For the other sources it's identified by the LOCAL playlist, not the active player.
            val epId = when {
                isMagis -> magisItem?.episodeId
                isDitu -> dituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            }
            // !isLive (Task 14): live has no "where you were" to save -- no "continue watching",
            // no progress bar to resume. Polling/saving position on a live stream was exactly
            // what broke Magis VOD (see LiveController's KDoc).
            // ExoPlayer doesn't expose mediaId matching the episodeId → the comparison is skipped for isExo.
            if (!isLive && tick % 10 == 0 && epId != null &&
                (isExo || mediaId == epId) && ready && activePlayer.isPlaying &&
                dur > 0 && pos in 0 until dur
            ) {
                vm.saveProgress(epId, pos, dur)
                // Every 600 ticks = 5 min. Magis paints on a TextureView and gets captured; Caracol
                // paints on a SurfaceView, so for it `videoTextureView()` returns null and
                // `FrameCapturer.capturar` does nothing.
                // !casting: while casting, `pos` is the REMOTE receiver's position, but the
                // TextureView is still the LOCAL one, which at that point isn't painting what's on
                // the TV. Capturing it would save an image that doesn't match that position (and
                // it would repeat on every trigger for as long as the cast lasts).
                if (tick % 600 == 0 && !casting) vm.captureFrame(epId, pos, videoTextureView())
            }
            // Heartbeat while casting: says whether the receiver is REALLY advancing. A position
            // stuck with state=ready means it accepted the media but isn't decoding it.
            if (casting && tick % 6 == 0) {
                android.util.Log.i(
                    "ArkivCast",
                    "heartbeat · pos=${pos}ms dur=${dur}ms state=${activePlayer.playbackState} " +
                        "playing=${activePlayer.isPlaying} item=${activePlayer.currentMediaItem?.mediaId ?: "NONE"}",
                )
            }
        }
    }

    /**
     * Captures the frame on PAUSING while staying in the player. Exiting has its own capture (in
     * the onDispose further below) and there's another periodic one every 5 min; this is the
     * "paused to go do something" one, exactly when the thumbnail has to stay on the last thing seen.
     *
     * It hangs off `mirror.wantsToPlay` (playWhenReady) and not `mirror.playing` on purpose: that one
     * also drops on every rebuffer (from Magis's CDN, or any network hiccup), so it would capture
     * —half a million pixels,
     * compress and write to disk— on every network stutter. playWhenReady only changes when someone
     * genuinely pauses (the button, OK on the bar, the media session, losing audio focus).
     *
     * With the key on the state itself it runs ONCE per transition: while it stays paused it
     * doesn't relaunch, and resuming playback doesn't capture either (it exits through the `return` above).
     *
     * Same guards as the other two triggers: `mediaId == epId` (so the frame doesn't get saved
     * under the wrong chapter's id on jumping episodes), `dur > 0`, `pos in 0 until dur`, and
     * `!casting` (while casting the position is the remote receiver's and the local TextureView
     * isn't painting that).
     */
    LaunchedEffect(mirror.wantsToPlay) {
        if (mirror.wantsToPlay || casting) return@LaunchedEffect
        val pos = activePlayer.currentPosition
        val dur = activePlayer.duration
        val mediaId = activePlayer.currentMediaItem?.mediaId
        val epId = when {
            isMagis -> magisItem?.episodeId
            isDitu -> dituPlay?.episodeId
            else -> playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
        }
        if (epId != null && (isExo || mediaId == epId) && dur > 0 && pos in 0 until dur) {
            vm.captureFrame(epId, pos, videoTextureView())
        }
    }

    // Auto-hide the controls while playing. The timer resets with ANY key
    AutoHideEffect(
        state = controls,
        playing = mirror.playing,
        marking = markers.marking,
        carouselRevealed = chaptersState.revealed,
    )

    // This BackHandler is added BEFORE the controls' one (further below), and
    // `OnBackPressedDispatcher` gives priority to the LAST callback added: with the overlay
    // visible AND the fun-fact panel open, BACK closes the controls first, not the panel. Only
    // once the controls are already hidden does a second BACK close the panel. Still better than
    // nothing (without this handler, BACK with the panel open and the controls hidden would exit
    // the video right away) and this batch doesn't change it.
    BackHandler(enabled = triviaState.panelOpen) { triviaState.closePanel() }

    // BACK with the overlay on screen CLOSES it instead of leaving the video; with the overlay
    // already hidden, this handler stays disabled and BACK falls through to navigation (= exit),
    // which is the usual behavior. Works the same for the TV remote, the system button, and the
    // phone's back gesture.
    // The condition mirrors the overlay's further below (`AnimatedVisibility(visible = ...)`): if
    // it only checked controls.visible, in marking mode or with an error on screen the variable
    // can stay true with nothing visible, and BACK would be dead (neither closes nor exits). Keep
    // both the same if one gets touched.
    BackHandler(enabled = !isLive && controls.visible && loadError == null && dlnaState.active == null && !markers.marking) {
        controls.hide()
    }


    // TV: on the overlay showing, move Android's focus from the video (which until now
    // intercepted EVERY key with fixed actions) to Compose's controls, so the D-pad navigates the
    // buttons/bar like a real player (Netflix/Prime) instead of fixed per-key mappings. On hiding,
    // focus goes back to the video for "any key = show".
    LaunchedEffect(controls.visible, isTv) {
        if (!isTv) return@LaunchedEffect
        if (controls.visible) {
            runCatching { focusPoints.bar.requestFocus() }
                .onFailure { runCatching { focusPoints.playPause.requestFocus() } }
        } else {
            // On the overlay hiding the carousel stops existing: if chaptersState.revealed stayed
            // true, on reappearing it would show already open but with focus on the play button.
            chaptersState.hide()
            runCatching { videoView?.requestFocus() }
        }
    }

    // TV: on the audio/subtitles dialog closing, focus has to be relocated by hand. It used to go
    // to videoView, but with the overlay still visible that's a dead end —its listener drops keys
    // while controls.visible is true— and the D-pad stopped responding. Goes back to the button
    // that opened the dialog; the video only makes sense once the overlay has already hidden.
    // Goes in an effect and not in onDismiss: covers however the picker gets closed (dismiss or
    // the Cerrar button), without depending on which way it exited.
    var subPickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tracksState.pickerOpen, isTv) {
        if (!isTv) return@LaunchedEffect
        if (tracksState.pickerOpen) {
            subPickerWasOpen = true
            return@LaunchedEffect
        }
        // Only on the open→closed transition: without this guard the effect would run on entering
        // the player and steal the progress bar's initial focus.
        if (!subPickerWasOpen) return@LaunchedEffect
        subPickerWasOpen = false
        var landed = false
        if (controls.visible) {
            repeat(12) {
                if (landed) return@repeat
                landed = runCatching { focusPoints.subtitles.requestFocus() }.isSuccess
                if (!landed) delay(32)
            }
        }
        if (!landed) runCatching { videoView?.requestFocus() }
    }

    // What the listener used to do with the LOCAL player. On starting to cast, it pauses; on
    // ending, it jumps ahead to where the receiver got to before resuming — otherwise the polling
    // persists the old position over the good one within ≤5s.
    var wasCasting by remember { mutableStateOf(false) }
    // `magisItem?.episodeId` in the key, not just `casting`: opening ANOTHER Magis title while the
    // Chromecast is already connected has to push the new one at the receiver. For playlist
    // sources that job belongs to LaunchedEffect(playlist), which Magis never reaches.
    LaunchedEffect(casting, magisItem?.episodeId) {
        if (casting) {
            // Which guard, if any, stops the send. Kept past the Magis fix: every branch below is
            // conditional, and a cast that silently does nothing is the failure mode of this whole
            // screen -- this line is what tells "no session" from "no item" from "already sent".
            android.util.Log.w(
                "ArkivCast",
                "casting=true · pl=${playlistRef.value != null} loaded=$loaded casteado=$castToReceiver " +
                    "castSession=${castSession != null} magis=${magisItem?.episodeId} " +
                    "live=${liveItem?.episodeId} ditu=${dituPlay?.episodeId}",
            )
            // Connecting the Chromecast to the chapter ALREADY playing on the phone is the action
            // the whole feature starts from, and this effect is the only one that sees it:
            // LaunchedEffect(playlist) isn't pinned to `casting` and on top of that cuts short on
            // the `loaded` guard. Without this, tapping the cast button paused the phone, showed
            // the banner… and left the TV on its idle screen forever.
            // The position comes from the LOCAL player, which is where the user is standing — not
            // from pl.startPositionMs, which is where the chapter started half an hour ago.
            val pl = playlistRef.value
            val idx = pl?.items?.indexOfFirst { it.episodeId == episodeId }?.coerceAtLeast(0)
            val epId = idx?.let { pl.items.getOrNull(it)?.episodeId }
            if (pl != null && idx != null && epId != null && loaded && castToReceiver != epId && castSession != null) {
                val from = runCatching { controller.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                val req = castRequestFor(pl, idx, from)
                if (req == null) {
                    android.util.Log.w("ArkivCast", "session open but there's no URL to send the receiver")
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.util.Log.w("ArkivCast", "session open → sending the current episode to the receiver from ${from}ms")
                    castSession.setMedia(req)
                    castToReceiver = epId
                }
            }

            // MAGIS. Its item never enters `playlist` -- the ViewModel publishes it in `magisItem`
            // and MagisExoPlayer plays it -- so the block above, which reads `playlistRef`, never
            // ran for it: connecting the Chromecast on a Magis title paused the phone, showed the
            // card, and left the TV on its idle screen forever, with no error anywhere. Measured
            // 2026-09-12: `pl=false loaded=false magis=magis:7B66…`, and `castRequestFor` was
            // never even entered.
            //
            // Same synthetic one-item PlaylistData the live channel above already uses, and for
            // the same reason: `castRequestFor` reads nothing from PlaylistData but `items` and
            // the index, so reusing it beats a second copy of the lanUrl/mime/audio logic that
            // could drift from it.
            val mg = magisItem
            // A streaming MPEG-TS is PREPARED before it is cast, not while. Casting the segments
            // and remuxing at the same time means downloading the same title twice at once through
            // one proxy, and measured on 2026-09-12 they starved each other: three live
            // connections to the origin, a broken pipe, the export stalled and the receiver frozen
            // at `state=2`. Reading it once, then casting the result, is the whole point of
            // waiting. The effect below does the preparing; this one stays quiet until it lands.
            val waitingForRemux = mg != null &&
                magisIsTs(mg) &&
                mg.castUrl.orEmpty() !in remuxFailed &&
                graph.tsRemuxer.alreadyDone(mg.castUrl.orEmpty()) == null
            if (waitingForRemux) {
                android.util.Log.w("ArkivCast", "magis ts: preparing the mp4 before casting, nothing sent yet")
            }
            if (mg != null && !waitingForRemux && castSession != null && castToReceiver != mg.episodeId) {
                // From the LOCAL ExoPlayer, which is where the person actually is. `activePlayer()`
                // is no use here: `casting` is already true, so it answers the receiver.
                val localPos = runCatching { magisPlayer?.currentPosition }.getOrNull()
                val from = (localPos ?: mg.startPositionMs).coerceAtLeast(0L)
                android.util.Log.w(
                    "ArkivCast",
                    "magis → cast · ep=${mg.episodeId} from=${from}ms " +
                        "(${if (localPos != null) "live position of the local player" else "no local player yet, using the saved startPosition"})",
                )
                val req = castRequestFor(PlaylistData(listOf(mg), 0, from, requested = mg.episodeId), 0, from)
                if (req == null) {
                    android.util.Log.w("ArkivCast", "magis: no URL the receiver can reach (no LAN ip, or the proxy isn't up)")
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.util.Log.w("ArkivCast", "magis → receiver · uri=${req.uri.take(90)} mime=${req.mimeType} start=${req.startPositionMs}ms")
                    castSession.setMedia(req)
                    castToReceiver = mg.episodeId
                    // Same reason as the live effect: the notification and the remote read this,
                    // and it must not keep pointing at whatever played before.
                    NowPlaying.episodeId = mg.episodeId
                }
            }

            runCatching { controller.pause() }
            // `controller` is the service player; Magis plays on its own ExoPlayer, so pausing the
            // former did nothing for it. That gap was harmless while Magis could not cast at all
            // (it was noted as accepted in the resume branch below) -- now that it does cast,
            // leaving it out means the phone and the TV play the same title at once.
            if (magisPlayer != null) {
                android.util.Log.i("ArkivCast", "pausing the local Magis player so it doesn't play over the cast")
                runCatching { magisPlayer?.pause() }
            }
        } else if (wasCasting) {
            castToReceiver = null
            castAsRemux = null
            // Stop converting what nobody is going to watch. The remux covers the whole title, so
            // a cast that ends after ten minutes would otherwise keep pulling the other hour and
            // fifty down the person's connection.
            magisItem?.castUrl?.takeIf { it.isNotBlank() }?.let { graph.tsRemuxer.stop(it) }
            // If the session ended because the user pressed "stop" (the bar's button), it must NOT
            // resume here: they asked for silence, and the local one was already left paused since
            // casting started (branch above) — resuming it would be exactly the opposite of what
            // that button asked for. Consumed only once: the next disconnect (the cast button's,
            // not the stop one's) resumes normally again.
            if (graph.castSession?.consumeIntentionalStop() != true) {
                if (isLive) {
                    // Live (Task 18): no "where you were" to resume -- it would be the position
                    // the receiver reports over a live HLS, which means nothing as an offset
                    // within the local proxy (see castRequestFor/CastRequestBuilder's KDoc).
                    // Live via ExoPlayer (Task 1, light-magis pruning): `livePlayer` was never
                    // paused on starting to cast (same gap already accepted for `magisPlayer`, see
                    // the `controller.pause()` in the `if (casting)` branch above), so there's
                    // nothing to resume here -- it keeps playing locally just as it did during the
                    // cast. This `controller.prepare()/play()` is on the local player, which isn't
                    // the one playing live.
                    // `isLive` with no `liveItem` is a Caracol channel, which plays on DituExoPlayer:
                    // for it this branch ends in the same controller `prepare()/play()` as VOD's
                    // below, which with no playlist (`loadDitu` leaves it null) also resumes nothing.
                    runCatching { controller.prepare() }
                    runCatching { controller.play() }
                    wasCasting = casting
                    return@LaunchedEffect
                }
                // MAGIS: resume on ITS player, not on the service one. Same question as VOD below
                // -- "did the receiver report a position for THIS episode?" -- but a seek is enough
                // here: MagisExoPlayer was only paused (see the branch above), never unloaded, so
                // there is nothing to reload.
                val mg = magisItem
                if (mg != null) {
                    val castMediaId = runCatching { castPlayer?.currentMediaItem?.mediaId }.getOrNull()
                    val castPos = if (castMediaId == mg.episodeId) {
                        runCatching { CastProgress.contentPosition(castPlayer?.currentPosition ?: 0L) }
                            .getOrDefault(0L)
                    } else {
                        android.util.Log.w(
                            "ArkivCast",
                            "magis resume: receiver position discarded, it's for '$castMediaId' and we're resuming '${mg.episodeId}'",
                        )
                        0L
                    }
                    android.util.Log.w(
                        "ArkivCast",
                        "magis ← cast · resuming locally at ${castPos}ms (player=${if (magisPlayer != null) "ready" else "gone"})",
                    )
                    if (castPos > 0L) runCatching { magisPlayer?.seekTo(castPos) }
                    runCatching { magisPlayer?.play() }
                    wasCasting = casting
                    return@LaunchedEffect
                }
                val pl = playlistRef.value
                val epId = pl?.items?.getOrNull(currentIndex)?.episodeId
                // The receiver's position only counts if it's for THIS episode. The CastPlayer
                // never stops (`stop()` is never called and `setRemoteMediaClient(null)` resets
                // nothing in media3), so `currentPosition` keeps returning the last reported
                // position forever: casting A up to 45:00, disconnecting outside the player,
                // opening B, and connecting/disconnecting would leave B jumping to 45:00 — and
                // polling would persist it.
                val castMediaId = runCatching { castPlayer?.currentMediaItem?.mediaId }.getOrNull()
                val castPos = if (epId != null && castMediaId == epId) {
                    // With no transcoder the receiver counts from the same point as the file:
                    // only a TIME_UNSET needs clamping to "don't know" (0).
                    runCatching {
                        CastProgress.contentPosition(castPlayer?.currentPosition ?: 0L)
                    }.getOrDefault(0L)
                } else {
                    android.util.Log.w("ArkivCast", "receiver position discarded: it's for '$castMediaId', we're resuming '$epId'")
                    0L
                }
                // RELOAD, don't seek: `setMediaItems(…, castPos)` puts the local player at the
                // receiver's position whether or not it was ever prepared (the CAST branch loads it
                // without preparing). libVLC needed it because it baked the old start time into its
                // media (the local resumed where casting STARTED); with ExoPlayer it stays correct.
                if (castPos > 0L && pl != null) {
                    runCatching { controller.setMediaItems(localMediaItems(pl.items), currentIndex, castPos) }
                }
                // The local one could be left primed WITHOUT preparing (CAST branch above): only
                // here, on genuinely resuming, does it get prepared. If it was already prepared (it was playing locally before
                // casting) prepare() is a no-op.
                val reloaded = castPos > 0L && pl != null
                val wasUnprepared = controller.playbackState == Player.STATE_IDLE
                runCatching { controller.prepare() }
                runCatching { controller.play() }
                // A new first frame comes only if the media was reloaded or prepared just now;
                // otherwise the first-frame spinner and the decoder watchdog would wait for nothing.
                if (reloaded || wasUnprepared) markLocalLoad(prefersSoftware = false)
            }
        }
        wasCasting = casting
    }

    // On marking (archive): pause and place the slider at the already-saved value (if any).
    // Resuming (play) ONLY applies on LEAVING marking mode — not on the initial composition:
    // otherwise, opening a new web source, this play() would revive the previous video (still
    // loaded in the service) behind the "Resolviendo…" overlay while the new one resolves.
    LaunchedEffect(markers.mode) {
        when (markers.transition()) {
            true -> {
                controller.pause()
                val alreadySaved = when (markers.mode) {
                    MarkMode.INTRO -> d?.openingEndMs
                    MarkMode.OUTRO -> d?.endingStartMs
                    else -> null
                }
                if (alreadySaved != null) {
                    controller.seekTo(alreadySaved)
                    mirror.jumpTo(alreadySaved)
                }
            }
            false -> controller.play()
            null -> Unit
        }
    }

    // Orientation / system bars on the phone.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(isLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val wic = WindowInsetsControllerCompat(window, window.decorView)
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) wic.hide(WindowInsetsCompat.Type.systemBars())
        else wic.show(WindowInsetsCompat.Type.systemBars())
    }

    // The current player, read from long-lived effects. Writing `activePlayer` inside the
    // onDispose below isn't enough: with key `Unit` the remember never gets rebuilt, so the
    // onDispose that runs is the one built in the FIRST composition — when it wasn't casting yet
    // and `activePlayer` was, by value, the local controller. (`casting` does read live from
    // closures because it's a MutableState delegate; `activePlayer` is a captured val.)
    // Putting `activePlayer` as the effect's key doesn't work either: that would destroy and
    // recreate it on every cast connect/disconnect, running its onDispose —and its
    // controller.pause()— mid-session. rememberUpdatedState gives the fresh value without
    // touching the effect's lifecycle.
    val currentPlayer by rememberUpdatedState(activePlayer)
    // Same as currentPlayer: the active ExoPlayer item on exiting / going to the background, so
    // onDispose and the lifecycle observer read the correct episodeId even while the screen is
    // already leaving.
    val currentMagisItem by rememberUpdatedState(magisItem)
    val currentDituPlay by rememberUpdatedState(dituPlay)
    val currentIsLive by rememberUpdatedState(isLive)

    DisposableEffect(Unit) {
        onDispose {
            // Cut local playback BEFORE saving the position: that way the audio goes silent
            // instantly on exit (avoids ~1s of tail). With Home the composable is NOT destroyed,
            // so this doesn't run and the audio keeps playing in the background; back/swipe does
            // destroy it and pauses (as today).
            // The position comes from the ACTIVE player (Chromecast if there's a session); pause(),
            // on the other hand, always goes to the local one: pausing the Chromecast on exiting
            // the screen would cancel the cast.
            // An unconfirmed incremental jump (see `seekBy`) IS the position the user chose: exiting
            // within those 350 ms can't save the previous one. Local only: while casting this
            // target is in CONTENT time and `currentPosition` is the receiver's, which with a
            // window carries a different origin — there the usual behavior is kept.
            val pending = seek.pendingMs?.takeIf { !casting }
            val pos = pending ?: currentPlayer.currentPosition
            val dur = currentPlayer.duration
            // Who those numbers belong to: same problem as polling. Exiting the screen right after
            // jumping chapters wrote the OLD chapter's position (the receiver hadn't switched
            // items yet) under the NEW one's id, and savePlayback recomputes "watched" from that.
            // `pos in 0 until dur` was also missing here.
            val mediaId = currentPlayer.currentMediaItem?.mediaId
            controller.pause()
            val isExoOnDispose = currentMagisItem != null || currentDituPlay != null
            // ExoPlayer: empty local playlist; the episodeId comes straight from the item.
            val epId = when {
                currentMagisItem != null -> currentMagisItem?.episodeId
                currentDituPlay != null -> currentDituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            }
            // !isLive (Task 14): exiting a live channel has no "position" to save.
            if (!isLive && epId != null && (isExoOnDispose || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                // EXIT capture. Same as in polling: with Caracol there's no TextureView and nothing gets captured.
                // !casting: same reason as in the periodic polling — while casting, `pos` is the
                // remote receiver's position, but the local TextureView isn't painting that.
                if (!casting) vm.captureFrame(epId, pos, videoTextureView())
            }
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                // Return brightness to system control (the brightness gesture had pinned it).
                it.window.let { w ->
                    val lp = w.attributes
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    w.attributes = lp
                }
            }
            dlnaState.stopOnExit()
        }
    }

    // Saves progress when the app goes to the background (Home button, notifications, etc.). The
    // onDispose above only runs on DESTROYING the screen (back/swipe); with Home the composable
    // survives, and if the process dies afterward the position would be lost.
    //
    // After saving, pauses: with Home this screen's ExoPlayers (Magis, live, Caracol) kept playing
    // outside. What pauses and how it's decided is [onBackground]; `isTv` is what `ArkivTvRoot`
    // passes, the root `MainActivity` chooses with `DeviceType.isTelevision`. On return, a video
    // stays paused where it was and a live channel goes back to live: playing if it was playing,
    // paused if it was paused ([onReturnToLive]).
    DisposableEffect(lifecycleOwner) {
        // The live stream that stopped on going to the background, and whether it was playing at
        // that moment, to decide on return. Only if it's still the active player on return: if
        // another one got armed meanwhile, that one isn't touched.
        var stoppedLiveStream: Player? = null
        var wasPlayingOnExit = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                val stopped = stoppedLiveStream
                stoppedLiveStream = null
                if (stopped != null && stopped === currentPlayer) {
                    val onReturn = onReturnToLive(wasPlayingOnExit)
                    android.util.Log.w("ArkivPlay", "app back in foreground → the live stream primes at the edge · $onReturn")
                    runCatching {
                        // The stopped one has nothing loaded: without `prepare()` it would stay
                        // still even if the person hit play.
                        stopped.seekToDefaultPosition()
                        stopped.prepare()
                        if (onReturn == OnReturnToLive.RESUME_LIVE) stopped.play()
                    }
                }
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val isExoStop = currentMagisItem != null || currentDituPlay != null
                val epId = when {
                    currentMagisItem != null -> currentMagisItem?.episodeId
                    currentDituPlay != null -> currentDituPlay?.episodeId
                    else -> playlistRef.value?.items?.getOrNull(currentPlayer.currentMediaItemIndex)?.episodeId
                }
                val pos = currentPlayer.currentPosition
                val dur = currentPlayer.duration
                val mediaId = currentPlayer.currentMediaItem?.mediaId
                if (!isLive && epId != null && (isExoStop || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                    vm.saveProgress(epId, pos, dur)
                    if (!casting) vm.captureFrame(epId, pos, videoTextureView())
                }

                val player = currentPlayer
                // `controller` IS the local player: downloaded files play on the ExoPlayer hosted by
                // PlaybackService, and this screen reaches it only through `controller`. So for a
                // local file `player === controller`, `isExoPlayer` is false and the phone gets KEEP_PLAYING:
                // it keeps playing in the background, with the media notification. Never route local
                // files to an in-screen player, or this rule starts pausing them. Pinned by
                // PauseOnExitTest.
                val action = onBackground(
                    isTv = isTv,
                    isExoPlayer = player !== controller,
                    casting = casting,
                    isLive = currentIsLive,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "app to background → $action · tv=$isTv casting=$casting live=$currentIsLive " +
                        "player=${player::class.simpleName}",
                )
                when (action) {
                    OnBackground.KEEP_PLAYING -> Unit
                    OnBackground.PAUSE -> runCatching { player.pause() }
                    OnBackground.STOP_LIVE -> {
                        // Before pausing it: if the person had already paused it, it stays that way on return.
                        wasPlayingOnExit = player.playWhenReady
                        runCatching {
                            player.pause()
                            player.stop()
                        }
                        stoppedLiveStream = player
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Transport through the active player (Chromecast if there's a session, otherwise the local one).
    val seekStepMs = 10_000L

    /**
     * Moves playback to [targetMs] of the CONTENT.
     *
     * While casting, `activePlayer` is already the `CastPlayer`: the seek goes straight to the
     * receiver, with no transcoder in between (removed -- see `castRequestFor`'s KDoc).
     */
    /**
     * Is the thing on the TV a remux being written, and therefore unseekable?
     *
     * It is announced as a live stream -- the only framing that stopped the receiver inventing an
     * end and stalling against it -- and a live stream has no timeline to move along. The seek
     * still had to be BLOCKED rather than simply failing: letting it through sent the receiver a
     * position it could not honour, the bar drew the destination, nothing arrived, and the bar
     * ended up further from the truth than before the attempt. Reported as "the bar goes crazy,
     * and trying to skip forward made it worse".
     */
    fun castIsLive(): Boolean =
        casting && magisItem != null && magisIsTs(magisItem!!) &&
            magisItem!!.castUrl?.let { graph.tsRemuxer.alreadyDone(it) == null } == true

    fun seekTo(targetMs: Long) {
        if (castIsLive()) {
            android.util.Log.w("ArkivCast", "seek ignored: the remux is still being written, so it is cast as live")
            android.widget.Toast.makeText(
                context,
                "Mientras se prepara para la TV no se puede adelantar",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            bump()
            return
        }
        val dur = contentDurationMs()
        val target = targetMs.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        activePlayer.seekTo(target)
        mirror.jumpTo(target)
        bump()
    }

    /**
     * Incremental jump: does NOT touch the player yet, only moves the target and lets
     * [SEEK_INCREMENTAL_DEBOUNCE_MS] confirm just one.
     *
     * Every press used to be a real `seekTo`, i.e. a Range request and its rebuffer: moving two
     * minutes with the TV's D-pad is twelve of those. On Magis each range can take 0.2 to 20 s, so
     * the burst of jumps was racing against itself. Accumulates on the previous target (and not on
     * the player's position) so the count doesn't depend on whether the previous seek already landed.
     *
     * While one is pending, `seek.dragging` turns on, the same thing the slider's drag uses: the
     * bar and the clock are painted with the target, so you can see where you're going even while
     * the video is still on the old frame. Same behavior as Netflix or Prime on TV.
     */
    fun seekBy(deltaMs: Long) {
        seek.jump(deltaMs, contentPositionMs(), contentDurationMs())
        bump()
    }

    // Confirms the burst: every new press changes the key and cancels this `delay`, so the
    // `seekTo` only goes out once, when you stopped moving. On clearing the pending target the
    // effect relaunches with null and returns on the first line.
    LaunchedEffect(seek.pendingMs) {
        val target = seek.pendingMs ?: return@LaunchedEffect
        delay(SEEK_INCREMENTAL_DEBOUNCE_MS)
        seekTo(target)
        seek.confirmed()
    }

    fun togglePlayPause() {
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            // Saves the position on pausing: if the app closes while paused (crash, Fire Stick
            // restarts), the position is saved and doesn't get lost.
            val epId = when {
                isMagis -> magisItem?.episodeId
                isDitu -> dituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            }
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            val mediaId = activePlayer.currentMediaItem?.mediaId
            if (!isLive && epId != null && (isExo || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                if (!casting) vm.captureFrame(epId, pos, videoTextureView())
            }
        } else {
            activePlayer.play()
        }
        // Live doesn't use bump()/controls.visible (that whole overlay is hidden -- see further
        // below, "visible = !isLive && ..."): without this guard, togglePlayPause() (reachable
        // from the D-pad's center on TV) still left controls.visible true, and the BackHandler
        // below (tied to that same variable) ate the first BACK closing an invisible overlay
        // instead of leaving the player.
        if (!isLive) bump()
    }

    // The `setOnKeyListener` further below is built ONCE, inside the `factory` of the AndroidView
    // that creates the local player's TextureView, and that factory never runs again in the screen's lifetime. Without
    // this bridge, the listener's lambda would keep the FIRST composition's `togglePlayPause`/`seekBy`,
    // which read that moment's `activePlayer` (the local `controller`, because
    // `magisPlayer`/`livePlayer`/`dituPlayer` hadn't been published yet) and not the player that's
    // genuinely playing anymore. Same pattern as `currentPlayer` above.
    val currentTogglePlayPause by rememberUpdatedState { togglePlayPause() }
    val currentSeekBy by rememberUpdatedState { deltaMs: Long -> seekBy(deltaMs) }

    val onOpenEpisodesState = rememberUpdatedState(onOpenEpisodes)

    val outerModifier = if (isLandscape) Modifier.fillMaxSize()
    else Modifier.fillMaxSize().systemBarsPadding()

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = outerModifier,
            factory = { ctx ->
                android.view.TextureView(ctx).also { tv ->
                    // Transparent until it paints: the black Box shows through, and the in-screen
                    // players (Magis, live, Caracol) draw on top of it.
                    tv.isOpaque = false
                    val previous = videoView
                    videoView = tv
                    if (previous != null) {
                        android.util.Log.w(
                            "ArkivVout",
                            "FACTORY #$screenId replaces videoView " +
                                "#${Integer.toHexString(System.identityHashCode(previous))} → " +
                                "#${Integer.toHexString(System.identityHashCode(tv))}",
                        )
                    }
                    // Bound on the player itself, not through `controller`: see PlaybackEngine.
                    serviceExo.setVideoTextureView(tv)
                    localVideo.onSurfaceAttached(android.os.SystemClock.elapsedRealtime())
                    android.util.Log.w("ArkivVout", "ATTACH factory#$screenId view=#${Integer.toHexString(System.identityHashCode(tv))}")
                    // The activity handles rotation itself (configChanges), so this view is resized in
                    // place and the aspect transform has to follow its new size.
                    tv.addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (r - l != oldR - oldL || b - t != oldB - oldT) {
                            (v as android.view.TextureView).fitAspect(localVideo.aspect, gestures.zoomForExo)
                        }
                    }
                    if (isTv) {
                        tv.isFocusable = true
                        tv.isFocusableInTouchMode = true
                        tv.setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            if (markers.marking) return@setOnKeyListener false
                            // With the controls overlay visible, Android's focus is already on
                            // Compose's buttons (see LaunchedEffect(controls.visible)) and this
                            // listener shouldn't even receive the event; the fallback exists just
                            // in case focus didn't move in time.
                            if (controls.visible) return@setOnKeyListener false
                            // UP with the controls hidden and facts loaded: instead of opening the
                            // overlay, shows the next fun fact. Goes BEFORE the live block because
                            // there up zaps, and a channel carries no facts anyway.
                            if (!isLive && keyCode == KeyEvent.KEYCODE_DPAD_UP && PlayerTrivia.hasButton(trivia)) {
                                triviaState.showNext(trivia.size)
                                return@setOnKeyListener true
                            }
                            // Live (Task 14): Up/Down zap instead of showing VOD's overlay (which
                            // doesn't exist in live, see `visible = !isLive && ...`), and
                            // Left/Right don't seek (no duration/position in live).
                            // Zapping and the drawer belong to Magis live: on a Caracol channel the
                            // arrows do nothing and the center is still play/pause.
                            if (isLive) {
                                // The drawer claims the left arrow BEFORE anything else. With the
                                // drawer open this listener no longer receives keys (Android's
                                // focus is on Compose's rows), so only the "closed + left" case can
                                // happen here.
                                if (isMagisLive) {
                                    val drawerAction =
                                        DrawerDpad.action(keyCode, liveState.drawerOpen, liveState.drawerFocus)
                                    if (drawerAction == DrawerAction.OPEN) {
                                        liveState.openDrawer()
                                        return@setOnKeyListener true
                                    }
                                }
                                return@setOnKeyListener when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP ->
                                        if (isMagisLive) { vm.zapPrevious(); liveState.showInfo(); true } else false
                                    KeyEvent.KEYCODE_DPAD_DOWN ->
                                        if (isMagisLive) { vm.zapNext(); liveState.showInfo(); true } else false
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                        { currentTogglePlayPause(); true }
                                    else -> false
                                }
                            }
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                                    { currentSeekBy(seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                                    { currentSeekBy(-seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                    { currentTogglePlayPause(); true }
                                // Any other arrow or MENU, with the overlay hidden: just shows it
                                // (same as Netflix/Prime) — the real navigation between buttons is
                                // handled by Compose's focus once visible.
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                                    { bump(); true }
                                else -> false
                            }
                        }
                        tv.post { tv.requestFocus() }
                    }
                }
            },
            // Aspect and zoom through the TextureView transform, same as the in-screen players.
            update = { it.fitAspect(localVideo.aspect, gestures.zoomForExo) },
            // A no-op when the incoming screen already bound its own view: ExoPlayer only clears the
            // view it is using. That's the ordering problem (outgoing release after incoming attach)
            // the libVLC code had to log around.
            onRelease = { tv ->
                android.util.Log.w("ArkivVout", "DETACH onRelease#$screenId")
                serviceExo.clearVideoTextureView(tv)
                localVideo.onSurfaceDetached()
            },
        )

        // Embedded subtitles of a downloaded file (cues from the local player, see the controller
        // listener). Mounted only while the local player is the one playing —the in-screen players
        // draw their own— and confined to the picture: with the video letterboxed, cues belong under
        // the image, not at the bottom of the screen.
        if (!isExo) {
            Box(outerModifier, contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = if (localVideo.aspect > 0f) Modifier.aspectRatio(localVideo.aspect) else Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SubtitleView(ctx).apply { applyArkivSubtitleStyle(subtitleStyle, isTv) }
                            .also { localSubtitles = it }
                    },
                )
            }
            localSubtitles?.let { ls ->
                LaunchedEffect(ls, subtitleStyle, isTv) { ls.applyArkivSubtitleStyle(subtitleStyle, isTv) }
            }
        }

        // Magis: ExoPlayer plays the local proxy's stream (headers already injected), without VLC.
        val mItem = magisItem
        if (mItem != null) {
            MagisExoPlayer(
                mediaUrl = mItem.mediaUrl,
                mirror = mirror,
                startPositionMs = mItem.startPositionMs,
                subtitleConfigs = (webExtras?.subtitles ?: emptyList()).toExoSubtitleConfigs(),
                onPlayerReady = { player ->
                    magisPlayer = player
                    tracksState.setExoPlayer(player)
                    gestures.setExoPlayer(player)
                },
                onTextureViewReady = { tv -> magisTextureView = tv },
                onError = { msg -> vm.onMagisExoError(msg) },
                // Nobody else watches for the end of a Magis episode: the screen's listener stays
                // quiet while an ExoPlayer is active, on the grounds that its STATE_ENDED belongs
                // to a local player holding nothing. True, but it left the end unhandled entirely.
                onChapterEnd = { onEndOfChapter() },
                onTracksChanged = { tracks -> tracksState.updateExoTracks(tracks) },
                onFirstFrame = { got -> exoRenderedSomething = got },
                zoom = gestures.zoomForExo,
                isTv = isTv,
            )
        }

        // Caracol: DASH with Widevine, no local proxy (see DituExoPlayer). Resumes from the same
        // position Magis would use: `safeStartPosition`, computed in PlayerViewModel.loadDitu.
        // Inside `key(dPlay)`: every publication brings a new `generation`, so a reload rebuilds
        // the player even if Caracol returns the same URL.
        val dPlay = dituPlay
        if (dPlay != null) key(dPlay) {
            DituExoPlayer(
                mediaUrl = dPlay.playable.url,
                drmLicenseUrl = dPlay.playable.drmLicenseUrl,
                drmLicenseHeaders = dPlay.playable.drmLicenseHeaders,
                localDownload = dPlay.localDownload,
                store = graph.caracolStore,
                mirror = mirror,
                startPositionMs = dPlay.startPositionMs,
                autoStart = dPlay.autoStart,
                onPlayerReady = { player ->
                    dituPlayer = player
                    tracksState.setExoPlayer(player)
                    gestures.setExoPlayer(player)
                },
                onError = { code, wantedToPlay ->
                    // Where to resume from if the ViewModel requests a new URL. Asked to the
                    // player and not the mirror, which only catches up on the half-second polling.
                    val pos = dituPlayer?.currentPosition?.coerceAtLeast(0L) ?: dPlay.startPositionMs
                    vm.onDituExoError(code, pos, wantedToPlay)
                },
                requestReprepare = { vm.dituCanReprepare() },
                onPosition = { pos, playing -> vm.dituAdvanced(pos, playing) },
                onTracksChanged = { tracks -> tracksState.updateExoTracks(tracks) },
                onFirstFrame = { got -> exoRenderedSomething = got },
                zoom = gestures.zoomForExo,
            )
        }

        // Live channel (Task 1, light-magis pruning): ExoPlayer plays the local proxy's HLS
        // (LiveHlsProxy, headers already injected against the CDN), without VLC -- same pattern as
        // Magis. No subtitles or resume: a live stream has none. The error goes to
        // `reopenLiveAfterCut()` -- see `onLiveExoError`'s KDoc -- instead of a banner, so a
        // passing CDN/proxy hiccup doesn't interrupt playback with a visible error.
        val lItem = liveItem
        if (lItem != null) {
            LiveExoPlayer(
                mediaUrl = lItem.mediaUrl,
                // See `key`'s KDoc in LiveExoPlayer: `mediaUrl` does NOT change between channels
                // (the proxy's URL is fixed), so without this zapping wouldn't recreate the player.
                key = lItem.episodeId to liveGeneration,
                channelCode = lItem.episodeId,
                mirror = mirror,
                onPlayerReady = { player ->
                    livePlayer = player
                    gestures.setExoPlayer(player)
                },
                onError = { msg -> vm.onLiveExoError(msg) },
                onFirstFrame = { got -> exoRenderedSomething = got },
                zoom = gestures.zoomForExo,
            )
        }

        // NIGHT MODE: black veil ON TOP of the video and BELOW the controls, on purpose -- that
        // way the controls stay readable at normal brightness, exactly when they're needed at night.
        // No gesture modifiers: without them it's not a hit-testing target, so the gesture layer
        // below (tap/seek/volume/brightness on the phone) keeps receiving every touch.
        if (clampedDimLevel > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = clampedDimLevel.toFloat() / DIM_MAX_LEVEL)),
            )
        }

        // GESTURE layer (phone only, both sources; ported from TorrentPlayerScreen): tap = controls;
        // left/right double-tap = ∓10s; long-press = temporary 2×; horizontal swipe = seek;
        // vertical swipe LEFT = brightness (volume-by-swipe removed: volume is the on-screen slider
        // button now, see PlayerVolume). For archive, a large downward swipe opens the episode list.
        if (!isTv) {
            Box(
                Modifier.fillMaxSize()
                    // `casting` goes as a KEY, not just as a condition inside: pointerInput
                    // launches its coroutine once and keeps that composition's lambdas until a key
                    // changes. Without this, the double-tap would keep calling the pre-cast
                    // composition's seekBy, which captured `activePlayer` (a val) pointing at the
                    // local player — and on the phone you have to touch the screen for the cast
                    // button to appear, so that lambda is ALWAYS from before the session.
                    .pointerInput(casting) {
                        detectTapGestures(
                            onTap = {
                                // Live (Task 14): the tap shows/hides the channel CARD, not VOD's
                                // controls bar (which doesn't even compose in live -- see
                                // `visible = !isLive && ...` further below). Same show/hide pair
                                // as VOD's controls.visible/bump(), with its own state. The card
                                // belongs to Magis live: on a Caracol channel the tap shows nothing.
                                if (isLive) liveState.toggleInfo()
                                else controls.toggle()
                            },
                            onDoubleTap = { o ->
                                // No seek in live (no duration or "forward/back" that makes sense).
                                if (!isLive) { if (o.x < size.width / 2) seekBy(-seekStepMs) else seekBy(seekStepMs) }
                            },
                            onLongPress = {
                                // Not while casting: the temporary 2× acts on the local player,
                                // which isn't what the Chromecast plays — the gesture stays inert.
                                // Not in live either: a temporary 2x on a live stream has no
                                // "forward" to come back from.
                                if (!isLive && !casting) {
                                    gestures.startAccelerating()
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                // ALWAYS restores, even if the cast started mid-gesture: otherwise
                                // the local speed stays stuck at 2× and once the cast session ends
                                // local playback resumes fast. Restoring it does no harm while
                                // casting (the local engine is paused anyway).
                                gestures.stopAccelerating()
                            },
                        )
                    }
                    // `casting` also goes as a key here: the seek swipe reads `activePlayer` in
                    // onDragStart/onDrag/onDragEnd, and without restarting the detector those
                    // lambdas would keep the local player even with the cast session already alive.
                    .pointerInput(casting) {
                        var horizontal = false
                        var decided = false
                        var startX = 0f
                        var seekTarget = 0L
                        var totalDx = 0f
                        var totalDy = 0f
                        detectDragGestures(
                            onDragStart = { o ->
                                decided = false; horizontal = false; startX = o.x
                                totalDx = 0f; totalDy = 0f
                                // While casting, the horizontal seek must start/apply on the
                                // active player (Chromecast), not always the local one.
                                seekTarget = activePlayer.currentPosition.coerceAtLeast(0)
                            },
                            onDragEnd = {
                                // Live (Task 14): the vertical swipe IS zapping -- up moves to the
                                // next (as if the content "scrolled up"), down to the previous.
                                // ZAP_THRESHOLD_PX small on purpose: it's the only way to zap on
                                // the phone, and doesn't compete with anything else (no
                                // seek/volume/brightness in live, see onDrag below). On a Caracol
                                // channel there's no zapping (it belongs to Magis live): the swipe does nothing.
                                if (isLive) {
                                    if (isMagisLive && !horizontal && kotlin.math.abs(totalDy) > ZAP_THRESHOLD_PX) {
                                        if (totalDy < 0) vm.zapNext() else vm.zapPrevious()
                                        liveState.showInfo()
                                    }
                                } else if (horizontal) {
                                    activePlayer.seekTo(seekTarget); mirror.jumpTo(seekTarget); bump()
                                } else if (totalDy > 240f && totalDy > kotlin.math.abs(totalDx) * 1.5f) {
                                    onOpenEpisodesState.value()
                                }
                                gestures.clearHud()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                totalDx += drag.x; totalDy += drag.y
                                if (!decided) { decided = true; horizontal = kotlin.math.abs(drag.x) >= kotlin.math.abs(drag.y) }
                                // Live: nothing to draw frame by frame -- the zap is resolved
                                // entirely in onDragEnd, above. No seek/volume/brightness, see its comment.
                                if (isLive) return@detectDragGestures
                                if (horizontal) {
                                    val dur = activePlayer.duration.coerceAtLeast(1)
                                    seekTarget = (seekTarget + (drag.x / size.width * 90_000f).toLong()).coerceIn(0L, dur)
                                    gestures.showHud("⏱ ${formatDuration(seekTarget)}")
                                } else if (startX <= size.width / 2) {
                                    // Volume-by-swipe was REMOVED (user request): the only way to
                                    // change volume is now the on-screen slider button (see
                                    // PlayerVolume). The vertical swipe keeps ONLY brightness, and
                                    // only on the LEFT half; a right-half vertical swipe no longer
                                    // changes anything.
                                    activity?.window?.let { w ->
                                        val cur = w.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                                        val nb = (cur - drag.y / size.height).coerceIn(0.02f, 1f)
                                        w.attributes = w.attributes.apply { screenBrightness = nb }
                                        gestures.showHud("☀ ${(nb * 100).toInt()}%")
                                    }
                                }
                            },
                        )
                    },
            )
        }

        // Central HUD of the gesture in progress (speed/seek/volume/brightness).
        gestures.hud?.let { hud ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    hud,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        // Spinner. Also shows while casting: `mirror.buffering` follows the active player, so
        // while the receiver loads it turns off the transport row, and without a spinner the
        // screen was left with the gradient, the top bar, and the Chromecast card — nothing else,
        // no controls, no explanation. `waitingForVideo` is also overridden while casting: it
        // waits for the local player to recover its local video output (up to 15s after returning
        // from the background), which doesn't matter and will never arrive while casting.
        //
        // MAGIS TOO, and it didn't used to: the condition excluded it (`magisItem == null`) with
        // no explanation, and the effect was that as soon as a magis movie loaded, the spinner
        // stopped drawing no matter what. Since the first frame takes a while -- 8 s measured on the Fire
        // Stick -- it left a silent black screen, which is what made it look like the app had
        // frozen. MagisExoPlayer doesn't draw its own spinner, so there was nothing to duplicate.
        if (
            loadError == null && dlnaState.active == null &&
            shouldShowSpinner(
                noPlaylist = playlist == null && magisItem == null && liveItem == null && dituPlay == null,
                buffering = mirror.buffering,
                noFirstFrame = noFirstFrame,
                lostVideoOutput = waitingForVideo,
                casting = casting,
            )
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                if (resolving) {
                    Text("Resolviendo fuente $resolvingSourceName…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                // Magis live (Task 14): resolving a channel takes about 3s (two calls to the
                // portal, see LiveController's KDoc) -- with no text, this same spinner looks
                // identical to a freeze. A Caracol channel doesn't zap: while resolving it says
                // `resolving`, and afterward it falls to the "Cargando video…" below.
                if (isMagisLive) {
                    Text("Cambiando de canal…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                if (waitingForVideo && !casting) {
                    Text("Reanudando video…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                // The other sources -- magis, archive -- said NOTHING while loading: just the
                // spinning circle, which is indistinguishable from a freeze. An 18s wait was
                // measured on the Fire Stick (the CDN rejected two ranges and the proxy retried
                // them) with not a single word on screen. The text only shows when nothing else
                // covers it, to avoid stacking two lines saying the same thing.
                if (!resolving && !isMagisLive && !waitingForVideo) {
                    Text(
                        if (casting) "Cargando en el receptor…" else "Cargando video…",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // Resolution error. When it's specifically "this live channel needs a linked account"
        // (needsMagisAccount), a "Vincular cuenta" action rides along -- there's no upfront offer
        // any more, so this is the only door left to fix it right here, without leaving the player.
        loadError?.let { err ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCCB00020))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                if (needsMagisAccount) {
                    Button(
                        onClick = { showMagisLinkOffer = true },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .focusRequester(linkAccountFocus)
                            .onFocusChanged { linkAccountFocused = it.isFocused },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    ) {
                        Text("Vincular cuenta", color = Color(0xFFB00020))
                    }
                }
            }
        }

        // The on-demand link prompt itself, floated over the player (not a full-screen replace --
        // the video underneath stays composed). Same screen Settings uses for voluntary linking
        // (MagisLinkOffer/TvMagisLinkOffer); closes itself on success and retries the load either
        // way, since "Ahora no" also links a fallback account that may now satisfy live (see
        // MagisAccount.linkFallbackAccount's KDoc).
        if (showMagisLinkOffer) {
            val magisState by graph.magisAccount.state.collectAsStateWithLifecycle()
            val regionGeoBlocked by graph.regionGeoBlocked.collectAsStateWithLifecycle()
            LaunchedEffect(magisState) {
                if (magisState is MagisAccountState.Linked) {
                    showMagisLinkOffer = false
                    vm.load(episodeId)
                }
            }
            Dialog(
                onDismissRequest = {},
                // No dismiss-on-back/outside-tap: the screen's own BackHandler (`::skip`, same as
                // "Ahora no") is the one door out, so back does the same thing the button does
                // instead of racing the Dialog's own dismiss and leaving `showMagisLinkOffer`
                // stuck `true` behind a closed window.
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                Box(Modifier.fillMaxSize().background(ArkivBlack)) {
                    if (isTv) {
                        TvMagisLinkOffer(
                            account = graph.magisAccount,
                            regionGeoBlocked = regionGeoBlocked,
                            onNotNow = {
                                showMagisLinkOffer = false
                                vm.load(episodeId)
                            },
                        )
                    } else {
                        MagisLinkOffer(
                            account = graph.magisAccount,
                            regionGeoBlocked = regionGeoBlocked,
                            onNotNow = {
                                showMagisLinkOffer = false
                                vm.load(episodeId)
                            },
                        )
                    }
                }
            }
        }

        // "Fun fact" badge at the top, centered, and the panel that expands the text (see its
        // KDoc in `PlayerTrivia.kt`). On the phone the badge is tappable; on TV it opens with the
        // up arrow, see the video's listener.
        TriviaBadge(
            state = triviaState,
            onTap = if (isTv) null else ({ triviaState.showNext(trivia.size) }),
        )
        TriviaPanel(triviaState, trivia)

        // Casting to Chromecast.
        if (casting) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Has to clear the top bar, so it shares its same insets frame
                    // (systemBarsPadding) instead of a fixed padding measured from the screen
                    // edge -- otherwise, on devices with a tall status bar they overlap.
                    .systemBarsPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
                Text("Reproduciendo en Chromecast", color = Color.White)
            }
        }

        // Current marker for the chapter playing: chapter or series, per ChapterMarker.choose's
        // precedence (manual-chapter > manual-series > auto-chapter > auto-series). Reactive, and
        // NOT the one baked into `d` when the playlist was built (Task 6 asks the gateway for the
        // automatic one in the background and saves it AFTER this overlay has already been drawn
        // -- with a static read the button would never appear for that chapter).
        // `remember` keeps it with the SAME Flow identity while itemId/episode don't change:
        // without this, every recomposition (the progress bar recomposes on every tick) would
        // request a new Flow and restart the Room query constantly.
        val chapterMarkers by remember(d?.itemId, currentEpisode) {
            graph.repository.skipMarkerDao().observeForChapter(d?.itemId ?: "", currentEpisode)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        val currentMarker = ChapterMarker.choose(
            fromChapter = chapterMarkers.firstOrNull { it.episodeId == currentEpisode },
            fromSeries = chapterMarkers.firstOrNull { it.episodeId.isEmpty() },
        )

        // Floating skip intro/outro buttons. They used to only show on the phone and outside
        // torrents: markers were set by hand per series and only for archive. Now they come from
        // the work's identity (tmdbId + chapter), so they work the same on the Fire TV -- which
        // is where anime is watched, the case that drove all of this -- and on a chapter
        // downloaded by torrent.
        val outroAction = OutroSkip.decide(
            currentIndex = currentIndex,
            playlistItems = playlist?.items?.size ?: 0,
            nextChapter = header.next,
        )
        // Which of the two buttons shows, if any. Computed up here, away from where it's drawn,
        // for two reasons: the focus effect also cares about the instant the button stops
        // existing (inside the `if` that draws it, it would leave the composition right then and
        // nobody would return focus), and the progress bar -- which composes earlier -- needs to
        // know whether there's a button to send its TOP there.
        val skipButton = when {
            currentMarker == null || markers.marking || dlnaState.active != null -> null
            else -> SkipButtonKind.which(
                inOpening = ChapterMarker.inOpening(currentMarker, mirror.positionMs),
                inEnding = ChapterMarker.inEnding(currentMarker, mirror.positionMs),
                outroAction = outroAction,
                casting = casting,
            )
        }

        // ---- Custom controls (fade in/out) ----
        AnimatedVisibility(
            // They DO show while casting: the transport controls the Chromecast (see
            // activePlayer). The inner elements that only apply to the local player carry their
            // own `!casting` guard.
            //
            // `!isLive` (Task 14): this WHOLE block is VOD (slider/seek/"next episode"/markers/
            // chapter carousel) -- none of that applies in live (no duration, no seek, no "next"
            // that isn't zapping). Instead of rewriting every control in here with its own guard,
            // it's cut once up here (the flag that isolates live mode, see `isLive`'s KDoc) and
            // further below there's a small, dedicated overlay for live ("LIVE" badge + channel
            // card for 3s).
            visible = !isLive && controls.visible && loadError == null && dlnaState.active == null && !markers.marking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Scrim that makes text and controls readable over the video. On TV the top one holds
            // lower (an intermediate stretch instead of dropping all at once): the header is two
            // big lines -- the series and "E131 · chapter name" -- and with the phone's drop-off
            // the second one already sat over bare video, unreadable on any light background.
            val scrim = if (isTv) {
                arrayOf(
                    0.0f to Color(0xB3000000),
                    0.22f to Color(0x8C000000),
                    0.42f to Color(0x14000000),
                    0.70f to Color(0x14000000),
                    1.0f to Color(0xD9000000),
                )
            } else {
                arrayOf(
                    0.0f to Color(0xB3000000),
                    0.30f to Color(0x14000000),
                    0.70f to Color(0x14000000),
                    1.0f to Color(0xD9000000),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(*scrim))
                    // Any key while the overlay is open resets the auto-hide timer, so it doesn't
                    // fade out from under you while navigating buttons or thumbnails. It used to
                    // only be reset by bump(), which fires from the video's listener -- and that
                    // only acts when controls are HIDDEN, so moving with the D-pad never reset it.
                    // It goes on the container and as a PREVIEW (not onKeyEvent): the preview
                    // travels down from the root before reaching the focused control, so it sees
                    // every key even if something consumes it -- the slider consumes left/right
                    // for seek and the row consumes DOWN, which with normal bubbling would never
                    // have reached here.
                    // Returns false: it only observes, it doesn't alter dispatch.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) controls.keepAlive()
                        false
                    }
                    // TV safe zone. Goes AFTER `background` on purpose: the gradient keeps
                    // painting edge to edge (it's the scrim that makes the controls readable over
                    // the video) and the padding only pulls the content inward.
                    //
                    // It's not a style choice: a TV crops the edge of the image (overscan) and how
                    // much it crops depends on the device. Measured on the Fire Stick, the title
                    // sat at 16 dp from the left edge, the duration at 15 dp from the right, and
                    // the transport row at 17 dp from the bottom edge -- i.e. exactly what a TV
                    // with overscan eats first. The TV library's constants are reused so there
                    // aren't two numbers meaning the same thing that could drift apart.
                    .then(if (isTv) Modifier.padding(horizontal = SAFE_H, vertical = SAFE_V) else Modifier),
            ) {
                // Top bar: back (phone) + title + markers/CC/cast.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Phone: back button hidden in LANDSCAPE (fullscreen video) -- the user asked to
                    // keep the horizontal player clean; portrait still has it.
                    if (!isTv && !isLandscape) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                        }
                    }
                    // Marker editor (archive only, phone). Hidden: it's the only access to setting
                    // intro/outro, so it's kept behind the flag.
                    if (SHOW_MARKERS_ON_PHONE && !isTv && d != null) {
                        Box {
                            IconButton(onClick = { markers.openMenu() }) {
                                Icon(Icons.Default.Tune, contentDescription = "Marcadores", tint = Color.White)
                            }
                            DropdownMenu(expanded = markers.menuOpen, onDismissRequest = { markers.closeMenu() }) {
                                DropdownMenuItem(
                                    text = { Text("Setear intro (fin del opening)") },
                                    onClick = { markers.mark(MarkMode.INTRO) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Setear outro (inicio del ending)") },
                                    onClick = { markers.mark(MarkMode.OUTRO) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Borrar marcadores") },
                                    onClick = { markers.closeMenu(); vm.clearMarkers() },
                                )
                            }
                        }
                    }
                    if (!isTv && d != null) {
                        // What's being watched. The title comes from header.info (the SERIES
                        // name) and not from d.title, so that for series it doesn't show the
                        // chapter name; below it, season/chapter. d.title stays as a fallback if
                        // header.info hasn't loaded yet (read from the DB in a LaunchedEffect).
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                header.title(d.title),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            header.episodeLabel?.let { ep ->
                                Text(
                                    ep,
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // Speed (phone only): cycle on tap. Zoom moved to the two +/- buttons in the
                    // secondary icon row (they replaced the brightness buttons on phone).
                    // Hidden while casting: the long-press gesture still gives a temporary 2×, so
                    // speed control isn't lost entirely.
                    // Not while casting: nextSpeed() acts on the local player, which isn't what the
                    // Chromecast plays.
                    if (SHOW_SPEED_AND_ZOOM_ON_PHONE && !isTv && !casting) {
                        TextButton(onClick = { gestures.nextSpeed() }) {
                            Text(
                                gestures.speedLabel,
                                color = if (gestures.speedIsNormal) Color.White else ArkivRed,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    // (The phone's CC/audio button moved down to the right, next to the transport
                    // row; on TV it was always in the bottom icon row.)
                    // DLNA + Chromecast (phone only, and PORTRAIT only -- hidden in landscape
                    // fullscreen at the user's request). Buttons shared with live mode, see
                    // `DlnaCastButtons`.
                    if (!isTv && !isLandscape) {
                        // Note specific to this Row: since `dlnaState.active != null` hides the
                        // whole controls overlay (visible = ... && dlnaState.active == null
                        // above), casting would have no way to be managed from the app if DLNA is
                        // active. That's why the Chromecast button below stays visible no matter
                        // what: it's the only path to end the session.
                        DlnaCastButtons(casting = casting, castContext = castContext, onDiscoverDlna = dlnaState::discover)
                    }
                }

                // TV: what's being watched (series or movie) and, if a series, season/chapter.
                // Shows whenever the interface is up, not just while paused: that's exactly when
                // someone opens it to know which chapter they're on. Since the overlay already
                // auto-hides after 4.5s, it doesn't compete with the video playing.
                if (isTv) {
                    header.info?.let { info ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .systemBarsPadding()
                                // No `top`: it used to reserve 56 dp to avoid overlapping the top
                                // icon bar, but that bar is entirely behind `!isTv` -- on TV it
                                // draws nothing. With the container's safe zone (SAFE_V) that 56 dp
                                // stacked on top and the title ended up sunk ~100 dp from the edge.
                                .padding(start = 16.dp, end = 16.dp)
                                // Width ceiling so the `Ellipsis` below actually applies: the
                                // Column is aligned in a full-screen Box, so without this it
                                // stretches with the text and a long name would cross the whole
                                // screen over the video instead of getting cut off.
                                .fillMaxWidth(0.6f),
                        ) {
                            Text(
                                info.itemTitle,
                                color = Color.White,
                                // headlineMedium (28sp) scaled 1.5×: the series name is the first
                                // thing read on pause and at 28 it competed with the chapter line.
                                // lineHeight is scaled the same way (36→54) so the box doesn't
                                // clip accents or accented capitals.
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 42.sp,
                                    lineHeight = 54.sp,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            // "E130 · Goku's opponent is… Goku?": the number alone doesn't say
                            // what the chapter is about, which is exactly what someone looks for
                            // on pause. The name comes from the same table (`episode_still`)
                            // already used by the carousel below, so it costs no extra query --
                            // and when we don't have it (not fetched from the gateway yet, or a
                            // source with no names) it falls back to the number alone, as before.
                            info.episodeLabel?.let { ep ->
                                val chapterName = chaptersState.titles[currentEpisode]?.takeIf { it.isNotBlank() }
                                Text(
                                    if (chapterName != null) "$ep · $chapterName" else ep,
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        // Time + slider + duration.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatDuration(seek.positionToShow(mirror.positionMs)),
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = seek.barValue(mirror.positionMs),
                                // Grabbing the bar discards any pending incremental jump: otherwise
                                // `seekBy`'s debounce would fire AFTER releasing and would snap you
                                // back to the arrow-key destination, stomping on the drag.
                                onValueChange = { v ->
                                    seek.dragTo(v)
                                    bump()
                                },
                                onValueChangeFinished = { seekTo(seek.release()) },
                                valueRange = 0f..(if (mirror.durationMs > 0) mirror.durationMs.toFloat() else 1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = ArkivRed,
                                    activeTrackColor = ArkivRed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                // Custom track with 3 layers: background (faint) + downloaded
                                // buffer (light gray) + played (red). This way the buffer shows
                                // ahead of the playhead.
                                track = { _ ->
                                    val dur = if (mirror.durationMs > 0) mirror.durationMs.toFloat() else 1f
                                    val posFrac = (seek.barValue(mirror.positionMs) / dur)
                                        .coerceIn(0f, 1f)
                                    val bufFrac = mirror.bufferedFraction.coerceIn(0f, 1f)
                                    // The track's thickness IS the focus indicator (the stroke is
                                    // derived from the Canvas's height, so thickening the height
                                    // thickens all three layers at once). Animated so the jump
                                    // doesn't feel abrupt.
                                    val trackHeight by animateDpAsState(
                                        targetValue = if (seek.barFocused) 8.dp else 4.dp,
                                        label = "progressBarThickness",
                                    )
                                    Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                                        val y = size.height / 2f
                                        val sw = size.height
                                        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
                                        if (bufFrac > 0f) drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width * bufFrac, y), sw, StrokeCap.Round)
                                        if (posFrac > 0f) drawLine(ArkivRed, Offset(0f, y), Offset(size.width * posFrac, y), sw, StrokeCap.Round)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                                    .then(
                                        if (!isTv) Modifier else Modifier
                                            .focusRequester(focusPoints.bar)
                                            .onFocusChanged { seek.focusChanged(it.isFocused) }
                                            // UP stays on the bar: it's the top of the overlay and
                                            // the buttons are BELOW, so sending `up` there used to
                                            // be a backwards jump (barely visible before, since
                                            // focus never reached here; now it's the first focused
                                            // control). UP stays on the bar when there's nothing
                                            // above, but if the skip button is on screen there is:
                                            // it sits right above the bar (see its padding), so
                                            // that's the way back for anyone who left the button
                                            // and changed their mind.
                                            .focusProperties {
                                                down = focusPoints.playPause
                                                up = if (skipButton != null) focusPoints.skip else focusPoints.bar
                                                left = focusPoints.bar
                                                right = focusPoints.bar
                                            }
                                            .onKeyEvent { e ->
                                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                                when (e.key) {
                                                    Key.DirectionRight -> { seekBy(seekStepMs); true }
                                                    Key.DirectionLeft -> { seekBy(-seekStepMs); true }
                                                    // OK on the bar toggles play/pause. With focus
                                                    // here, center used to do nothing, and pausing
                                                    // is the most frequent action: it forced going
                                                    // down to the button and back up. It calls the
                                                    // SAME `togglePlayPause` as the button so they
                                                    // can't diverge. Both keys are accepted because
                                                    // not every remote sends the same thing: Android
                                                    // TV ones usually send DPAD_CENTER and some
                                                    // (and the emulator) send ENTER.
                                                    Key.DirectionCenter, Key.Enter -> { togglePlayPause(); true }
                                                    else -> false
                                                }
                                            },
                                    ),
                            )
                            Text(formatDuration(mirror.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))

                        // PORTRAIT DOES NOT FIT. Transport is five buttons and the secondary
                        // controls are up to five more; at 48dp each that is ~430dp of icon before
                        // a single gap, against roughly 379dp of usable width on a phone held
                        // upright. They were not merely cramped, they ran off the screen. So in
                        // portrait they go on a SECOND row and the transport centres itself;
                        // landscape has the room and keeps the single row it always had.
                        val isPortrait = LocalConfiguration.current.orientation ==
                            Configuration.ORIENTATION_PORTRAIT
                        // Casting, tracks are chosen on the LOCAL player, so these hide entirely.
                        val hasSecondaryButtons = !isTv && !casting
                        val secondaryIcons: @Composable () -> Unit = {
                            if (hasMarkersToFix) {
                                ChapterMarkersMenu(
                                    state = markers,
                                    isTv = false,
                                    onEndOfOpening = { markTime(MarkMode.INTRO) },
                                    onStartOfEnding = { markTime(MarkMode.OUTRO) },
                                    onRemove = { removeChapterMarkers() },
                                )
                            }
                            IconButton(onClick = { tracksState.openPicker() }) {
                                Icon(
                                    if (tracksState.hasSubtitle) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                                    contentDescription = "Subtítulos y audio",
                                    tint = if (tracksState.hasSubtitle) ArkivRed else Color.White,
                                )
                            }
                            // ZOOM (phone only). Replaces the two brightness buttons here: pinch
                            // zoom is finicky, and wide movies come with thick letterbox bars, so
                            // two buttons crop the video to fill the screen. Night mode stays
                            // reachable through the left-edge brightness gesture (and the TV UI).
                            IconButton(onClick = { gestures.zoomOut() }) {
                                Icon(
                                    Icons.Default.ZoomOut,
                                    contentDescription = "Alejar (menos zoom)",
                                    tint = if (gestures.zoomIsFit) Color.White else ArkivRed,
                                )
                            }
                            IconButton(onClick = { gestures.zoomIn() }) {
                                Icon(
                                    Icons.Default.ZoomIn,
                                    contentDescription = "Acercar (más zoom, recorta las barras negras)",
                                    tint = if (gestures.zoomIsFit) Color.White else ArkivRed,
                                )
                            }
                            if (PlayerTrivia.hasButton(trivia)) {
                                IconButton(onClick = { triviaState.showNext(trivia.size) }) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        tint = Color.White,
                                    )
                                }
                            }
                            // Volume: the rightmost of the secondary row -- a speaker button that
                            // pops a vertical slider, for people who prefer a button to the swipe.
                            VolumeButton(gestures)
                        }

                        // Previous chapter / rewind / play-pause / forward / next chapter /
                        // subtitles (TV) -- all in a single row, left/right navigable with the
                        // D-pad. The two chapter-skip buttons sit at the ends of the transport, as
                        // in any player: |< << ▶ >> >|.
                        Row(
                            modifier = Modifier
                                // On the phone the row fills the whole width so the subtitles
                                // button can be pushed against the right edge (see the end of
                                // this Row).
                                .then(if (isTv) Modifier else Modifier.fillMaxWidth())
                                .then(
                                // One more DOWN press from this row reveals the chapter carousel
                                // (it doesn't exist in the tree yet until chaptersState.revealed
                                // is true, so it can't be resolved with a normal
                                // focusProperties.down -- the key is intercepted here and the
                                // reveal is triggered).
                                if (!isTv || !chaptersState.hasCarousel) Modifier else Modifier.onKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                        // Closed: reveal it (the LaunchedEffect moves focus to the
                                        // chip). Already open: move focus down to the carousel --
                                        // it used to fall to the button's `down` and there was no
                                        // way back in.
                                        if (!chaptersState.revealed) chaptersState.reveal()
                                        else runCatching { chaptersState.focusRequester.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            ),
                            // Portrait spreads them across the whole width; landscape keeps the
                            // fixed gaps, because there the spacer below pushes the secondary icons
                            // to the right end of this same row and even spacing would fight it.
                            //
                            // `SpaceEvenly` and not a centred cluster: this row and the one under it
                            // then span the same width and share the same rhythm, which is what
                            // makes them read as one block of controls instead of two leftovers.
                            // It also never overflows -- at 48dp a side, eight icons is the point
                            // where 384dp of phone runs out, and the most this player ever shows
                            // is five.
                            horizontalArrangement = if (isPortrait) {
                                Arrangement.SpaceEvenly
                            } else {
                                Arrangement.spacedBy(20.dp)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The chapter-skip buttons depend ONLY on whether the neighbor exists,
                            // never on transport state. Hanging them off `playing` (as the "next"
                            // one used to be) made them flicker: that falls to false on every
                            // rebuffer and every seek -- the controller reports STATE_BUFFERING
                            // directly, with no translator in between -- so the button would
                            // appear while seeking forward and disappear again once it hit READY.
                            // See `mirror.wantsToPlay`'s KDoc.
                            // Computed BEFORE the buttons so the full focus graph can be built
                            // (every direction explicit; leaving one undefined makes Compose's
                            // default spatial search fail and focus "gets lost").
                            val prev = header.previous
                            val next = header.next
                            val showPrev = prev != null
                            val showNext = next != null
                            val forwardRight = if (showNext) focusPoints.nextEpisode else if (isTv) focusPoints.subtitles else focusPoints.forward
                            val nextRight = if (isTv) focusPoints.subtitles else focusPoints.nextEpisode
                            // First in the row when it exists: its `left` points to itself (top).
                            if (showPrev) {
                                TvTransportButton(
                                    icon = Icons.Default.SkipPrevious,
                                    contentDescription = "Capítulo anterior",
                                    onClick = { onNextEpisode(prev) },
                                    modifier = if (!isTv) Modifier else Modifier
                                        .focusRequester(focusPoints.previousEpisode)
                                        .focusProperties { left = focusPoints.previousEpisode; right = focusPoints.rewind; up = focusPoints.bar; down = focusPoints.previousEpisode },
                                )
                            }
                            // `down` points to the button itself (it stays put) and NOT to the
                            // slider: going down from here used to move focus to the progress bar,
                            // where left/right seek -- hence the "sometimes it switches buttons,
                            // sometimes it fast-forwards/rewinds". When there are chapters, DOWN is
                            // intercepted by the row's onKeyEvent (above).
                            TvTransportButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Atrasar 10s",
                                onClick = { seekBy(-seekStepMs) },
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focusPoints.rewind)
                                    .focusProperties {
                                        left = if (showPrev) focusPoints.previousEpisode else focusPoints.rewind
                                        right = focusPoints.playPause
                                        up = focusPoints.bar
                                        down = focusPoints.rewind
                                    },
                            )
                            TvTransportButton(
                                icon = if (mirror.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (mirror.playing) "Pausar" else "Reproducir",
                                onClick = { togglePlayPause() },
                                iconSize = 34.dp,
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focusPoints.playPause)
                                    .focusProperties { left = focusPoints.rewind; right = focusPoints.forward; up = focusPoints.bar; down = focusPoints.playPause },
                            )
                            TvTransportButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Adelantar 10s",
                                onClick = { seekBy(seekStepMs) },
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focusPoints.forward)
                                    .focusProperties { left = focusPoints.playPause; right = forwardRight; up = focusPoints.bar; down = focusPoints.forward },
                            )
                            // Also shows while casting: the new chapter now FOLLOWS the cast (the
                            // load branches on `casting` and sends it to the receiver), which is
                            // the whole point of this feature. The TV's chapter carousel never had
                            // this guard, so this also stops them from contradicting each other.
                            if (showNext) {
                                TvTransportButton(
                                    icon = Icons.Default.SkipNext,
                                    contentDescription = "Siguiente episodio",
                                    onClick = { onNextEpisode(next) },
                                    modifier = if (!isTv) Modifier else Modifier
                                        .focusRequester(focusPoints.nextEpisode)
                                        .focusProperties { left = focusPoints.forward; right = nextRight; up = focusPoints.bar; down = focusPoints.nextEpisode },
                                )
                            }
                            if (isTv) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 4.dp)
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(Color.White.copy(alpha = 0.3f)),
                                )
                                // Focused inverts like the others (black icon on white); whether
                                // subtitles are active is still distinguished by the icon
                                // (ClosedCaption vs ClosedCaptionOff), not just by the red tint.
                                TvTransportButton(
                                    icon = if (tracksState.hasSubtitle) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                                    contentDescription = "Subtítulos y audio",
                                    onClick = { tracksState.openPicker() },
                                    iconSize = 24.dp,
                                    tint = if (tracksState.hasSubtitle) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focusPoints.subtitles)
                                        .focusProperties {
                                            left = if (showNext) focusPoints.nextEpisode else focusPoints.forward
                                            right = focusPoints.dimDown
                                            up = focusPoints.bar
                                            down = focusPoints.subtitles
                                        },
                                )
                                // NIGHT MODE, two buttons: lower (moon) on the left and raise
                                // (sun) on the right, i.e. less → more, reading order. Never
                                // disabled at the ends: on TV a disabled button doesn't receive
                                // focus and would break the D-pad chain right at the edge.
                                TvTransportButton(
                                    icon = Icons.Default.Brightness2,
                                    contentDescription = "Bajar brillo",
                                    onClick = { gestures.brightnessStep(+1, clampedDimLevel) },
                                    iconSize = 24.dp,
                                    tint = if (clampedDimLevel > 0) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focusPoints.dimDown)
                                        .focusProperties {
                                            left = focusPoints.subtitles
                                            right = focusPoints.dimUp
                                            up = focusPoints.bar
                                            down = focusPoints.dimDown
                                        },
                                )
                                TvTransportButton(
                                    icon = Icons.Default.BrightnessHigh,
                                    contentDescription = "Subir brillo",
                                    onClick = { gestures.brightnessStep(-1, clampedDimLevel) },
                                    iconSize = 24.dp,
                                    tint = if (clampedDimLevel > 0) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focusPoints.dimUp)
                                        .focusProperties {
                                            left = focusPoints.dimDown
                                            right = when {
                                                PlayerTrivia.hasButton(trivia) -> focusPoints.trivia
                                                hasMarkersToFix -> focusPoints.markers
                                                else -> focusPoints.dimUp
                                            }
                                            up = focusPoints.bar
                                            down = focusPoints.dimUp
                                        },
                                )
                                // Fun facts: only exists if there's data. Goes at the END of the
                                // row on purpose -- inserting it in the middle would force
                                // rewriting several links of this focus chain. The button above's
                                // `right` already accounts for it.
                                if (PlayerTrivia.hasButton(trivia)) {
                                    TvTransportButton(
                                        icon = Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        onClick = { triviaState.showNext(trivia.size) },
                                        iconSize = 24.dp,
                                        tint = Color.White,
                                        // Last in the row: its `right` points to itself (right edge).
                                        modifier = Modifier
                                            .focusRequester(focusPoints.trivia)
                                            .focusProperties {
                                                left = focusPoints.dimUp
                                                right = if (hasMarkersToFix) focusPoints.markers else focusPoints.trivia
                                                up = focusPoints.bar
                                                down = focusPoints.trivia
                                            },
                                    )
                                }
                                // Fix the current chapter's times. Goes at the end of the row --
                                // putting it in the middle forces rewriting links of the focus
                                // chain.
                                if (hasMarkersToFix) {
                                    ChapterMarkersMenu(
                                        state = markers,
                                        isTv = true,
                                        onEndOfOpening = { markTime(MarkMode.INTRO) },
                                        onStartOfEnding = { markTime(MarkMode.OUTRO) },
                                        onRemove = { removeChapterMarkers() },
                                        modifier = Modifier
                                            .focusRequester(focusPoints.markers)
                                            .focusProperties {
                                                left = if (PlayerTrivia.hasButton(trivia)) focusPoints.trivia else focusPoints.dimUp
                                                right = focusPoints.markers
                                                up = focusPoints.bar
                                                down = focusPoints.markers
                                            },
                                    )
                                }
                            }
                            // LANDSCAPE: they ride at the right end of this same row, pushed
                            // there by the spacer. In portrait they do not fit and go below --
                            // see `isPortrait` above.
                            if (hasSecondaryButtons && !isPortrait) {
                                Spacer(Modifier.weight(1f))
                                secondaryIcons()
                            }
                        }

                        // PORTRAIT: the secondary controls, on their own row, spread across the
                        // full width on the same rhythm as the transport row above. They were
                        // right-aligned first and it looked like what it was -- leftovers shoved
                        // into a corner, two thirds of the row empty, and the two rows not even
                        // sharing an axis. They keep the order they have in landscape, so the same
                        // icon is in the same place whichever way the phone is held.
                        if (hasSecondaryButtons && isPortrait) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                secondaryIcons()
                            }
                        }
                        // Chapter carousel (TV, series with more than 1 episode): one more step
                        // down from the icon row. All episodes in horizontal scroll, with the
                        // current one highlighted and centered when it appears.
                        if (isTv && chaptersState.hasCarousel) {
                            ChapterCarousel(
                                state = chaptersState,
                                currentEpisode = currentEpisode,
                                upFocus = focusPoints.playPause,
                                onChooseEpisode = onNextEpisode,
                            )
                        }
                    }
            }
        }

        // ---- Live mode (Task 14): its own small overlay -- replaces the ENTIRE block above.
        // The three pieces live in `PlayerLive.kt`. ----
        if (isLive) {
            // Phone only: on TV this band had nothing left to show once the "EN VIVO" badge was
            // dropped (its back button and cast buttons were already phone-only, see LiveBanner's
            // KDoc), so it's skipped here instead of composing it empty.
            // Phone PORTRAIT only: the live band carries the back + cast buttons, both hidden in
            // landscape fullscreen at the user's request (same as VOD above).
            if (!isTv && !isLandscape) {
                LiveBanner(onBack = onBack) {
                    // Task 18: the SAME buttons as VOD (`dlnaState` is a single instance for the
                    // whole screen), just hung off THIS strip because the VOD block is hidden here
                    // (visible=!isLive). It's offered FROM THE PLAYER, not in LiveScreen's earlier
                    // dialog: only once the channel is actually playing is there real audio to feed
                    // CastAudioSupport (see castRequestFor's KDoc) -- before playback starts there's
                    // nowhere to pull that reading from, neither for live nor for VOD (VOD doesn't
                    // offer cast in its own destination dialog either, for the same reason).
                    DlnaCastButtons(casting = casting, castContext = castContext, onDiscoverDlna = dlnaState::discover)
                }
            }
            // This card is for Magis live: its channel and its EPG. Caracol doesn't have it.
            if (isMagisLive) ChannelCard(state = liveState, channel = liveChannel, liveApi = graph.liveCatalog)
        }

        // Whether the button HAD focus. It's a latch, not the live reading of `isFocused`: when
        // the button leaves the composition, Compose has already reported `isFocused = false`
        // before the effect below runs, so nobody would return focus and the remote would go
        // dead. It's lowered by hand in the two spots where focus actually leaves the button: when
        // it's returned here below, and when the person leaves with an arrow key.
        var skipHadFocus by remember { mutableStateOf(false) }
        // And whether it has it RIGHT NOW. This is the signal the retry uses to know whether focus
        // arrived: requesting it reports nothing (see `retryFocus`), so the only reliable source
        // is the button itself reporting via `onFocusChanged`.
        var skipFocused by remember { mutableStateOf(false) }
        val skipButtonFocus = remember { SkipButtonFocus() }
        LaunchedEffect(skipButton, isTv) {
            if (!isTv) return@LaunchedEffect
            when (skipButtonFocus.onChange(skipButton, skipHadFocus, controls.visible)) {
                // The button just appeared and takes focus: with the chapter playing, a single OK
                // skips the opening. Without this, OK would fall to the transport and PAUSE the
                // video. It retries for a short while (~320 ms) because the node may not be laid
                // out yet in the frame it appears in, and because focus has to be taken away from
                // `videoView` due to Compose's interop. See `retryFocus`: success is signaled by
                // the button reporting that it has focus, NOT by the request not having thrown.
                SkipButtonFocus.Action.REQUEST -> retryFocus(
                    isAlreadyFocused = { skipFocused },
                    wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
                    request = { focusPoints.skip.requestFocus() },
                )
                SkipButtonFocus.Action.RETURN_TO_CONTROLS -> {
                    skipHadFocus = false
                    runCatching { focusPoints.bar.requestFocus() }
                }
                SkipButtonFocus.Action.RETURN_TO_VIDEO -> {
                    skipHadFocus = false
                    runCatching { videoView?.requestFocus() }
                }
                SkipButtonFocus.Action.NONE -> Unit
            }
        }

        if (skipButton != null && currentMarker != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    // The usual 88 dp is for the PHONE. On the Fire TV the overlay is much taller
                    // and the button ended up mounted on top of the progress bar: measured on the
                    // device (1920x1080 at 320 dpi = 960x540 dp), the button occupied 404-452 dp
                    // of height and the bar 384-428 dp -- they overlapped, and with focus over
                    // them it wasn't clear which of the two OK would reach. With 180 dp the button
                    // ends at 360 dp, i.e. 24 dp of clearance above the bar. `end` also rises to
                    // the TV's safe-zone margin: 20 dp from the right edge is exactly what
                    // overscan eats (the rest of the overlay already uses SAFE_H for the same
                    // reason).
                    .padding(
                        end = if (isTv) SAFE_H else 20.dp,
                        bottom = if (isTv) 180.dp else 88.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkipButton(
                    text = if (skipButton == SkipButtonKind.INTRO) "Saltar intro" else "Saltar outro",
                    icon = skipButton == SkipButtonKind.OUTRO,
                    modifier = Modifier
                        .focusRequester(focusPoints.skip)
                        .onFocusChanged {
                            skipFocused = it.isFocused
                            if (it.isFocused) skipHadFocus = true
                        }
                        .then(
                            if (!isTv) Modifier else Modifier.onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    // Ignore the button: any arrow moves focus to the controls and
                                    // the button does NOT steal it back while it stays on screen
                                    // (`SkipButtonFocus` only acts when which button there is
                                    // changes). These have to be intercepted: with focus in
                                    // Compose, the video's listener -- the one that opens the
                                    // overlay on any key -- no longer receives anything, so
                                    // without this the arrows would do absolutely nothing and the
                                    // button would be a trap.
                                    Key.DirectionUp, Key.DirectionDown,
                                    Key.DirectionLeft, Key.DirectionRight,
                                    -> {
                                        skipHadFocus = false
                                        if (controls.visible) runCatching { focusPoints.bar.requestFocus() } else bump()
                                        true
                                    }
                                    // Same reason: remotes with their own play button would stop
                                    // pausing while the button has focus.
                                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                                        togglePlayPause()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        ),
                ) {
                    when (skipButton) {
                        SkipButtonKind.INTRO -> currentMarker.openingEndMs?.let { activePlayer.seekTo(it) }
                        // Where it jumps to is decided by `OutroSkip` (see its KDoc):
                        // `seekToNextMediaItem()` on its own only worked on archive.org (source
                        // removed in this branch's pruning), the only multi-item source, and on
                        // magis/Ditu/local -and the legacy web/torrent/NUC- which publish ONE item,
                        // the button still showed up and did NOTHING.
                        SkipButtonKind.OUTRO -> when (outroAction) {
                            OutroSkip.Action.PLAYLIST_ADVANCE -> controller.seekToNextMediaItem()
                            // The same path as `onEndOfChapter()`: navigating to the new
                            // chapter's route is what restarts source resolution.
                            else -> header.next?.let(onNextEpisode)
                        }
                    }
                }
            }
        }

        // Marking editor panel with slider (archive only).
        if (d != null && markers.marking) {
            MarkerEditor(
                mode = markers.mode!!,
                positionMs = mirror.positionMs,
                durationMs = mirror.durationMs,
                onSeek = { p -> seekTo(p) },
                onCancel = { markers.finish() },
                onSave = {
                    val label = if (markers.mode == MarkMode.INTRO) {
                        vm.setOpeningEnd(mirror.positionMs); "Intro"
                    } else {
                        vm.setEndingStart(mirror.positionMs); "Outro"
                    }
                    android.widget.Toast.makeText(
                        context,
                        "$label guardado en ${formatDuration(mirror.positionMs)}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    markers.finish()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // "Playing on <TV>" bar (DLNA active).
        ActiveDlnaBar(dlnaState)

        // Goes LAST inside the Box so it sits above the rest of the overlays.
        if (isTv && isMagisLive && liveState.drawerOpen) {
            LiveChannelDrawer(
                state = liveState,
                currentChannel = liveChannel?.code,
                onChooseChannel = { list, channel -> vm.goToChannel(list, channel) },
            )
        }
    }

    // When the drawer closes, focus has to be returned to the video: otherwise it's left on a row
    // that no longer exists and the remote stops responding -- neither zapping nor Back. `videoView`
    // is the one holding live's `setOnKeyListener`.
    LaunchedEffect(liveState.drawerOpen) {
        if (!liveState.drawerOpen) {
            repeat(10) {
                if (videoView?.requestFocus() == true) return@LaunchedEffect
                delay(50)
            }
        }
    }

    // A rejection the portal itself explains (region-blocked, a channel taken down, …) --
    // see GatewayBlockedException's KDoc. Its own dialog instead of the resolution-error pill,
    // because it isn't a bug in Kino, and there's nothing to retry: dismissing leaves the player,
    // same as if the source had never resolved.
    blocked?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissBlocked(); onBack() },
            title = { Text("No se puede reproducir") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissBlocked(); onBack() }) { Text("Entendido") }
            },
        )
    }

    // DLNA devices dialog. Building the URL sent to the renderer lives in `sendToRenderer`; here
    // it's only left which item it comes from and what to do if the renderer rejects it.
    DlnaDevicesDialog(dlnaState) { device ->
        // Live via ExoPlayer (Task 1, light-magis pruning) is no longer in `playlist`: it falls to
        // `liveItem`, which carries the same `kind = SourceKind.LIVE` that `sendToRenderer` needs
        // to resolve the proxy's LAN URL (it doesn't use `ep.mediaUrl`/`castUrl` for live).
        // Magis titles (the bulk of the catalog: movies AND series) live in `magisItem`, NOT in the playlist,
        // exactly as the Chromecast path above knows. This line used to look only at the playlist and live,
        // so for a Magis title `ep` was null and DLNA never even started ("nothing is playing"), found from
        // the DLNA log on a real TV.
        val ep = magisItem?.takeIf { it.kind == SourceKind.MAGIS }
            ?: playlistRef.value?.items?.getOrNull(currentIndex)
            ?: liveItem
        controller.pause()
        scope.launch {
            val ok = sendToRenderer(dlna, device, ep, { graph.lanIp() }, graph.liveHlsProxy)
            if (ok) {
                dlnaState.markActive(device)
            } else {
                // The specific reason when we have one (the TV's UPnP error, an unsupported local file, no WiFi
                // address...): "check your WiFi" was what it said for EVERY failure, whatever the cause.
                android.widget.Toast.makeText(
                    context,
                    dlna.lastError ?: "No se pudo castear (revisa el WiFi)",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Audio and subtitles dialog (the tracks carried by the file/stream, via ExoPlayer). The two
    // pieces of data it receives are only to label the tracks that magis delivers without a
    // language; see `spuLabel`.
    AudioAndSubtitlesDialog(
        state = tracksState,
        isMagis = PlayerSource.kindFor(episodeId) == SourceKind.MAGIS,
        declaredLanguages = webExtras?.subtitles?.map { it.lang }.orEmpty(),
    )
}

@Composable
private fun MarkerEditor(
    mode: MarkMode,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().systemBarsPadding().padding(16.dp),
        color = ArkivSurface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (mode == MarkMode.INTRO) "Marca el FIN del intro" else "Marca el INICIO del outro",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Muévete con el slider hasta la posición exacta y guarda.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            // Same deferral as the player's bar: while dragging, only this local fraction moves
            // (and the clock up here, which would otherwise stay at the old position), and the
            // seek fires ONCE on release. It used to fire a real seek on every step of the finger,
            // which is exactly what makes marking unusable over a source that goes over the network.
            var drag by remember { mutableStateOf<Float?>(null) }
            val displayedPosition = drag?.let { (it * durationMs).toLong() } ?: positionMs
            Text(
                "${formatDuration(displayedPosition)} / ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.titleLarge,
                color = ArkivRed,
            )
            Slider(
                value = drag ?: (if (durationMs > 0) positionMs.toFloat() / durationMs else 0f),
                onValueChange = { v -> drag = v },
                onValueChangeFinished = {
                    drag?.let { onSeek((it * durationMs).toLong()) }
                    drag = null
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Cancelar") }
                Button(onClick = onSave, modifier = Modifier.padding(start = 8.dp)) { Text("Guardar") }
            }
        }
    }
}

/**
 * Overlay transport row button. On receiving focus it inverts (white circle + black icon, same as
 * [SkipButton]) so that from the couch it's obvious at a glance which one is selected: the only
 * indicator there used to be was Material's ripple, invisible from 3 meters away -- without
 * knowing where focus was, D-pad navigation felt erratic.
 * On the phone nothing takes focus in touch mode, so it looks the same as before.
 */
@Composable
private fun TvTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    tint: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color.Transparent, CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Button + menu to hand-correct the current chapter's times.
 *
 * It's the ONLY entry point reachable on the TV: it lives in the overlay's icon row, i.e. inside
 * the screen's focus system (`PlayerFoco.kt`), and is used with the D-pad without leaving
 * playback. On the phone it goes in the same bottom row, next to subtitles.
 */
@Composable
private fun ChapterMarkersMenu(
    state: MarkersState,
    isTv: Boolean,
    onEndOfOpening: () -> Unit,
    onStartOfEnding: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box {
        if (isTv) {
            TvTransportButton(
                icon = Icons.Default.Tune,
                contentDescription = "Corregir intro y outro",
                onClick = { state.openChapterMenu() },
                iconSize = 24.dp,
                tint = Color.White,
                modifier = modifier,
            )
        } else {
            IconButton(onClick = { state.openChapterMenu() }, modifier = modifier) {
                Icon(Icons.Default.Tune, contentDescription = "Corregir intro y outro", tint = Color.White)
            }
        }
        DropdownMenu(
            expanded = state.chapterMenuOpen,
            onDismissRequest = { state.closeChapterMenu() },
        ) {
            DropdownMenuItem(text = { Text("El opening termina aquí") }, onClick = onEndOfOpening)
            DropdownMenuItem(text = { Text("El ending empieza aquí") }, onClick = onStartOfEnding)
            DropdownMenuItem(text = { Text("Este capítulo no tiene") }, onClick = onRemove)
        }
    }
}

@Composable
private fun SkipButton(
    text: String,
    icon: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // On TV this button starts out focused (see SkipButtonFocus), so it has to LOOK focused:
    // otherwise the red border on the rest of the controls disappears from the screen and it's
    // unclear who OK will reach.
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        border = if (focused) BorderStroke(2.dp, ArkivRed) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Color.White else Color.White.copy(alpha = 0.92f),
            contentColor = Color.Black,
        ),
    ) {
        Text(text)
        if (icon) Icon(Icons.Default.SkipNext, contentDescription = null)
    }
}
