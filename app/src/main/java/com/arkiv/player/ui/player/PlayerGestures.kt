package com.arkiv.player.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.SettingsStore
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Speed steps and their labels, in the same order: the index applies to both lists. */
private val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val SPEED_LABELS = listOf("0.75×", "1×", "1.25×", "1.5×", "2×")

/**
 * Zoom steps, applied as a scale of the video TextureView's transform. 0f = "fit the screen"; the
 * rest crop progressively so the black letterbox bars of a wide movie can be zoomed away, at the
 * cost of losing the sides. A fine ramp (10% per tap) because the two [zoomIn]/[zoomOut] buttons
 * make small adjustments cheap, and the sweet spot for a given movie is somewhere in the middle.
 */
private val ZOOM_STEPS = listOf(0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)

/**
 * Night mode's ceiling: the black veil goes ON TOP of the video, with opacity level/[DIM_MAX_LEVEL] —
 * 0 = normal brightness (no veil), [DIM_MAX_LEVEL] = fully black. The screen's real brightness
 * isn't used because that would also dim the controls, exactly when they're needed.
 */
internal const val DIM_MAX_LEVEL = 10

/** How long the brightness-level HUD stays after a button press. */
private const val BRIGHTNESS_HUD_MS = 1200L

/** The long-press's temporary speed. */
private const val ACCELERATED_SPEED = 2f

/**
 * Speed, zoom, night mode, and the gestures' central HUD: everything the user adjusts ON TOP of
 * playback without changing what's playing.
 *
 * The three settings share [hud] —the little card in the center— and that's why they travel
 * together; each on its own would have nowhere to put that state. Speed and zoom go against the
 * local player and aren't persisted (they're this session's own); night mode is, in
 * [SettingsStore], because it survives closing the app.
 *
 * [onInteract] is the screen's `bump()`: any of these adjustments counts as activity and resets
 * the controls' auto-hide.
 */
@Stable
internal class GesturesState(
    /** The local (service-hosted) player, through the screen's `MediaController`. See [exoRef]. */
    private val local: Player?,
    private val settings: SettingsStore,
    /**
     * The system media-volume control. The volume gesture drives the device's STREAM_MUSIC level
     * (the same one the hardware volume keys move) instead of ExoPlayer's per-player software gain,
     * which only attenuates below the device volume and starts at max -- so raising it did nothing.
     */
    private val audio: AudioManager?,
    private val onInteract: () -> Unit,
) {
    private var speedIndex by mutableIntStateOf(1) // starts at 1×
    private var zoomIndex by mutableIntStateOf(0) // starts at "Ajustar"

    /**
     * The player in charge: an in-screen ExoPlayer while one is bound, otherwise [local]. Same
     * treatment as in [TracksState]: the screen plugs it in when it creates the player, and speed
     * and volume go to whichever is playing.
     */
    private var exoRef: Player? = local

    /** Null = back to the local (service) player. */
    fun setExoPlayer(player: Player?) {
        exoRef = player ?: local
    }

    /** Speed goes to whoever is playing. */
    private fun applySpeed(rate: Float) {
        exoRef?.setPlaybackSpeed(rate)
    }

    private fun currentSpeed(): Float = exoRef?.playbackParameters?.speed ?: 1f

    /**
     * The device media volume as 0..100, backed by the system STREAM_MUSIC level (what the hardware
     * volume keys move). Its device-specific step count (often 0..15) is mapped onto 0..100 and
     * rounded, so a raise then a lower returns to the same number instead of drifting.
     *
     * This replaced ExoPlayer's per-player `volume` (a 0..1 software gain): that only attenuates
     * below the device volume and defaults to 1.0, so the swipe could never make anything louder --
     * the HUD went up while nothing happened. 100 when there is no AudioManager.
     */
    fun currentVolume(): Int {
        val am = audio ?: return 100
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 100
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).coerceIn(0, 100)
    }

    fun setVolume(v: Int) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val index = Math.round(v.coerceIn(0, 100) / 100f * max)
        // Flag 0: no system volume-bar UI -- the gesture draws its own HUD. runCatching because a
        // Do-Not-Disturb policy can reject a media-volume change with SecurityException.
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    /** Central card for the gesture in progress (speed, seek, volume, brightness, zoom), or null. */
    var hud by mutableStateOf<String?>(null)
        private set

    /**
     * Bumps with each press of the brightness OR zoom buttons, so their HUD clears itself. Goes by
     * tick and not by the value: at the caps the level doesn't change, but the press still shows the
     * HUD and it has to fade. Gestures clear theirs by hand on release; a button has no "release",
     * so it needs its own timer.
     */
    var brightnessHudTick by mutableIntStateOf(0)
        private set

    /** Long-press on the video: 2× while held. */
    var accelerating by mutableStateOf(false)
        private set

    private var speedBeforeAccelerating by mutableFloatStateOf(1f)

    val speedLabel: String get() = SPEED_LABELS[speedIndex]
    val speedIsNormal: Boolean get() = speedIndex == 1

    /** True at step 0 ("fit"): the video isn't zoomed, so the buttons show as inactive (white). */
    val zoomIsFit: Boolean get() = zoomIndex == 0

    /**
     * The zoom whoever draws the video has to apply, or 1 if nothing needs to be touched.
     *
     * ExoPlayer has no zoom of its own, so whoever draws the video scales its TextureView transform
     * by this (see `fitAspect`): the view keeps its size and what overflows is cropped. That
     * includes the local player, whose TextureView lives in PlayerScreen.
     */
    val zoomForExo: Float get() = if (exoRef != null) ZOOM_STEPS[zoomIndex].let { if (it <= 0f) 1f else it } else 1f

    /** Speed is cyclic: each tap moves to the next step and wraps back to the start. */
    fun nextSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_STEPS.size
        applySpeed(SPEED_STEPS[speedIndex])
        onInteract()
    }

    /**
     * Zoom is NOT cyclic (unlike speed): the two buttons step it up/down, clamped to the ends. Like
     * [brightnessStep], at the caps the press changes nothing but still flashes the HUD. Nobody to
     * tell: whoever draws the video reads [zoomForExo].
     */
    fun zoomIn() {
        zoomIndex = (zoomIndex + 1).coerceAtMost(ZOOM_STEPS.size - 1)
        showZoomHud()
        onInteract()
    }

    fun zoomOut() {
        zoomIndex = (zoomIndex - 1).coerceAtLeast(0)
        showZoomHud()
        onInteract()
    }

    private fun showZoomHud() {
        val z = ZOOM_STEPS[zoomIndex]
        hud = if (z <= 0f) "🔍 Ajustar" else "🔍 ${(z * 100).toInt()}%"
        brightnessHudTick++
    }

    /**
     * Raises (delta<0) or lowers (delta>0) the night mode veil by one step, clamped to
     * [0, DIM_MAX_LEVEL]. At the ends the press changes nothing, but it still shows the HUD and
     * resets the auto-hide: the buttons are NOT disabled on purpose (on TV a disabled button
     * doesn't take focus and would break the D-pad chain right at the cap).
     */
    fun brightnessStep(delta: Int, currentLevel: Int) {
        val next = (currentLevel + delta).coerceIn(0, DIM_MAX_LEVEL)
        if (next != currentLevel) settings.setDimLevel(next)
        hud = if (next == 0) "☀ Normal" else "🌙 ${next * (100 / DIM_MAX_LEVEL)}%"
        brightnessHudTick++
        onInteract()
    }

    fun showHud(text: String) {
        hud = text
    }

    fun clearHud() {
        hud = null
    }

    /** Long-press: saves the previous speed so it can be restored on release. */
    fun startAccelerating() {
        speedBeforeAccelerating = currentSpeed()
        applySpeed(ACCELERATED_SPEED)
        accelerating = true
        hud = "⏩ ${ACCELERATED_SPEED.toInt()}×"
    }

    /** On release. Returns whether it was actually accelerating, so the caller knows if it acted. */
    fun stopAccelerating(): Boolean {
        if (!accelerating) return false
        accelerating = false
        applySpeed(speedBeforeAccelerating)
        hud = null
        return true
    }
}

@Composable
internal fun rememberGesturesState(
    local: Player?,
    settings: SettingsStore,
    onInteract: () -> Unit,
): GesturesState {
    // rememberUpdatedState so the holder doesn't keep the callback's first version: it's created
    // only once, but `bump()` recomposes with the rest of the screen.
    val latest by rememberUpdatedState(onInteract)
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    return remember(local, settings) { GesturesState(local, settings, audio) { latest() } }
}

/** Clears only the brightness-level HUD, a while after the last press. */
@Composable
internal fun BrightnessHudEffect(state: GesturesState) {
    LaunchedEffect(state.brightnessHudTick) {
        if (state.brightnessHudTick > 0) {
            delay(BRIGHTNESS_HUD_MS)
            state.clearHud()
        }
    }
}
