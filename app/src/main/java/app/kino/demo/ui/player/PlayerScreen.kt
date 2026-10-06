package app.kino.demo.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.kino.demo.data.Film
import app.kino.demo.data.metaLine
import app.kino.demo.ui.formatDuration
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.tv.landingFocus
import app.kino.demo.ui.tv.rememberLandingFocus
import kotlinx.coroutines.delay

/** How far the arrows and the ±10 buttons jump. */
private const val SEEK_STEP_MS = 10_000L

/** "Saltar intro": shown between these positions, on films long enough to have an opening; jumps this far. */
private const val SKIP_FROM_MS = 3_000L
private const val SKIP_UNTIL_MS = 120_000L
private const val SKIP_MIN_DURATION_MS = 10 * 60_000L
private const val SKIP_INTRO_MS = 85_000L

/** The TV's overscan safe zone around the controls. */
private val SAFE_H = 44.dp
private val SAFE_V = 44.dp

/**
 * The player: a plain media3 ExoPlayer under Kino's controls overlay (title, progress bar with the
 * buffer, rewind / play-pause / forward, and on the phone the rotate button).
 */
@Composable
fun PlayerScreen(film: Film, isTv: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(film.videoUrl))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedFraction by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var barFocused by remember { mutableStateOf(false) }
    val controls = rememberControlsState()
    AutoHideEffect(controls, playing)
    val menu = rememberPlayerMenuState()
    var showTracks by remember { mutableStateOf(false) }
    var showMarkers by remember { mutableStateOf(false) }
    var skipUsed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = player.playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && error == null
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) controls.bump()
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "No se pudo reproducir este video."
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Progress is polled: the position moves continuously and has no listener of its own.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) durationMs = player.duration
            bufferedFraction = if (durationMs > 0) player.bufferedPosition.toFloat() / durationMs else 0f
            playing = player.playWhenReady
            delay(500)
        }
    }

    // Leaving the app pauses; coming back leaves it paused with the controls up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                controls.bump()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Full screen while the player is open: system bars hidden, back to normal on leaving.
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        controls.bump()
    }

    fun seekBy(deltaMs: Long) {
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, max))
        positionMs = player.currentPosition
        controls.bump()
    }

    // The video surface takes the D-pad while the controls are hidden (and is the screen's landing).
    val surface = rememberLandingFocus()
    var playPauseFocused by remember { mutableStateOf(false) }
    val barFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val tracksFocus = remember { FocusRequester() }
    val markersFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }

    // "Saltar intro" during the opening minutes; one press and it is gone for this film.
    val showSkip = !skipUsed && error == null && durationMs > SKIP_MIN_DURATION_MS && positionMs in SKIP_FROM_MS..SKIP_UNTIL_MS
    fun skipIntro() {
        skipUsed = true
        seekBy(SKIP_INTRO_MS)
        if (isTv) controls.hide()
    }

    // TV: the overlay's play/pause takes focus as it opens; the video takes it back as it closes.
    // Repeated until it holds, since a request made while the overlay is still appearing can be dropped.
    // With the controls hidden, "Saltar intro" holds the focus while it shows, so OK skips.
    LaunchedEffect(controls.visible, isTv, showSkip) {
        if (!isTv) return@LaunchedEffect
        repeat(40) {
            val held = when {
                controls.visible -> playPauseFocused
                showSkip -> skipFocused
                else -> surface.focused
            }
            if (held) return@LaunchedEffect
            runCatching {
                when {
                    controls.visible -> playPauseFocus.requestFocus()
                    showSkip -> skipFocus.requestFocus()
                    else -> surface.request()
                }
            }
            delay(50)
        }
    }

    BackHandler {
        if (controls.visible && playing && error == null) controls.hide() else onBack()
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .landingFocus(surface)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || controls.visible) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.Spacebar -> { togglePlayPause(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seekBy(SEEK_STEP_MS); true }
                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> { controls.bump(); true }
                    else -> false
                }
            }
            .focusable()
            // A tap toggles the controls; no `clickable`, which would add a second focus target here.
            .pointerInput(Unit) { detectTapGestures { controls.toggle() } },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    this.player = player
                }
            },
        )

        if (buffering && error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }

        error?.let { message ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        error = null
                        buffering = true
                        player.prepare()
                        player.play()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KinoRed, contentColor = Color.White),
                ) { Text("Reintentar") }
            }
        }

        AnimatedVisibility(
            visible = controls.visible && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Scrim that makes text and controls readable over the video; on TV the top holds longer
            // so the big title stays readable.
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
                    // Any key while the overlay is open resets the auto-hide timer.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) controls.keepAlive()
                        false
                    }
                    .then(if (isTv) Modifier.padding(horizontal = SAFE_H, vertical = SAFE_V) else Modifier),
            ) {
                if (isTv) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .systemBarsPadding()
                            .padding(start = 16.dp, end = 16.dp)
                            .fillMaxWidth(0.6f),
                    ) {
                        Text(
                            film.title,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 42.sp, lineHeight = 54.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            film.metaLine(),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                film.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                film.metaLine(),
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
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
                    val shownPosition = dragValue?.toLong() ?: positionMs
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(shownPosition), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        SeekBar(
                            positionMs = shownPosition,
                            durationMs = durationMs,
                            bufferedFraction = bufferedFraction,
                            focused = barFocused,
                            onDrag = { dragValue = it; controls.bump() },
                            onDragEnd = {
                                dragValue?.let { player.seekTo(it.toLong()) }
                                dragValue = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                                .then(
                                    if (!isTv) Modifier else Modifier
                                        .focusRequester(barFocus)
                                        .onFocusChanged { barFocused = it.isFocused }
                                        // The bar's D-pad is handled here, before the Slider's own key handler:
                                        // left/right jump 10 s, down goes to the buttons, OK toggles play/pause.
                                        .onPreviewKeyEvent { e ->
                                            val arrow = e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
                                                e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent arrow
                                            when (e.key) {
                                                Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }
                                                Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                                                Key.DirectionDown -> { runCatching { playPauseFocus.requestFocus() }; true }
                                                Key.DirectionUp -> true
                                                Key.DirectionCenter, Key.Enter -> { togglePlayPause(); true }
                                                else -> false
                                            }
                                        },
                                ),
                        )
                        Text(formatDuration(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = if (isTv) Modifier else Modifier.fillMaxWidth(),
                        horizontalArrangement = if (!isTv && !isLandscape) Arrangement.SpaceEvenly else Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportButton(
                            icon = Icons.Default.Replay10,
                            contentDescription = "Atrasar 10s",
                            onClick = { seekBy(-SEEK_STEP_MS) },
                            modifier = if (!isTv) Modifier else Modifier
                                .focusRequester(rewindFocus)
                                .focusProperties { left = rewindFocus; right = playPauseFocus; up = barFocus; down = rewindFocus },
                        )
                        TransportButton(
                            icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pausar" else "Reproducir",
                            onClick = { togglePlayPause() },
                            iconSize = 34.dp,
                            modifier = if (!isTv) Modifier else Modifier
                                .focusRequester(playPauseFocus)
                                .onFocusChanged { playPauseFocused = it.isFocused }
                                .focusProperties { left = rewindFocus; right = forwardFocus; up = barFocus; down = playPauseFocus },
                        )
                        TransportButton(
                            icon = Icons.Default.Forward10,
                            contentDescription = "Adelantar 10s",
                            onClick = { seekBy(SEEK_STEP_MS) },
                            modifier = if (!isTv) Modifier else Modifier
                                .focusRequester(forwardFocus)
                                .focusProperties { left = playPauseFocus; right = tracksFocus; up = barFocus; down = forwardFocus },
                        )
                        TransportButton(
                            icon = Icons.Default.Subtitles,
                            contentDescription = "Audio y subtítulos",
                            onClick = { showTracks = true; controls.bump() },
                            iconSize = 26.dp,
                            modifier = if (!isTv) Modifier else Modifier
                                .focusRequester(tracksFocus)
                                .focusProperties { left = forwardFocus; right = markersFocus; up = barFocus; down = tracksFocus },
                        )
                        TransportButton(
                            icon = Icons.Default.Tune,
                            contentDescription = "Corregir intro y outro",
                            onClick = { showMarkers = true; controls.bump() },
                            iconSize = 26.dp,
                            modifier = if (!isTv) Modifier else Modifier
                                .focusRequester(markersFocus)
                                .focusProperties { left = tracksFocus; right = markersFocus; up = barFocus; down = markersFocus },
                        )
                        if (!isTv) {
                            if (isLandscape) Spacer(Modifier.weight(1f))
                            // Rotate: forces the orientation the system's auto-rotate would not give.
                            IconButton(onClick = {
                                activity?.requestedOrientation = if (isLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            }) {
                                Icon(
                                    Icons.Default.ScreenRotation,
                                    contentDescription = if (isLandscape) "Cambiar a vertical" else "Cambiar a horizontal",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSkip) {
            SkipIntroButton(
                onClick = { skipIntro() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = if (isTv) SAFE_H + 16.dp else 20.dp, bottom = if (controls.visible) 130.dp else 40.dp)
                    .then(
                        if (!isTv) Modifier else Modifier
                            .focusRequester(skipFocus)
                            .onFocusChanged { skipFocused = it.isFocused }
                            // Up brings the controls; left/right keep seeking as on the bare video.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown || controls.visible) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> { controls.bump(); true }
                                    Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                                    Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }
                                    else -> false
                                }
                            },
                    ),
            )
        }
    }

    if (showTracks) AudioAndSubtitlesDialog(menu, onDismiss = { showTracks = false })
    if (showMarkers) {
        SkipMarkersDialog(
            positionLabel = formatDuration(positionMs),
            onPick = { intro ->
                showMarkers = false
                val what = if (intro) "Fin de la intro" else "Inicio del outro"
                Toast.makeText(context, "$what marcado en ${formatDuration(positionMs)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showMarkers = false },
        )
    }
}

/** "Saltar intro": a white-bordered pill over the video; inverts while it holds the D-pad focus. */
@Composable
private fun SkipIntroButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(
        "Saltar intro",
        color = if (focused) Color.Black else Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/**
 * The progress bar: a three-layer track (faint background, the downloaded buffer in light grey, the
 * played part in red) that thickens while it holds the D-pad focus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedFraction: Float,
    focused: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = if (durationMs > 0) durationMs.toFloat() else 1f
    Slider(
        value = if (durationMs > 0) positionMs.toFloat().coerceIn(0f, max) else 0f,
        onValueChange = onDrag,
        onValueChangeFinished = onDragEnd,
        valueRange = 0f..max,
        colors = SliderDefaults.colors(
            thumbColor = KinoRed,
            activeTrackColor = KinoRed,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        track = { _ ->
            val posFrac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val bufFrac = bufferedFraction.coerceIn(0f, 1f)
            val trackHeight by animateDpAsState(targetValue = if (focused) 8.dp else 4.dp, label = "progressBarThickness")
            Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                val y = size.height / 2f
                val sw = size.height
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
                if (bufFrac > 0f) drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width * bufFrac, y), sw, StrokeCap.Round)
                if (posFrac > 0f) drawLine(KinoRed, Offset(0f, y), Offset(size.width * posFrac, y), sw, StrokeCap.Round)
            }
        },
        modifier = modifier,
    )
}

/**
 * A transport button. Focused it inverts (white circle, black icon), so from the couch it's obvious
 * which one is selected; on a touch screen nothing takes focus and it looks the same as ever.
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color.Transparent, CircleShape),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(iconSize))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
