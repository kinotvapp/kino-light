package com.arkiv.player.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.EffectsReduced
import com.arkiv.player.data.EffectsMode
import com.arkiv.player.data.EffectsPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * The app's DECORATIVE motion (the hero backdrop that slowly zooms and drifts, and the crossfade
 * between backdrops) can be turned off, either by the person or because the device proved too slow for
 * it. The decision is [EffectsPolicy]'s; this file is the Android side: reading the device, holding the
 * effects behind one switch, and measuring real frame times.
 *
 * Only decorative motion is covered: the hero backdrop, its crossfade and the cards' focus zoom. What
 * stays is what a remote-control user needs to see where they are (the white focus border on every
 * card) and the player's own zoom, which is a functional control and a cheap texture transform.
 */

/** Whether the decorative effects should be OFF right now: the person's choice, or the automatic signals in "Automático". */
@Composable
fun rememberReducedEffects(): Boolean {
    val graph = rememberGraph()
    val context = LocalContext.current
    val mode by graph.settings.effectsMode.collectAsState()
    val autoReduced by graph.settings.effectsAutoReduced.collectAsState()
    // Neither changes while the app runs (specs, and the accessibility animation scale is read at launch).
    val staticHint = remember(context) { DeviceEffects.staticHint(context) }
    val animationsOff = remember(context) { DeviceEffects.systemAnimationsOff(context) }
    return EffectsPolicy.resolve(mode, autoReduced, staticHint, animationsOff)
}

/**
 * The hero backdrop's slow left-right drift, 0..1. Returns a [State], NOT a Float, on purpose: callers
 * read it only inside `graphicsLayer { }`, so each frame invalidates just that layer instead of
 * recomposing the whole screen. Returning the value would recompose the Home 60 times a second.
 *
 * With [reduced] there is no infinite transition at all (not one that runs and is ignored: a running
 * animation keeps the frame clock ticking), just a fixed, centred value.
 */
@Composable
fun rememberHeroDrift(reduced: Boolean, durationMs: Int): State<Float> {
    if (reduced) return remember { mutableFloatStateOf(0.5f) }
    return rememberInfiniteTransition(label = "heroDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDriftX",
    )
}

/** The crossfade between hero backdrops: 450 ms normally, an instant swap when reduced (two large images composited at once are the cost). */
fun backdropFadeSpec(reduced: Boolean): FiniteAnimationSpec<Float> = if (reduced) snap() else tween(450)

/**
 * The reduced-effects switch for anything deep in the TV UI (the cards). Provided ONCE by the app's TV
 * mount (`MainActivity`) so that dozens of cards per screen don't each read the settings. Outside that
 * provider it is `false` = full effects, which is the safe default (only ever more motion, never less).
 */
val LocalReducedEffects = compositionLocalOf { false }

/**
 * The TV cards' focus zoom: +8% as focus arrives, none when reduced. Every card also draws a white 3 dp
 * border when focused, and that border, not the zoom, is what shows where the focus is, so turning the
 * zoom off costs the remote-control user nothing. Moving through a row grows and shrinks a card on every
 * D-pad press, which is what makes a slow box feel sluggish.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun cardFocusScale(reduced: Boolean): CardScale = CardDefaults.scale(focusedScale = if (reduced) 1f else 1.08f)

/**
 * Judges the device while the effects run, and turns them off for good if it's slow. TV Home only.
 *
 * Measures once per process, after [SETTLE_MS] (the startup credential warm-up takes 4-34 s on these
 * boxes and competes for the same cores, which would make any device look slow), and only when it can
 * matter: not already reduced, and not when the person picked a mode explicitly. A slow verdict is a
 * strike, and it takes [EffectsPolicy.STRIKES_TO_REDUCE] on separate launches to switch the effects off,
 * unless the sample is so bad ([EffectsPolicy.SEVERE_SHARE]) that one is enough.
 */
@Composable
fun EffectsAutoTune(reduced: Boolean) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val mode by graph.settings.effectsMode.collectAsState()
    LaunchedEffect(reduced, mode) {
        if (reduced || mode != EffectsMode.AUTO || FrameJankMonitor.judgedThisProcess) return@LaunchedEffect
        val window = context.hostActivity()?.window ?: return@LaunchedEffect
        delay(SETTLE_MS)
        val frames = FrameJankMonitor.sampleFrames(window)
        val budgetMs = DeviceEffects.frameBudgetMs(context)
        val slow = EffectsPolicy.isSlow(frames, budgetMs) ?: return@LaunchedEffect
        FrameJankMonitor.judgedThisProcess = true
        if (!slow) {
            graph.settings.recordSmoothEffectsSample()
            return@LaunchedEffect
        }
        val severe = EffectsPolicy.isSevere(frames, budgetMs)
        if (graph.settings.recordSlowEffectsSample(severe)) {
            // Stable message, the numbers as extras: one GlitchTip issue collects every device.
            Crash.report(
                EffectsReduced("decorative effects turned off: device measured slow"),
                "effects-auto-reduced",
                extras = mapOf(
                    "model" to Build.MODEL,
                    "ram_mb" to DeviceEffects.totalRamMb(context).toString(),
                    "refresh_hz" to "%.0f".format(DeviceEffects.refreshRateHz(context)),
                    "budget_ms" to "%.1f".format(budgetMs),
                    "dropped_pct" to (EffectsPolicy.droppedShare(frames, budgetMs) * 100).toInt().toString(),
                    "frames" to frames.size.toString(),
                    "severe" to severe.toString(),
                ),
            )
        }
    }
}

private const val SETTLE_MS = 15_000L

internal object DeviceEffects {

    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_048_576L
    }

    fun staticHint(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return EffectsPolicy.staticHint(totalRamMb(context), am?.isLowRamDevice == true)
    }

    /** The accessibility "remove animations" setting (animator duration scale 0): whoever set it doesn't want motion. */
    fun systemAnimationsOff(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** The display's refresh rate in Hz (0 when unknown). */
    @Suppress("DEPRECATION")
    fun refreshRateHz(context: Context): Float =
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.refreshRate ?: 0f

    /** One frame's time budget on this display: 16.7 ms, or longer on a slower panel, never shorter (see [EffectsPolicy.frameBudgetMs]). */
    fun frameBudgetMs(context: Context): Float = EffectsPolicy.frameBudgetMs(refreshRateHz(context))
}

internal object FrameJankMonitor {

    /** Set once a verdict was reached in this process, so leaving and re-entering Home can't count two strikes in one launch. */
    @Volatile
    var judgedThisProcess = false

    /**
     * The total time of each of the next [EffectsPolicy.SAMPLE_FRAMES] frames drawn in [window], in ms,
     * after skipping the first [EffectsPolicy.WARMUP_FRAMES] (first draw and image decoding are slow on
     * every device). Frames only come while something is animating, which the effects guarantee.
     * Cancelling (leaving the screen) removes the listener.
     */
    suspend fun sampleFrames(window: Window): List<Float> {
        val thread = HandlerThread("kino-frames").apply { start() }
        var listener: Window.OnFrameMetricsAvailableListener? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val samples = ArrayList<Float>(EffectsPolicy.SAMPLE_FRAMES)
                var seen = 0
                var finished = false
                val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    if (finished) return@OnFrameMetricsAvailableListener
                    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                    if (seen++ < EffectsPolicy.WARMUP_FRAMES) return@OnFrameMetricsAvailableListener
                    samples += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f
                    if (samples.size >= EffectsPolicy.SAMPLE_FRAMES) {
                        finished = true
                        // A copy: this thread must not touch the list the caller is about to read.
                        if (cont.isActive) cont.resume(samples.toList())
                    }
                }
                listener = l
                window.addOnFrameMetricsAvailableListener(l, Handler(thread.looper))
            }
        } finally {
            // Off the callback thread, so removing it can't race the window's own iteration of listeners.
            listener?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
            thread.quitSafely()
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}
