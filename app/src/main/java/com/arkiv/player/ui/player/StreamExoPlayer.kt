package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.SubtitleView
import com.arkiv.player.playback.withAudioFocus
import com.arkiv.player.playback.LiveErrorKind
import com.arkiv.player.playback.MpegTs
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.TsDurationProbe
import com.arkiv.player.playback.ValidatedNetwork
import com.arkiv.player.playback.VodSyncMonitor
import com.arkiv.player.playback.VodSyncStats
import com.arkiv.player.playback.fallbackRenderers
import com.arkiv.player.ui.rememberGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "StreamExo"

/**
 * How many bytes at each end of a progressive MPEG-TS ExoPlayer scans for a PCR.
 *
 * TS carries no duration in a header: ExoPlayer reads the first PCR and the last one and subtracts,
 * and only when it gets a duration does it build a seek map. media3's default window is 112 800
 * bytes (600 packets). Some Magis titles end their video well before their audio -- measured, a
 * movie with three audio tracks whose video stops 224 096 bytes before the end of the file -- and
 * since the PCR rides on the video, the default window at the tail held none. The read then gave up
 * without a word: `dur = TIME_UNSET`, `seekable = false`, and every seek restarted the film from
 * byte 0 while the bar (range 0..1 without a duration) sat full.
 *
 * As wide as [ArchiveCacheProxy]'s hot tail and no wider: that tail is what the proxy answers from
 * memory, and a window reaching past it would send ExoPlayer's first tail read to a CDN that takes
 * 0.2 s to 20 s per range. A whole number of packets, for the same reason the default is.
 */
internal const val STREAM_TS_SEARCH_BYTES = (TsDurationProbe.PROBE_BYTES / MpegTs.PACKET) * MpegTs.PACKET

/**
 * What a [StreamExoPlayer] built for one stream, kept around ([remember]ed) so a later audio-track
 * fallback (see [fallbackAudioTracks]) can rebuild the merged source on the SAME player -- without
 * this, retrying would need the whole `httpFactory`/`mediaSourceFactory` construction repeated.
 */
private class PreparedSource(
    val player: ExoPlayer,
    val mediaItem: MediaItem,
    val mediaSourceFactory: DefaultMediaSourceFactory,
    /** Set by [PluginWidevine.sessionManagerProvider] (playback thread) when the device would not run Widevine at L3; read by `onPlayerError` to tag the report. */
    val drmSoftwareLevelRefused: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
)

/**
 * A stream's separately-hosted audio track as its own media item: a CLEAR one, never with a
 * [MediaItem.DrmConfiguration], even when the video it is merged with is Widevine-protected. The
 * item then gets [androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED] from
 * [PluginWidevine.sessionManagerProvider] -- no license request, no session -- which is right for
 * a plain audio file and is what the guide asks a plugin to serve next to a protected video: an
 * encrypted side file fails the playback as a DRM error instead.
 */
private fun clearAudioItem(track: ResolvedAudioTrack): MediaItem = MediaItem.Builder().setUri(Uri.parse(track.url)).build()

/**
 * The container MIME as media3 must receive it, or null to let the player sniff.
 *
 * media3 picks HLS/DASH/SmoothStreaming by comparing the MIME with its exact constants
 * (`application/x-mpegURL`, case included), and any other MIME makes it ignore the URL's
 * `.m3u8` and play the manifest as a progressive file -- which no extractor reads. The IANA name
 * `application/vnd.apple.mpegurl`, the one the plugin guide shows, is such a MIME, so every HLS
 * alias is mapped to media3's spelling here, and DASH/SS are matched case-insensitively.
 */
internal fun exoMimeType(mime: String?): String? {
    val m = mime?.trim().orEmpty()
    if (m.isEmpty()) return null
    return when (m.lowercase()) {
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
        "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
        MimeTypes.APPLICATION_MPD -> MimeTypes.APPLICATION_MPD
        MimeTypes.APPLICATION_SS -> MimeTypes.APPLICATION_SS
        else -> m
    }
}

/**
 * Sets [tracks] on [prepared]'s player: merged into the video via [MergingMediaSource] when there
 * are any, the plain [PreparedSource.mediaItem] otherwise -- unchanged from before audio tracks
 * existed, no merge at all. Used both for the stream's initial setup and, with a shorter [tracks],
 * for [fallbackAudioTracks]'s retry after a merged track turns out unusable.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applyAudioTracks(
    prepared: PreparedSource,
    tracks: List<ResolvedAudioTrack>,
    startPositionMs: Long,
    // The first prepare starts playing; a retry after a failed audio track keeps whatever the person
    // had chosen, so a track that dies in the background while paused never un-pauses the video.
    playWhenReady: Boolean = true,
) {
    val player = prepared.player
    if (tracks.isEmpty()) {
        player.setMediaItem(prepared.mediaItem)
    } else {
        val audioSources = tracks.map { track -> prepared.mediaSourceFactory.createMediaSource(clearAudioItem(track)) }
        player.setMediaSource(
            MergingMediaSource(prepared.mediaSourceFactory.createMediaSource(prepared.mediaItem), *audioSources.toTypedArray()),
        )
    }
    player.prepare()
    if (startPositionMs > 0L) player.seekTo(startPositionMs)
    player.playWhenReady = playWhenReady
    Log.i(TAG, "ExoPlayer prepared · seekTo=$startPositionMs audioTracks=${tracks.size}")
}

/**
 * Plays a Magis VOD stream or a plugin stream using ExoPlayer.
 *
 * Magis's URL already arrives proxied by [archiveCacheProxy] (http://127.0.0.1:...), which injects
 * the CDN's authentication headers transparently. A plugin's URL is played directly, with the
 * plugin's headers on the data source ([requestHeaders]) and every request host-gated ([http]).
 * ExoPlayer downloads either as plain HTTP, and [DefaultMediaSourceFactory] auto-detects HLS, DASH or progressive (MP4/TS) based on
 * the content type. For the progress bar and controls it uses the same [PlayerMirror] VLC used to.
 *
 * Uses [TextureView] directly so [onTextureViewReady] exposes the surface and `captureFrame`
 * works the same way it did with VLC. The aspect ratio is kept in sync by listening to
 * [Player.Listener.onVideoSizeChanged]: in portrait the video stays centered in landscape format.
 *
 * The portal's or plugin's external subtitles are passed as [subtitleConfigs] and ExoPlayer loads
 * them automatically; the overlaid [SubtitleView] renders them on screen. Detected audio and
 * subtitle tracks are reported via [onTracksChanged] so [TracksState] can expose them in the menu.
 *
 * A plugin's Widevine stream ([drm]) plays on this same [TextureView], at security level L3 so no
 * secure decoder is ever asked for -- and only once the device confirms L3, else it fails closed:
 * see [PluginWidevine] for why, and for where the license goes. Its side [audioTracks] stay clear
 * ([clearAudioItem]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun StreamExoPlayer(
    mediaUrl: String,
    mirror: PlayerMirror,
    startPositionMs: Long = 0L,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList(),
    /**
     * The stream's own separately-hosted audio tracks (apiVersion 1, optional plugin field): each
     * is merged into the video source as its own [MergingMediaSource] child, so the container's
     * embedded audio (if any) and these appear together in [onTracksChanged] and the existing
     * audio menu ([TracksState]) offers and auto-selects them exactly like any other track. Empty
     * plays exactly as before this field existed: the plain media item is set directly, no merge.
     */
    audioTracks: List<ResolvedAudioTrack> = emptyList(),
    /**
     * A plugin's Widevine license (apiVersion 2, the `drm` capability), or null for a clear stream
     * -- Magis, and every stream before the capability existed. Goes on the media item as its
     * [MediaItem.DrmConfiguration], and the license request goes through [http]'s host gate like
     * every other request of the stream, but with only its `licenseHeaders` -- never
     * [requestHeaders] (see [pluginHttpFactories], [PluginWidevine]). Null leaves the player
     * byte-for-byte as it was.
     */
    drm: ResolvedDrm? = null,
    /**
     * An own M3U channel's ClearKey key (a `#KODIPROP:inputstream.adaptive.license_key`, see
     * [com.arkiv.player.data.live.M3uEntry]), or null for every other source. Goes on the media
     * item as [MediaItem.DrmConfiguration] like [drm], but the key never reaches the network: see
     * [PluginClearKey]. Mutually exclusive with [drm] in practice -- a stream carries one DRM shape
     * or the other, never both.
     */
    clearKey: ResolvedClearKey? = null,
    /**
     * Headers for every request of this stream (plugins). Empty for Magis, whose headers travel
     * inside the local proxy's URL. Set on the data source, not through `archiveCacheProxy`: that
     * proxy serves ONE URL's bytes, and an HLS/DASH manifest's relative segments would resolve
     * against 127.0.0.1 and 404. Also reaches this stream's subtitle requests, but never a
     * plugin's Widevine license request (see [pluginHttpFactories]).
     */
    requestHeaders: Map<String, String> = emptyMap(),
    /** The data source: [StreamHttp.Default] for Magis, host-gated OkHttp for plugins. */
    http: StreamHttp = StreamHttp.Default,
    /** Container MIME when the source knows it (e.g. `application/x-mpegURL`); null = sniff. */
    mimeType: String? = null,
    /** Prefix of the Sentry tag in `onPlayerError`: `"magis"` or `"plugin"`. */
    crashTag: String = "magis",
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    /**
     * Non-null for a live channel (a plugin's): every player error goes here instead of [onError],
     * with its [LiveErrorKind], and the answer says what this player does about it -- rejoin the
     * live edge in place, wait for a rebuild with a freshly resolved Stream, or nothing more (the
     * caller already warned the person). See `PlayerViewModel.onPluginLiveError`. Null = VOD, where
     * an error is an error.
     */
    onLiveError: ((LiveErrorKind, String) -> PluginLiveRecovery)? = null,
    /**
     * A plugin stream's request was refused only because its host is undeclared and askable
     * ([StreamHttp.PluginGated.pluginId] set): called with that host and where playback was, instead
     * of any other error route (see `PlayerViewModel.onPluginHostRefused`). Null: such a refusal is
     * an error like any other, as before.
     */
    onUndeclaredHost: ((host: String, positionMs: Long) -> Unit)? = null,
    /**
     * A VOD's network recovery wants the stream resolved again (its second attempt, see
     * [VodNetworkRecovery.step]): called with where playback was and whether it was playing; true
     * when the caller took it (this player is then rebuilt from a fresh Stream), false to re-prepare
     * in place instead. Null: nothing to resolve again, every attempt re-prepares.
     */
    onNetworkReResolve: ((positionMs: Long, playWhenReady: Boolean, reprepare: () -> Unit) -> Boolean)? = null,
    /**
     * Where a VOD's network recovery shows itself: waiting for the network ("Sin conexión,
     * esperando la red…") and, once it gave up on a network error, the "Reintentar" that reopens
     * this player (also pressed by itself when the network comes back). Null: neither shows.
     */
    networkUi: VodNetworkUi? = null,
    /** Whether the first prepare starts playing. False only for a stream rebuilt by a recovery while paused. */
    startPlaying: Boolean = true,
    /**
     * Every track report, with the side [audioTracks] merged in RIGHT NOW: the list starts as
     * [audioTracks] and shrinks when [fallbackAudioTracks] drops a failing one and the source is
     * rebuilt, so the audio menu must be labelled against this one, never the Stream's original.
     */
    onTracksChanged: ((Tracks, List<ResolvedAudioTrack>) -> Unit)? = null,
    onFirstFrame: (Boolean) -> Unit = {},
    /**
     * The episode reached its end.
     *
     * Magis plays here and not on the service player, and the screen's own end-of-episode listener
     * deliberately stays quiet while an ExoPlayer is active -- its STATE_ENDED would belong to a
     * local player holding nothing. That left NOBODY watching for the end of a Magis episode, so
     * it simply stopped at the last frame and the next one had to be started by hand. The comment
     * excusing it said "the ExoPlayer handles its own end"; it never did.
     */
    onChapterEnd: () -> Unit = {},
    zoom: Float = 1f,
    isTv: Boolean = false,
) {
    val context = LocalContext.current
    val graph = rememberGraph()
    // While casting the TV plays and this player sits paused: its last cue stayed frozen on the
    // phone (2026-10-01), so the subtitles are not drawn at all until the cast ends.
    val castingState = rememberCastingState(graph)
    val castingNow by castingState
    // A network recovery waits for the app to be in front (see NETWORK_RETRY in onPlayerError).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val recoveryScope = androidx.compose.runtime.rememberCoroutineScope()
    val subtitleStyle by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

    val prepared = remember(mediaUrl, subtitleConfigs, requestHeaders, mimeType, http, audioTracks, drm, clearKey) {
        // Host and path only: a signed URL's query (Signature=…, tokens) never reaches the log.
        val shownUrl = Uri.parse(mediaUrl).let { "${it.scheme}://${it.host}${it.path.orEmpty().take(60)}" }
        Log.i(
            TAG,
            "Creating ExoPlayer · url=$shownUrl startMs=$startPositionMs subs=${subtitleConfigs.size} " +
                "audioTracks=${audioTracks.size} drm=${drm != null} clearKey=${clearKey != null}",
        )
        val pluginFactories: PluginHttpFactories? = (http as? StreamHttp.PluginGated)?.let {
            // Under liveStreamHosts "any", side-loaded subtitles and audio tracks stay strict on every hop
            // (under the broad video permission they don't: it covers them).
            val sideUrls = strictSideUrls(it.hosts, subtitleConfigs.map { c -> c.uri.toString() }, audioTracks.map { a -> a.url })
            val streamClient = graph.pluginStreamClient(it.hosts, it.xuper, sideUrls, askAboutFor = it.pluginId)
            // Never askable: a license redirect to a new host would carry the plugin's licenseHeaders
            // (its auth) there, and a DRM session's failure takes its own route (DRM_FINAL). Never
            // relaxed either: live "any" and broad video both stop short of the license.
            val licenseClient = when {
                it.hosts.anyPublicStreamHost -> graph.pluginStreamClient(licenseHostsFor(it.hosts), it.xuper)
                it.pluginId != null -> graph.pluginStreamClient(it.hosts, it.xuper)
                else -> streamClient
            }
            pluginHttpFactories(streamClient, requestHeaders, drm?.licenseHeaders.orEmpty(), licenseClient)
        }
        val httpFactory: DataSource.Factory = when (http) {
            StreamHttp.Default -> DefaultHttpDataSource.Factory()
                .setUserAgent(requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "okhttp/4.12.0")
                .setDefaultRequestProperties(requestHeaders.filterKeys { !it.equals("User-Agent", true) })
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
            // Every request this stream makes — manifest, variants, segments, keys, subtitles and
            // each redirect hop — is gated to the approved hosts before it leaves the device.
            is StreamHttp.PluginGated -> pluginFactories!!.stream
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setSubtitleConfigurations(subtitleConfigs)
            .apply { exoMimeType(mimeType)?.let { setMimeType(it) } }
            .apply { drm?.let { setDrmConfiguration(PluginWidevine.drmConfiguration(it)) } }
            .apply { clearKey?.let { setDrmConfiguration(PluginClearKey.drmConfiguration()) } }
            .build()

        // Built once and reused for the video AND every audio track below: the same [httpFactory]
        // backs all of them, so the stream's headers reach the audio requests too, exactly as they
        // reach the video's and the subtitles'.
        // VOD: a `.ts` whose last PCR lies before that window still gets its duration and seeking
        // (see TsTailPcrExtractor), searched through this same httpFactory -- same gate, same headers.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            httpFactory,
            com.arkiv.player.playback.streamExtractorsFactory(live = onLiveError != null, tsSearchBytes = STREAM_TS_SEARCH_BYTES),
        )
        // Only with a license to fetch: the factory's default provider would request it through its
        // own plain DefaultHttpDataSource, outside the host gate. With [PluginHttpFactories.license]
        // instead, the license request meets the same gate as every segment but carries only the
        // `licenseHeaders`, never the Stream's `headers` (see [pluginHttpFactories]). The provider
        // serves the video item's DRM block only; the merged audio items ([clearAudioItem]) have
        // none and get no session.
        val drmSoftwareLevelRefused = java.util.concurrent.atomic.AtomicBoolean(false)
        if (drm != null) {
            mediaSourceFactory.setDrmSessionManagerProvider(
                PluginWidevine.sessionManagerProvider(pluginFactories?.license ?: httpFactory) { drmSoftwareLevelRefused.set(true) },
            )
        } else if (clearKey != null) {
            // No network at all: the key travels with the M3U entry, never fetched. A malformed
            // key (see PluginClearKey.responseJson) leaves the provider null, same as no ClearKey.
            PluginClearKey.sessionManagerProvider(clearKey.keyId, clearKey.key)?.let {
                mediaSourceFactory.setDrmSessionManagerProvider(it)
            }
        }

        // Magis's CDN delivers at 70–230 KB/s and its files carry 8 badly interleaved audio
        // tracks: the video lives in one zone and the audio 13 MB away, so the player jumps
        // between the two and each jump costs between 1.6 s and 3.9 s of waiting. With the
        // factory buffer —50 s ceiling and 2.5 s to start— it runs dry every two or three
        // seconds and the picture stutters, so it's given ample margin ahead.
        //
        // But only as far as it fits on a phone. Tested at 300 s and a 96 MB ceiling and it was
        // worse than the original problem: at 236 KB/s bitrate that's ~70 MB retained, the heap
        // went from 107 MB to 142 MB, the GC looped, and the picture froze every 15 s like
        // clockwork. A 60 s ceiling is about 14 MB, which comfortably covers the slowest jump
        // measured. What accumulates BEFORE resuming after a cut is 4 s and not 8: with this CDN
        // those extra seconds cost dearly. Measured on the Fire Stick with the source at
        // 36 KB/s —a sixth of what the video asks for— a rebuffer cost 85 s of waiting, because
        // gathering 8 s of content at that rate is almost 2 MB. At 4 s the wait is cut in half and
        // there's still cushion for a normal hiccup.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setTargetBufferBytes(24 * 1024 * 1024)
            // Sends duration and not size: with 8 audio tracks the byte ceiling is reached well
            // before the seconds of video needed to cover a jump.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context, fallbackRenderers(context))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .withAudioFocus()
            .build()
        val built = PreparedSource(player, mediaItem, mediaSourceFactory, drmSoftwareLevelRefused)
        // Unchanged from before audio tracks existed when [audioTracks] is empty: same mediaItem,
        // same setMediaItem call, no merge at all (see [applyAudioTracks]).
        applyAudioTracks(built, audioTracks, startPositionMs, playWhenReady = startPlaying)
        built
    }
    val exoPlayer = prepared.player
    // The tracks actually still merged in for THIS player: starts as [audioTracks] and only ever
    // shrinks, when [fallbackAudioTracks] blames one (or all) of them for a player error -- never
    // restored within the same playback, so a track already found unusable is not retried.
    var activeAudioTracks by remember(prepared) { mutableStateOf(audioTracks) }
    // Re-prepares already spent on a stuck VOD player (see PlayerErrorRoute.STUCK_RETRY); per prepared source.
    var stuckRetries by remember(prepared) { mutableStateOf(0) }
    // The VOD network recovery's state, per prepared source (see [VodNetworkRecovery]): whether this
    // stream ever reached READY (a stream that never opened is an error at once), the attempts spent
    // since it was last READY, and one deferred until the cast ends.
    var everReady by remember(prepared) { mutableStateOf(false) }
    var networkRetries by remember(prepared) { mutableStateOf(0) }
    val networkBudget = remember(prepared) { NetworkRecoveryBudget() }
    var networkRetryAfterCast by remember(prepared) { mutableStateOf(false) }
    // How long this stream's recoveries already waited for a network since it was last READY.
    var offlineWaitedMs by remember(prepared) { mutableStateOf(0L) }

    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }
    // Read inside the layout listener below, which is built once (`remember`) and outlives every
    // recomposition: a plain `zoom` capture would freeze at whatever it was on that first build.
    val currentZoom = rememberUpdatedState(zoom)

    val textureView = remember(exoPlayer) {
        TextureView(context).apply {
            // No opacity, so whatever has no picture lets the background show through. On its own
            // it did NOT remove the green stripe —tested— but it's correct for a view that doesn't
            // fill its slot, and it costs nothing.
            isOpaque = false
            // Where it ends up placed. This uncovered that the view was shrinking halfway through
            // (1920x1080 on mount, 1920x800 once the video's ratio arrives), and stays here in case
            // some device does something odd with the size again.
            //
            // Also re-applies the aspect transform on any real size change (a rotation, chiefly):
            // `update`'s `fitAspect` call only re-runs on recomposition, and neither `videoAspectRatio`
            // nor `zoom` change just because Android relaid out the view at its new fillMaxSize()
            // bounds -- without this, the picture stayed squashed with the PREVIOUS orientation's
            // transform until something unrelated forced a recomposition.
            addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                Log.i(TAG, "TextureView placed at [$l,$t]-[$r,$b] · ${r - l}x${b - t}")
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    (v as TextureView).fitAspect(videoAspectRatio, currentZoom.value)
                }
            }
        }
    }
    val subtitleView = remember(exoPlayer) { SubtitleView(context) }
    LaunchedEffect(subtitleView, subtitleStyle, isTv) {
        subtitleView.applyArkivSubtitleStyle(subtitleStyle, isTv)
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.setVideoTextureView(textureView)
        onPlayerReady(exoPlayer)
        onTextureViewReady(textureView)
        mirror.syncTransport(
            buffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            playing = exoPlayer.isPlaying,
            wantsToPlay = exoPlayer.playWhenReady,
        )
        Log.i(TAG, "DisposableEffect hooked · state=${exoPlayer.playbackState} isPlaying=${exoPlayer.isPlaying}")

        // A film (not a plugin's live channel) is watched for the signs of out-of-sync audio: one report at most, and
        // a device sends one a day. See [VodSyncMonitor].
        val syncMonitor = if (onLiveError == null) {
            VodSyncMonitor(
                player = exoPlayer,
                context = context,
                sourceTag = crashTag,
                externalAudioTracks = { activeAudioTracks.size },
                mayReport = { VodSyncStats.mayReport(System.currentTimeMillis(), graph.settings.avSyncReportedAtMs) },
                onReported = { graph.settings.avSyncReportedAtMs = System.currentTimeMillis() },
            ).also { exoPlayer.addAnalyticsListener(it) }
        } else null

        var lastReadyDuration = Long.MIN_VALUE
        // The pending network recovery attempt (waiting for the app to come back, or its back-off).
        var networkRetryJob: kotlinx.coroutines.Job? = null
        val listener = object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.height > 0)
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                else 0f
                Log.i(TAG, "onVideoSizeChanged · ${videoSize.width}x${videoSize.height} sar=${videoSize.pixelWidthHeightRatio} → ratio=$ratio")
                if (ratio > 0f) videoAspectRatio = ratio
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val text  = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

                Log.i(TAG, "onTracksChanged · video=${video.size} groups / audio=${audio.size} groups / subs=${text.size} groups")

                audio.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  audio[$gi][$ti] lang=${fmt.language} label=${fmt.label} codec=${fmt.sampleMimeType} ch=${fmt.channelCount} selected=${group.isTrackSelected(ti)}")
                    }
                }
                text.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  subs[$gi][$ti] lang=${fmt.language} label=${fmt.label} mime=${fmt.sampleMimeType} selected=${group.isTrackSelected(ti)}")
                    }
                }

                onTracksChanged?.invoke(tracks, activeAudioTracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView.setCues(stackOverlappingCues(cueGroup.cues))
            }

            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE     -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY    -> "READY"
                    Player.STATE_ENDED    -> "ENDED"
                    else                  -> "?"
                }
                Log.i(TAG, "onPlaybackStateChanged → $name · isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms dur=${exoPlayer.duration}ms")
                mirror.updateBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    everReady = true
                    // The connection is back: a later loss (the next pause in the background) gets its full attempts.
                    if (networkRetries > 0) {
                        Log.i(TAG, "network recovery: READY again after $networkRetries attempt(s)")
                        networkRetries = 0
                    }
                    offlineWaitedMs = 0L
                }
                // Progress diagnostics: once per READY with a new duration (not on every rebuffer).
                if (state == Player.STATE_READY && exoPlayer.duration != lastReadyDuration) {
                    lastReadyDuration = exoPlayer.duration
                    com.arkiv.player.ui.ProgressDiagnostics.playerReady(crashTag, mediaUrl, exoPlayer)
                }
                if (state == Player.STATE_ENDED) {
                    Log.w(TAG, "episode ended at ${exoPlayer.currentPosition}ms of ${exoPlayer.duration}ms")
                    onChapterEnd()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.i(TAG, "onIsPlayingChanged → playing=$playing · pos=${exoPlayer.currentPosition}ms playWhenReady=${exoPlayer.playWhenReady}")
                mirror.updatePlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "onPlayWhenReadyChanged → playWhenReady=$playWhenReady reason=$reason · isPlaying=${exoPlayer.isPlaying}")
                mirror.updateWantsToPlay(playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                onFirstFrame(true)
            }

            /**
             * A live channel's error, decided by [onLiveError] (never an error dialog on the first
             * hiccup, same as Magis live): only the in-place recovery is this player's to do; a
             * re-resolve rebuilds this player from outside, and a give-up was already shown.
             */
            fun recoverLive(kind: LiveErrorKind, msg: String, error: PlaybackException) {
                when (onLiveError!!(kind, msg)) {
                    PluginLiveRecovery.REJOIN_EDGE -> {
                        Log.w(TAG, "live $kind ($msg) -> seek to the live edge and prepare again")
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                    PluginLiveRecovery.RE_RESOLVE -> Log.w(TAG, "live $kind ($msg) -> the channel is resolved again")
                    PluginLiveRecovery.GIVE_UP -> {
                        Log.e(TAG, "live $kind gave up · errorCode=${error.errorCode} msg=$msg", error)
                        // Only the definitive failure reaches Sentry: three hiccups in a row are one report, not three.
                        com.arkiv.player.crash.Crash.report(error, "$crashTag-live-playback-${androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode)}", pluginExtras(http))
                    }
                }
            }

            /**
             * A VOD's network error is on screen: "Reintentar" reopens this player where it was,
             * with its recovery's attempts and offline wait fresh; and it is pressed by itself the
             * first time the network comes back ([NetworkReturn]).
             */
            fun offerNetworkRetry(ui: VodNetworkUi) {
                networkRetryJob?.cancel()
                ui.offerRetry(prepared) {
                    networkRetryJob?.cancel()
                    networkRetries = 0
                    offlineWaitedMs = 0L
                    Log.w(TAG, "network error: reopening at ${exoPlayer.currentPosition.coerceAtLeast(0L)}ms")
                    if (exoPlayer.playerError != null) runCatching { exoPlayer.prepare() }
                }
                networkRetryJob = recoveryScope.launch {
                    val back = NetworkReturn()
                    ValidatedNetwork.states(context).first { back.onState(it) }
                    lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    Log.w(TAG, "network error: the network is back -> retrying once by itself")
                    ui.retry()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Error de reproducción (${error.errorCode})"
                val liveKind = if (onLiveError != null) liveErrorKind(error) else null
                val undeclared = if (onUndeclaredHost != null) undeclaredPlaybackHost(error) else null
                val networkRetry = undeclared == null && VodNetworkRecovery.shouldRetry(
                    error.errorCode, live = onLiveError != null, everReady = everReady, retriesSpent = networkRetries,
                ) && networkBudget.tryTake(System.currentTimeMillis())
                val route = playerErrorRoute(
                    networkRetry = networkRetry,
                    live = liveKind != null,
                    liveInPlace = liveKind?.recoverableInPlace == true,
                    drmError = PluginWidevine.isDrmError(error.errorCode),
                    drmSoftwareRefused = prepared.drmSoftwareLevelRefused.get(),
                    audioTracksActive = activeAudioTracks.isNotEmpty(),
                    askableHost = undeclared != null,
                    stuck = isStuckPlayer(error),
                    stuckRetriesLeft = MAX_STUCK_RETRIES - stuckRetries,
                )
                when (route) {
                    // The player stays stopped on this error while the person decides; a "yes"
                    // rebuilds it (new hosts = new StreamHttp) at this same position.
                    PlayerErrorRoute.ASK_HOST -> {
                        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                        Log.w(TAG, "request refused: ${undeclared!!.host} is not declared by ${undeclared.pluginId} -> asking at ${position}ms")
                        onUndeclaredHost!!(undeclared.host, position)
                    }
                    // The player's own watchdog found it stuck (playing with no progress, or buffering and not loading), on a VOD:
                    // rebuild the source at the same position, up to twice, before the person sees an error. Most of these follow
                    // an audio sink discontinuity on a TV box, after which the AudioTrack is dead until the renderer is re-enabled.
                    PlayerErrorRoute.STUCK_RETRY -> {
                        stuckRetries++
                        val at = exoPlayer.currentPosition.coerceAtLeast(0L)
                        Log.w(TAG, "player stuck ($msg) → re-preparing at ${at}ms, retry $stuckRetries/$MAX_STUCK_RETRIES")
                        applyAudioTracks(prepared, activeAudioTracks, at, playWhenReady = exoPlayer.playWhenReady)
                    }
                    // A VOD that had played lost its connection: recovered without an error. See [VodNetworkRecovery].
                    PlayerErrorRoute.NETWORK_RETRY -> {
                        networkRetries++
                        val attempt = networkRetries
                        // While casting this player sits paused on purpose and the TV plays: nothing
                        // is reopened now (a re-resolve would even recast). The cast's end resumes it
                        // (seek + play, or stays paused after "stop"); the LaunchedEffect below
                        // re-prepares it then.
                        if (castingState.value) {
                            Log.w(TAG, "network error while casting ($msg) -> re-prepared when the cast ends")
                            networkRetryAfterCast = true
                            return
                        }
                        networkRetryJob?.cancel()
                        networkRetryJob = recoveryScope.launch {
                            // In the background the network may be cut for this app: an attempt
                            // there would only burn the budget. The person is away anyway.
                            lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                            // No network at all (the Wi-Fi dropped): an attempt now only burns the
                            // budget. Wait for one, bounded, saying so on screen; then try.
                            val waitMs = VodNetworkRecovery.offlineWaitMs(ValidatedNetwork.isUp(context), offlineWaitedMs)
                            if (waitMs > 0) {
                                Log.w(TAG, "network error ($msg) -> no network: waiting up to ${waitMs}ms before attempt $attempt")
                                networkUi?.setWaiting(prepared, true)
                                val start = android.os.SystemClock.elapsedRealtime()
                                try {
                                    val back = withTimeoutOrNull(waitMs) { ValidatedNetwork.states(context).first { it } } != null
                                    Log.w(TAG, "network recovery: " + if (back) "the network is back" else "still no network, trying anyway")
                                } finally {
                                    offlineWaitedMs += android.os.SystemClock.elapsedRealtime() - start
                                    networkUi?.setWaiting(prepared, false)
                                }
                            }
                            delay(VodNetworkRecovery.delayMs(attempt, networkBudget.recent(System.currentTimeMillis())))
                            // Something else already re-prepared it (a seek does not, a new source does).
                            if (exoPlayer.playerError == null) return@launch
                            // Read now, not at the error: the person may have pressed play meanwhile.
                            val at = exoPlayer.currentPosition.coerceAtLeast(0L)
                            val playing = exoPlayer.playWhenReady
                            val step = VodNetworkRecovery.step(attempt, canReResolve = onNetworkReResolve != null)
                            // A resolve that fails (still no network) re-prepares in place instead.
                            val reprepare = { if (exoPlayer.playerError != null) runCatching { exoPlayer.prepare() } }
                            if (step == NetworkRetryStep.RE_RESOLVE && onNetworkReResolve!!(at, playing) { reprepare() }) {
                                Log.w(TAG, "network error ($msg) -> attempt $attempt/${VodNetworkRecovery.MAX_RETRIES}: resolving the stream again at ${at}ms playing=$playing")
                                return@launch
                            }
                            Log.w(TAG, "network error ($msg) -> attempt $attempt/${VodNetworkRecovery.MAX_RETRIES}: re-preparing at ${at}ms playing=$playing")
                            // Same item, same position, same play/pause: prepare() after an error reopens the source.
                            exoPlayer.prepare()
                        }
                    }
                    // A live channel's playlist-level error (behind the live window, reset, stuck) is
                    // never an audio track's fault: it is fixed in place before anything is blamed.
                    PlayerErrorRoute.LIVE_IN_PLACE, PlayerErrorRoute.LIVE_CUT -> recoverLive(liveKind!!, msg, error)
                    // A DRM session's failure (license refused or unreachable, no Widevine or no L3 on
                    // the device, key expired) is never an audio track's fault, so it is never retried
                    // without them: final, and said in Spanish. See [playerErrorRoute] for live.
                    PlayerErrorRoute.DRM_FINAL -> {
                        val tag = PluginWidevine.crashTag(crashTag, error.errorCode, prepared.drmSoftwareLevelRefused.get())
                        Log.e(TAG, "onPlayerError DRM errorCode=${error.errorCode} tag=$tag msg=$msg", error)
                        com.arkiv.player.crash.Crash.report(error, tag, pluginExtras(http))
                        onError(PluginWidevine.ERROR_MESSAGE)
                    }
                    // A MergingMediaSource is all-or-nothing (MergingMediaPeriod.maybeThrowPrepareError
                    // propagates the first child's failure and never prepares the rest), so while any of
                    // the stream's own audio tracks are still merged in, ANY error here is presumed
                    // attributable to one of them first: a plugin's audio URL must never take a perfectly
                    // fine video down. Only once there is nothing left to blame (activeAudioTracks empty,
                    // the exact same state a stream with none ever had) does the error reach the person.
                    PlayerErrorRoute.DROP_AUDIO -> {
                        val failureText = playbackFailureText(error)
                        val next = fallbackAudioTracks(activeAudioTracks, failureText)
                        Log.w(TAG, "audio track(s) unusable, retrying without them · ${activeAudioTracks.size} -> ${next.size} · $failureText")
                        activeAudioTracks = next
                        applyAudioTracks(prepared, next, exoPlayer.currentPosition.coerceAtLeast(0L), playWhenReady = exoPlayer.playWhenReady)
                    }
                    PlayerErrorRoute.FINAL -> {
                        // The person reads a short Spanish sentence; ExoPlayer's English and the
                        // technical detail stay here and in the crash report.
                        val video = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
                            ?.takeIf { MimeTypes.isVideo(it.sampleMimeType) } ?: exoPlayer.videoFormat
                        val shown = playerErrorMessage(error, video?.height ?: 0, video?.sampleMimeType)
                        Log.e(
                            TAG,
                            "onPlayerError errorCode=${error.errorCode} (${androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode)}) " +
                                "msg=$msg video=${video?.height ?: 0}p ${video?.sampleMimeType} -> shown \"$shown\"",
                            error,
                        )
                        // Also to Sentry: VOD playback failures (codec init, source, decoder) used to vanish
                        // into Logcat -- this is proactive signal on which content/devices can't play.
                        com.arkiv.player.crash.Crash.report(error, "$crashTag-playback-${androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode)}", pluginExtras(http))
                        onError(shown)
                        if (networkUi != null && VodNetworkRecovery.offersRetry(error.errorCode, live = onLiveError != null)) {
                            offerNetworkRetry(networkUi)
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        val decoderLog = videoDecoderLog(exoPlayer)
        exoPlayer.addAnalyticsListener(decoderLog)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms isPlaying=${exoPlayer.isPlaying}")
            syncMonitor?.let {
                runCatching { it.finish() }
                exoPlayer.removeAnalyticsListener(it)
            }
            networkRetryJob?.cancel()
            networkUi?.reset(prepared)
            exoPlayer.removeListener(listener)
            exoPlayer.removeAnalyticsListener(decoderLog)
            exoPlayer.clearVideoTextureView(textureView)
            exoPlayer.release()
            mirror.resetClock()
            mirror.syncTransport(buffering = false, playing = false, wantsToPlay = false)
            onPlayerReady(null)
            onTextureViewReady(null)
            onFirstFrame(false)
        }
    }

    // A network recovery deferred while casting (see NETWORK_RETRY): once the cast ends, the player
    // the cast handed back (already seeked to the TV's position, playing or not) reopens its source.
    LaunchedEffect(exoPlayer, castingNow) {
        if (castingNow || !networkRetryAfterCast) return@LaunchedEffect
        networkRetryAfterCast = false
        if (exoPlayer.playerError != null) {
            Log.w(TAG, "cast ended -> re-preparing after the network error it had while casting")
            exoPlayer.prepare()
        }
    }

    // Position polling. Also watches for two pathologies the clock alone doesn't reveal:
    //  · the position advances while !isPlaying (transport bug);
    //  · the clock advances but the renderer doesn't produce a single frame — the picture stays
    //    frozen with the bar running. Happens when the network changes underneath: the proxy
    //    abandons its connections to the origin, the response feeding ExoPlayer cuts off mid-
    //    download, and ExoPlayer reads it as a legitimate end of stream. It stays in READY without
    //    requesting more data, the AudioTrack stops, and media3 falls back to its internal clock,
    //    which runs free even though not a single byte arrives.
    //
    //    Measured with the decoder's counters, not the clock: they're the only proof a frame
    //    actually reached the screen. The rescue is staggered because the two causes call for
    //    different remedies: first a seek (cheap, unsticks a stuck decoder), and if the counter is
    //    still pinned, prepare(), the only thing that rebuilds the source and reopens the HTTP
    //    connection — a seek reopens nothing when the player thinks the stream already ended.
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastFrames = -1L
        var frozenSinceMs = 0L
        var lastRescueMs = 0L
        var consecutiveRescues = 0
        var lastMilestoneMs = 0L

        while (true) {
            delay(500)
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            val playing = exoPlayer.isPlaying
            val wantPlay = exoPlayer.playWhenReady
            val state = exoPlayer.playbackState
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L

            if (!playing && !wantPlay && pos != lastPos && lastPos >= 0) {
                Log.w(TAG, "POSITION ADVANCES WHILE PAUSED · pos=$pos lastPos=$lastPos state=$state")
            }

            // The clock is genuinely running (not a seek, not a pause) but no frame came in.
            val clockAdvanced = lastPos >= 0 && pos > lastPos
            val noFrames = lastFrames >= 0 && frames == lastFrames
            val now = SystemClock.elapsedRealtime()

            if (playing && state == Player.STATE_READY && clockAdvanced && noFrames && frames >= 0) {
                if (frozenSinceMs == 0L) {
                    frozenSinceMs = now
                    Log.w(TAG, "VIDEO WITHOUT FRAMES · starts · pos=${pos}ms frames=$frames")
                }
                val frozenMs = now - frozenSinceMs
                // 5 s margin: below that it's indistinguishable from the CDN's normal hiccups,
                // which can last up to 4 s and recover on their own.
                //
                // The rescue attacks the three points where the freeze was measured, from
                // cheapest to costliest, because each one cures a case the previous one doesn't:
                //  1. seekTo — unsticks a stuck decoder. Sometimes it's enough (a recovery was
                //     measured at 190 ms), but in the hard freeze the frame counter stays pinned
                //     at the same number after a perfectly successful seek.
                //  2. prepare() — rebuilds the source and reopens the HTTP connection. Cures it
                //     when the player read the proxy's cut as end of stream and stopped
                //     requesting data.
                //  3. re-hooking the TextureView — the surface stopped draining and the decoder
                //     ran out of output buffers. A run was measured where not even prepare()
                //     moved the counter: neither the source nor the decoder was missing there,
                //     what was missing was somewhere to paint, and only detaching and re-setting
                //     the surface fixed it.
                if (frozenMs >= 5_000 && now - lastRescueMs >= 8_000) {
                    lastRescueMs = now
                    frozenSinceMs = 0L
                    consecutiveRescues++
                    if (consecutiveRescues <= 1) {
                        Log.w(TAG, "VIDEO FROZEN ${frozenMs}ms · rescue 1: prepare() at $pos")
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    } else {
                        Log.w(TAG, "VIDEO FROZEN ${frozenMs}ms · rescue $consecutiveRescues: re-hooking the surface at $pos")
                        exoPlayer.clearVideoTextureView(textureView)
                        exoPlayer.setVideoTextureView(textureView)
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                }
            } else {
                if (frozenSinceMs != 0L) {
                    Log.i(TAG, "VIDEO WITHOUT FRAMES · recovered after ${now - frozenSinceMs}ms · frames=$frames")
                }
                frozenSinceMs = 0L
                // Only counts as recovered if new frames genuinely came in, not from a BUFFERING
                // flash between two frozen stretches.
                if (frames > lastFrames && lastFrames >= 0) consecutiveRescues = 0
            }

            // A milestone every 30 s of wall time: proof the picture keeps coming, or since when not.
            if (now - lastMilestoneMs >= 30_000) {
                lastMilestoneMs = now
                val c = exoPlayer.videoDecoderCounters
                Log.i(TAG, "frames rendered=${c?.renderedOutputBufferCount} dropped=${c?.droppedBufferCount} skipped=${c?.skippedOutputBufferCount} at ${pos}ms state=$state")
            }

            lastPos = pos
            lastFrames = frames

            mirror.readClock(
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // The ratio is applied on the containing Box, NOT on the AndroidView: if the AndroidView's
    // Modifier changed (fillMaxSize → aspectRatio), Compose may briefly reattach the TextureView,
    // destroying its SurfaceTexture and leaving the video black with audio.
    // With a Box wrapper the TextureView always has fillMaxSize() → a stable surface.
    // The TextureView NEVER changes size: it always fills the whole screen and the ratio is
    // achieved by transforming its content (see [fitAspect]).
    //
    // The surrounding Box used to be given the aspect, and that shrank the view halfway through:
    // it was placed at 1920x1080 —the ratio isn't known until the decoder starts— and once
    // onVideoSizeChanged arrived it switched to 1920x800. But its SurfaceTexture had been created
    // at 1080, and the leftover 280 px stayed there with an unused buffer: a GREEN stripe under
    // the video in every widescreen movie. Measured on the Fire Stick with a 2.4:1, and it never
    // showed on 16:9 precisely because there the view already filled the screen and never shrank.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // key(textureView): an AndroidView runs its factory ONCE, so when this composable builds a
        // new player in place (a host approved mid-playback changes [http], see
        // `PlayerViewModel.onPluginHostRefused`) the screen kept showing the OLD TextureView while
        // the new player drew into its own, never attached: sound and no picture, the decoder
        // configured fine (csd 41 bytes) and zero frames, and every "re-hooking the surface" rescue
        // re-hooked the same detached view. Measured on the KALLEY R3 with Castle: frames=0 after
        // the rebuild, picture at once without it. Keyed, a new player gets a new view on screen.
        key(textureView) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { textureView },
                update = { it.fitAspect(videoAspectRatio, zoom) },
            )
        }

        // BLACK BARS ON TOP, covering what's left over from the video.
        //
        // It's a patch and worth knowing that: in a widescreen movie the bottom half of the gap
        // came out GREEN —the surface's unused buffer— and the cause was never found. These were
        // ruled out, each measured on the Fire Stick: the zoom's composition layer, the
        // TextureView's opacity, the view changing size halfway through, and the content
        // transform. With all of those the video landed EXACTLY where it should (measured:
        // y=138..941 for a 2.4:1 on 1080) and the stripe stayed the same. The strangest part is
        // that the TOP bar always came out black and only the bottom one green, with the same
        // surface.
        //
        // So black gets painted on top of both bars. It doesn't fix the buffer, but a widescreen
        // movie's gap has to be black and this way it is.
        // The bars go wherever they belong: top and bottom if the video is WIDER than the screen
        // (a widescreen movie on TV), on the sides if it's NARROWER (a 4:3 on TV, or anything on a
        // phone held upright). Only one of the two branches can happen at a time, and with video
        // in exactly the same format neither one is painted.
        val boxHeight = maxHeight
        val boxWidth = maxWidth
        if (videoAspectRatio > 0f && boxHeight > 0.dp && boxWidth > 0.dp) {
            val screenAspect = boxWidth / boxHeight
            if (videoAspectRatio > screenAspect) {
                val bar = (boxHeight - boxWidth / videoAspectRatio) / 2
                if (bar > 0.dp) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(bar).background(Color.Black))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bar).background(Color.Black))
                }
            } else if (videoAspectRatio < screenAspect) {
                val bar = (boxWidth - boxHeight * videoAspectRatio) / 2
                if (bar > 0.dp) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(bar).background(Color.Black))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(bar).background(Color.Black))
                }
            }
        }

        // Overlaid SubtitleView: renders VTT/SRT cues loaded via SubtitleConfiguration. Keyed for
        // the same reason as the TextureView: a rebuilt player's cues go to its own view.
        // ALWAYS mounted, hidden while casting: taking the AndroidView out and back in handed its
        // factory the same remembered View, still attached to the old holder -- "The specified
        // child already has a parent" when the cast ended with the player open.
        key(subtitleView) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { subtitleView },
                update = { view ->
                    val hidden = castingNow
                    view.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
                    if (hidden) view.setCues(emptyList())
                },
            )
        }
    }
}

/**
 * What the video decoder is given and does, for telling "no picture" apart on a device: the decoder
 * chosen, each input format with its codec-specific data sizes (SPS/PPS for H.264: an empty list
 * means the decoder gets none), the output surface, first frames, seeks and dropped frames. Measured
 * need: Castle's "sound, no picture" on the KALLEY R3 could only be told apart from a decoder fault
 * by these (csd 41 bytes and a detached surface).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun videoDecoderLog(player: ExoPlayer) = object : AnalyticsListener {
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "video decoder $decoderName ready in ${initializationDurationMs}ms")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        Log.i(
            TAG,
            "video format ${format.sampleMimeType} codecs=${format.codecs} ${format.width}x${format.height} " +
                "csd=${format.initializationData.map { it.size }} reuse=${decoderReuseEvaluation?.result}",
        )
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        Log.i(TAG, "first frame on ${output.javaClass.simpleName}@${System.identityHashCode(output).toString(16)} at ${player.currentPosition}ms")
    }

    override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
        Log.i(TAG, "output surface ${width}x$height")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.w(TAG, "dropped $droppedFrames video frames in ${elapsedMs}ms at ${player.currentPosition}ms")
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) Log.i(TAG, "seek ${oldPosition.positionMs}ms → ${newPosition.positionMs}ms")
    }
}

/**
 * Fits the video into the view without distorting it, moving the CONTENT and not the view.
 *
 * ExoPlayer stretches the video to fill the TextureView, so a 2.4:1 movie on a 16:9 screen comes
 * out squashed. Fixed with the surface's matrix: how much overflows on the axis that doesn't fit
 * is calculated and shrunk there, leaving the rest black like any letterbox. Same as what media3's
 * PlayerView does with a TextureView.
 *
 * [zoom] multiplies at the end, so the zoom gesture keeps working on top of the result.
 *
 * `internal` (not `private`): [LiveExoPlayer] reuses it as-is for live's same letterbox.
 */
internal fun TextureView.fitAspect(videoAspect: Float, zoom: Float) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (videoAspect <= 0f || w <= 0f || h <= 0f) return

    val viewAspect = w / h
    // Only the axis that overflows gets SHRUNK: enlarging the other would crop the picture.
    val scaleX = if (videoAspect > viewAspect) 1f else videoAspect / viewAspect
    val scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f

    setTransform(
        android.graphics.Matrix().apply {
            setScale(scaleX * zoom, scaleY * zoom, w / 2f, h / 2f)
        },
    )
}

/**
 * Which HTTP data source a [StreamExoPlayer] stream plays through.
 *
 * Magis keeps [DefaultHttpDataSource] exactly as it was (its URL is the local proxy). A plugin
 * stream plays through OkHttp with the host gate on every request and redirect hop
 * ([com.arkiv.player.data.plugin.PluginStreamHttp]), so what the manifest names can't reach an
 * undeclared host, plain http, an IP literal or the home network.
 */
internal sealed interface StreamHttp {
    data object Default : StreamHttp

    /**
     * [hosts]: the ones the person approved or typed (installed record + settings) — never plugin output.
     * [xuper]: the stream is the recognized Xuper plugin's (`PluginAccess.Ready.xuper`), so the
     * gate also honors `AppGraph`'s one [com.arkiv.player.data.plugin.XuperStreams]; see `AppGraph.pluginStreamClient`.
     */
    data class PluginGated(
        val hosts: com.arkiv.player.data.plugin.EffectiveHosts,
        val xuper: Boolean = false,
        /**
         * The plugin whose undeclared-but-askable hosts the STREAM client reports as
         * [com.arkiv.player.data.plugin.UndeclaredPlaybackHostException] (see `askAboutFor` in
         * [com.arkiv.player.data.plugin.PluginStreamHttp.client]); never the license client. Null
         * refuses exactly as before.
         */
        val pluginId: String? = null,
    ) : StreamHttp
}

/** The gate's askable refusal, wherever ExoPlayer wrapped it (`PlaybackException` > `HttpDataSourceException` > …). */
internal fun undeclaredPlaybackHost(error: Throwable): com.arkiv.player.data.plugin.UndeclaredPlaybackHostException? =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<com.arkiv.player.data.plugin.UndeclaredPlaybackHostException>()
        .firstOrNull()

private const val MAX_CAUSE_DEPTH = 16

/**
 * This plugin item once the person approved a host the player needed: the same item with
 * [declared] as its approved hosts (typed servers, insecure hosts and the live flag untouched) and
 * [positionMs] as where to start, or the live edge (0) for a channel. A new host set is a new
 * [StreamHttp] and so a new player (`StreamExoPlayer` is keyed on it), started where the person was.
 */
internal fun PlayerData.afterHostApproved(
    declared: List<String>,
    positionMs: Long,
    live: Boolean,
    /** The person just granted (or had granted) the broad video permission; a live channel never takes it. */
    anyVideoHost: Boolean = pluginHosts.anyPublicVideoHost,
): PlayerData =
    copy(
        pluginHosts = pluginHosts.copy(declared = declared, anyPublicVideoHost = !live && anyVideoHost),
        startPositionMs = if (live) 0L else positionMs.coerceAtLeast(0L),
    )

/**
 * The data-source factories of a gated plugin stream, both over a host-gated client (so a license
 * request can no more reach an undeclared host, plain http or the home network than a segment can).
 * The same one, except under `liveStreamHosts: "any"`: the license then gets its own STRICT client
 * (the hosts with `anyPublicLiveHost` off), since "any" never covers a DRM license:
 *  - [stream]: the manifest, segments, keys, side audio, subtitles and redirect hops, with the
 *    Stream's `headers` on every request.
 *  - [license]: the Widevine license (and provisioning) request only, bare: none of the Stream's
 *    `headers` -- not its User-Agent, not a CDN `Cookie`/`Authorization`/`Referer`. The
 *    `licenseHeaders` travel on the request itself (`HttpMediaDrmCallback` puts them there from the
 *    media item's DRM block). This is what `docs/plugins/README.md` promises authors: `headers` are
 *    not sent to the license server, and `licenseHeaders` are not sent to the CDN.
 */
internal class PluginHttpFactories(val stream: DataSource.Factory, val license: DataSource.Factory)

/**
 * [PluginHttpFactories] over [client], the plugin's host-gated OkHttp client; the license over
 * [licenseClient], which is [client] unless the stream's hosts are relaxed for a live channel.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun pluginHttpFactories(
    client: okhttp3.Call.Factory,
    requestHeaders: Map<String, String>,
    licenseHeaders: Map<String, String>,
    licenseClient: okhttp3.Call.Factory = client,
): PluginHttpFactories {
    val stream = OkHttpDataSource.Factory(client)
        .setUserAgent(userAgentOf(requestHeaders))
        .setDefaultRequestProperties(requestHeaders.filterKeys { !it.equals("User-Agent", true) })
    // No default request properties: only what the request itself carries (licenseHeaders).
    val license = OkHttpDataSource.Factory(licenseClient).setUserAgent(userAgentOf(licenseHeaders))
    return PluginHttpFactories(stream, license)
}

/** The User-Agent a header set asks for, else the one a plugin stream has always sent. */
private fun userAgentOf(headers: Map<String, String>): String =
    headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "okhttp/4.12.0"

/**
 * The side-loaded subtitle and audio URLs whose whole redirect chain must be gated strictly
 * (`PluginStreamHttp.client`'s `strictOrigins`): all of them under live "any", which never covers a
 * side file; none under the broad video permission, which does (`EffectiveHosts.sideTracks`), nor on
 * an unrelaxed stream, where strict is the only gate there is.
 */
internal fun strictSideUrls(hosts: com.arkiv.player.data.plugin.EffectiveHosts, subtitles: List<String>, audio: List<String>): List<String> =
    if (hosts.sideTracks == hosts) emptyList() else subtitles + audio

/** The hosts a plugin stream's DRM license client is gated to: never relaxed, by live "any" or by broad video. */
internal fun licenseHostsFor(hosts: com.arkiv.player.data.plugin.EffectiveHosts): com.arkiv.player.data.plugin.EffectiveHosts = hosts.strict

/** Only a PLUGIN stream is gated; an empty host list is still gated (it reaches nothing). */
/**
 * A plugin stream's playback report carries which plugin it was (id, version, apiVersion, origin,
 * Nuvio repo/scraper: `PluginTelemetry.describe`, nothing the person typed); empty for Magis and for a
 * plugin stream without its id.
 */
private fun pluginExtras(http: StreamHttp): Map<String, String> =
    (http as? StreamHttp.PluginGated)?.pluginId?.let { runCatching { com.arkiv.player.data.plugin.PluginTelemetry.current.describe(it) }.getOrNull() }.orEmpty()

internal fun streamHttpFor(kind: SourceKind, pluginHosts: com.arkiv.player.data.plugin.EffectiveHosts, xuper: Boolean = false, pluginId: String? = null): StreamHttp =
    if (kind == SourceKind.PLUGIN) StreamHttp.PluginGated(pluginHosts, xuper, pluginId) else StreamHttp.Default

/**
 * A subtitle's type: the `format` the source declared (plugins), else guessed from its path. VTT
 * by default: what magis's portal serves (Magis passes no format, so it keeps the URL guess); the
 * .srt case is there in case some source names one that way with that extension.
 */
internal fun subtitleMimeType(sub: ResolvedSub): String = when {
    sub.format == "srt" -> MimeTypes.APPLICATION_SUBRIP
    sub.format == "vtt" -> MimeTypes.TEXT_VTT
    sub.url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    else -> MimeTypes.TEXT_VTT
}

/**
 * Converts the portal's subtitle list to ExoPlayer's SubtitleConfiguration. Each gets the id of its
 * place in the list (`CastTextTracks.phoneIdOf`), which its track's `Format.id` carries: that is how
 * the cast maps the menu's choice back to the subtitle to turn on on the TV.
 */
internal fun List<ResolvedSub>.toExoSubtitleConfigs(): List<MediaItem.SubtitleConfiguration> =
    mapIndexed { i, sub ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(subtitleMimeType(sub))
            .setLanguage(sub.lang)
            .setId(com.arkiv.player.cast.CastTextTracks.phoneIdOf(i))
            .build()
    }

/**
 * Which of [tracks] to retry with after a player error while all of them were still merged into
 * the video source ([MergingMediaSource] is all-or-nothing: `MergingMediaPeriod.maybeThrowPrepareError`
 * propagates the FIRST child's failure and never prepares the rest, so one bad audio URL -- a 404,
 * a timeout, an unreachable host -- would otherwise fail the whole playback even though the video
 * itself is fine). Looks for exactly one track whose own [ResolvedAudioTrack.url] appears in
 * [failureText] (the failed error's own text, walked through its cause chain -- see
 * `playbackFailureText`) and drops only that one; when none or more than one match -- the failure
 * doesn't name a URL Kino recognizes, or the match is ambiguous -- it drops ALL of them, since a
 * plain video is always safer than guessing wrong and failing again. [tracks] empty is returned
 * unchanged: the caller only retries while there is something left to drop.
 */
internal fun fallbackAudioTracks(tracks: List<ResolvedAudioTrack>, failureText: String): List<ResolvedAudioTrack> {
    if (tracks.isEmpty()) return tracks
    val blamed = tracks.filter { failureText.contains(it.url) }
    return if (blamed.size == 1) tracks - blamed[0] else emptyList()
}

/** [error]'s message, and every cause behind it: where [fallbackAudioTracks] looks for a URL. */
internal fun playbackFailureText(error: Throwable): String =
    generateSequence(error) { it.cause }.joinToString(" | ") { it.toString() }

/** How many times a stuck VOD player is re-prepared in place before its error reaches the person. */
private const val MAX_STUCK_RETRIES = 2
