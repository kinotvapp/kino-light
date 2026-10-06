package app.kino.tv.ui

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale

/*
 * Decorative motion (the hero backdrop that slowly drifts, backdrop crossfades, the cards' focus
 * zoom, the side rail opening) goes through these helpers so it can be switched off in one place.
 * It is off when the system's "remove animations" accessibility setting is on.
 */

/** Whether the system asked for no animations (animator duration scale 0). */
fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Reduced effects for everything below the provider; `false` (full effects) by default. */
val LocalReducedEffects = compositionLocalOf { false }

/**
 * The hero backdrop's slow left-right drift, 0..1. Returns a [State] so callers read it inside
 * `graphicsLayer { }` and only that layer is invalidated per frame.
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

/** The crossfade between hero backdrops: 450 ms normally, an instant swap when reduced. */
fun backdropFadeSpec(reduced: Boolean): FiniteAnimationSpec<Float> = if (reduced) snap() else tween(450)

/** The TV cards' focus zoom: +8% as focus arrives, none when reduced (the white border still marks focus). */
fun cardFocusScale(reduced: Boolean): CardScale = CardDefaults.scale(focusedScale = if (reduced) 1f else 1.08f)

/** [full] with the effects on, an instant [snap] with them off. */
@Composable
fun <T> effectSpec(full: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReducedEffects.current) snap() else full

/** An `AnimatedVisibility` enter: [full] with the effects on, an instant appearance with them off. */
fun effectEnter(reduced: Boolean, full: EnterTransition): EnterTransition = if (reduced) EnterTransition.None else full

/** An `AnimatedVisibility` exit: [full] with the effects on, an instant disappearance with them off. */
fun effectExit(reduced: Boolean, full: ExitTransition): ExitTransition = if (reduced) ExitTransition.None else full

/**
 * Tablet in landscape: the only case that gets the wide phone layout. Looks at the smallest side,
 * which doesn't change with rotation (a big phone lying flat must not get the tablet layout).
 */
@Composable
fun isLandscapeTablet(): Boolean {
    val config = LocalConfiguration.current
    return config.smallestScreenWidthDp >= 600 && config.orientation == Configuration.ORIENTATION_LANDSCAPE
}
