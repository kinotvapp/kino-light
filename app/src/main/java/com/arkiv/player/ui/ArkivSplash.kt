package com.arkiv.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivBlack
import kotlin.math.min

/**
 * Intro duration, in ms.
 *
 * Public because `MainActivity` times against this for when it starts composing the app (see
 * `INTRO_HEAD_START_MS`). When they were two loose numbers they drifted apart: the head start
 * stayed at 600 ms, tuned for the old 750 ms intro, so the root composed ON TOP of this one's
 * heaviest stretch and the animation choked right there.
 */
const val INTRO_DURATION_MS = 960

private const val TOTAL_MS = INTRO_DURATION_MS

/** Exit fade duration, in ms. */
private const val EXIT_MS = 260

/**
 * Outer radius of the ring the system splash paints, in dp.
 *
 * The launcher foreground (`ic_launcher_foreground.xml`) puts the ring's outer edge at radius 30 of
 * its 108 canvas, and the splash icon view is 288dp (both on Android 12+ and in the
 * core-splashscreen compat path): 30 / 108 * 288 = 80. The intro's first frame draws the ring at
 * exactly this size and at the screen's center, so the handoff isn't visible.
 */
private val SYSTEM_RING_OUTER_RADIUS = 80.dp

/** Sub-progress [0..1] of a stretch of the global timeline. */
private fun seg(t: Float, from: Float, to: Float): Float =
    ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

private fun easeInOut(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

/** Ease with a bit of bounce at the end, so the TV "lands" instead of stopping short. */
private fun easeBack(t: Float): Float {
    val c = 1.4f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Boot intro: the app icon's ring becomes the "o" of kino, the word appears around it, and the
 * small TV zooms in until it lands in its spot. Ends with a zoom + fade that uncovers the app.
 *
 * On TV ([isTv]) there's no intro: the logo is shown already assembled and only the exit fade runs.
 *
 * ### Why the ring does NOT appear from invisible
 *
 * `installSplashScreen()` doesn't hold onto the system splash: it releases the static icon as soon
 * as there's a first frame and Compose takes over right away. If the intro started from black, the
 * device would show "icon → black → logo assembling". That's why frame zero is the ring EXACTLY as
 * the system splash paints it (same center, same size -- see [SYSTEM_RING_OUTER_RADIUS]) and the
 * only thing that animates is it shrinking into the word. Don't "fix" this by adding an entrance
 * fade.
 *
 * Drawn ON TOP of the content to cover the cold start. The exit doesn't start until [canExit] is
 * true: that way the fade uncovers an already-drawn screen instead of leaving another black gap.
 * [onFinished] tells the caller to remove it from the composition.
 */
@Composable
fun ArkivSplash(
    isTv: Boolean,
    canExit: Boolean,
    onFinished: () -> Unit,
) {
    val intro = remember { Animatable(0f) }
    val exitAnim = remember { Animatable(0f) }
    var introDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // TV: no animation, the logo is drawn in its final state from the first frame. The intro
        // cost startup time on the TV boxes (MainActivity held the root back for its whole
        // duration), and on a TV nobody is watching the launch up close.
        if (isTv) intro.snapTo(1f) else intro.animateTo(1f, tween(durationMillis = TOTAL_MS, easing = LinearEasing))
        introDone = true
    }
    // The exit waits for the content to be ready: if the splash left as soon as the animation
    // finished, there'd be a black frame while the root still hadn't drawn anything.
    LaunchedEffect(introDone, canExit) {
        if (!introDone || !canExit) return@LaunchedEffect
        exitAnim.animateTo(1f, tween(durationMillis = EXIT_MS, easing = FastOutLinearInEasing))
        onFinished()
    }

    // `drawWithCache` and not `Canvas`: the block above `onDrawBehind` runs ONCE per screen size
    // (sizing the lockup, building the paths and strokes) and `onDrawBehind` runs per frame
    // allocating nothing. What animates are transforms and alphas on these same objects -- the
    // earlier intro drew at ~10 fps when it rebuilt its paths every frame, and on the Fire Stick
    // (weak GPU, 1.7 GB) that's exactly what shouldn't happen per frame.
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f + 0.07f * exitAnim.value
                scaleX = s
                scaleY = s
                alpha = 1f - exitAnim.value
            }
            .drawWithCache {
                // Lockup width: a share of the screen width, capped by the height so it doesn't
                // take over a landscape TV.
                val lockupWidth = min(size.width * (if (isTv) 0.46f else 0.64f), size.height * 0.85f)
                val scale = lockupWidth / Wordmark.WIDTH
                val lockupHeight = Wordmark.HEIGHT * scale
                // Where the logo's (0,0) lands on screen so the lockup is centered.
                val origin = Offset(
                    (size.width - lockupWidth) / 2f - Wordmark.LEFT * scale,
                    (size.height - lockupHeight) / 2f - Wordmark.TOP * scale,
                )
                val screenCenter = Offset(size.width / 2f, size.height / 2f)
                val oFinal = origin + Wordmark.oCenter * scale
                // Scale of the "o" at frame zero, relative to its final size in the lockup.
                val ringStartScale = SYSTEM_RING_OUTER_RADIUS.toPx() / (Wordmark.O_OUTER * scale)

                val kPath = Wordmark.k()
                val iPath = Wordmark.iStem()
                val nPath = Wordmark.n()
                val tvPath = Wordmark.tv()
                val rise = 18.dp.toPx() / scale   // in logo units

                /** Draws in logo units, placed and scaled into the lockup. */
                fun DrawScope.inLogo(block: DrawScope.() -> Unit) = withTransform({
                    translate(origin.x, origin.y)
                    scale(scale, scale, pivot = Offset.Zero)
                }, block)

                onDrawBehind {
                    val t = intro.value * TOTAL_MS
                    val pRing = easeInOut(seg(t, 0f, 420f))
                    val pK = easeOut(seg(t, 200f, 480f))
                    val pI = easeOut(seg(t, 260f, 540f))
                    val pN = easeOut(seg(t, 320f, 600f))
                    val pTv = seg(t, 540f, 900f)

                    drawRect(ArkivBlack)

                    // The ring: from the system splash's icon at the center to the "o" of kino.
                    val ringCenter = Offset(
                        lerp(screenCenter.x, oFinal.x, pRing),
                        lerp(screenCenter.y, oFinal.y, pRing),
                    )
                    val ringScale = scale * lerp(ringStartScale, 1f, pRing)
                    withTransform({
                        translate(ringCenter.x, ringCenter.y)
                        scale(ringScale, ringScale, pivot = Offset.Zero)
                    }) {
                        drawCircle(Color.White, Wordmark.O_RADIUS, Offset.Zero, style = Wordmark.ringStroke)
                        drawCircle(LogoRed, Wordmark.DOT_RADIUS, Offset.Zero)
                    }

                    // k, i, n: each one rises and fades in, one after the other.
                    inLogo {
                        if (pK > 0f) translate(top = rise * (1f - pK)) {
                            drawPath(kPath, Color.White, alpha = pK, style = Wordmark.letterStroke)
                        }
                        if (pI > 0f) translate(top = rise * (1f - pI)) {
                            drawPath(iPath, Color.White, alpha = pI, style = Wordmark.letterStroke)
                            drawCircle(Color.White, Wordmark.I_DOT_RADIUS, Wordmark.iDot, alpha = pI)
                        }
                        if (pN > 0f) translate(top = rise * (1f - pN)) {
                            drawPath(nPath, Color.White, alpha = pN, style = Wordmark.letterStroke)
                        }
                        // The small TV zooms in from big and lands in its spot.
                        if (pTv > 0f) {
                            val z = lerp(3.2f, 1f, easeBack(pTv))
                            scale(z, z, pivot = Wordmark.tvCenter) {
                                drawPath(
                                    tvPath,
                                    Color.White,
                                    alpha = min(pTv / 0.35f, 1f),
                                    style = Wordmark.tvStroke,
                                )
                            }
                        }
                    }
                }
            },
    )
}
