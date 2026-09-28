package com.arkiv.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/** One sweep of the skeleton shimmer across a placeholder. */
private const val SHIMMER_MS = 1_300

/**
 * The shimmer's position, 0..1, shared by every placeholder on a screen so they sweep together. Null
 * with [reduced] effects: then no infinite transition exists at all (a running one keeps the frame clock
 * ticking even if ignored) and the placeholders stay static grey. Read only while drawing (see
 * [skeleton]), so each frame redraws the placeholders without recomposing the screen.
 */
@Composable
fun rememberSkeletonShimmer(reduced: Boolean): State<Float>? {
    if (reduced) return null
    return rememberInfiniteTransition(label = "skeletonShimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonShimmerX",
    )
}

/**
 * A grey placeholder shape with, when [shimmer] is given, a faint light band sweeping across it. Draws
 * only: no click, no focus, no semantics, so the D-pad and screen readers pass over it.
 */
fun Modifier.skeleton(shimmer: State<Float>?, shape: Shape = RoundedCornerShape(8.dp)): Modifier =
    clip(shape).drawBehind {
        drawRect(ArkivSurfaceHigh)
        val progress = shimmer?.value ?: return@drawBehind
        val band = size.width.coerceAtLeast(size.height)
        val x = -band + progress * (size.width + 2 * band)
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                start = Offset(x - band / 2, 0f),
                end = Offset(x + band / 2, size.height),
            ),
        )
    }
