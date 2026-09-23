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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Intro duration, in ms.
 *
 * Public because `MainActivity` times against this for when it starts composing the app (see
 * `INTRO_HEAD_START_MS`). When they were two loose numbers they drifted apart: the head start
 * stayed at 600 ms, tuned for the old 750 ms intro, so the root composed ON TOP of this one's
 * heaviest stretch -- the beam and the word reveal -- and the animation choked right there.
 */
const val INTRO_DURATION_MS = 880

private const val TOTAL_MS = INTRO_DURATION_MS

/** Exit fade duration, in ms. */
private const val EXIT_MS = 260

/** Sub-progress [0..1] of a stretch of the global timeline. */
private fun seg(t: Float, from: Float, to: Float): Float =
    ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/** Ease with a bit of bounce at the end, so the shaft "lands" instead of stopping short. */
private fun easeBack(t: Float): Float {
    val c = 1.7f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/**
 * Kino's monogram, in a 100x100 box with the Y facing down.
 *
 * These are the SAME numbers as `docs/marca/kino_logo.py`, which generates the launcher icon and
 * the TV PNGs. If they're touched here and not there (or the other way around), the intro's K
 * stops matching the icon's.
 */
private object KGeometry {
    private const val SHAFT_X0 = 12f
    private const val SHAFT_X1 = 29f
    private const val TOP = 8f
    private const val BOTTOM = 92f
    private const val RIGHT = 86f
    private const val THICKNESS = 17.5f

    /** Where the arms are born: tucked inside the shaft, so they weld without a seam. */
    const val JOINT_X = SHAFT_X1 - 6f
    const val JOINT_Y = 50f

    val shaft: List<Offset> = listOf(
        Offset(SHAFT_X0, TOP), Offset(SHAFT_X1, TOP),
        Offset(SHAFT_X1, BOTTOM), Offset(SHAFT_X0, BOTTOM),
    )
    val upperArm: List<Offset> = arm(TOP - 2f)
    val lowerArm: List<Offset> = arm(BOTTOM + 2f)

    /** An arm from the joint to the right edge, cut straight. */
    private fun arm(toY: Float): List<Offset> {
        val bx = RIGHT + 14f                     // overshoots and gets cut afterward
        val dx = bx - JOINT_X
        val dy = toY - JOINT_Y
        val n = hypot(dx, dy)
        val nx = -dy / n * (THICKNESS / 2f)
        val ny = dx / n * (THICKNESS / 2f)
        return cutAt(
            listOf(
                Offset(JOINT_X + nx, JOINT_Y + ny), Offset(bx + nx, toY + ny),
                Offset(bx - nx, toY - ny), Offset(JOINT_X - nx, JOINT_Y - ny),
            ),
            RIGHT,
        )
    }

    /** Sutherland-Hodgman against a single vertical plane: leaves a straight cut. */
    private fun cutAt(poly: List<Offset>, xMax: Float): List<Offset> {
        val out = mutableListOf<Offset>()
        for (i in poly.indices) {
            val c = poly[i]
            val p = poly[(i - 1 + poly.size) % poly.size]
            val cInside = c.x <= xMax
            val pInside = p.x <= xMax
            if (cInside != pInside) {
                val t = (xMax - p.x) / (c.x - p.x)
                out += Offset(xMax, p.y + t * (c.y - p.y))
            }
            if (cInside) out += c
        }
        return out
    }
}

/**
 * Builds the `Path` of one monogram piece, already scaled and placed in its spot.
 *
 * Built ONCE per screen size, not per frame: in the first version every frame allocated three new
 * `Path`s, and that on top of rebuilding the gradients was what kept the intro at ~10 fps
 * (measured on the emulator, 2026-08-13). What animates now are transforms on these same paths,
 * which allocate nothing.
 */
private fun pathFor(points: List<Offset>, origin: Offset, scale: Float): Path {
    val path = Path()
    points.forEachIndexed { i, p ->
        val x = origin.x + p.x * scale
        val y = origin.y + p.y * scale
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * Boot intro: the K opens and projects the name.
 *
 * The shaft falls, the two arms shoot out from the joint --like a projector opening--, from there
 * a beam of light shoots to the right and KINO reveals itself INSIDE the beam, left to right. Ends
 * with a zoom + fade that uncovers the app.
 *
 * ### Why the shaft does NOT appear from invisible
 *
 * `installSplashScreen()` doesn't hold onto the system splash: it releases the static K as soon as
 * there's a first frame and Compose takes over right away. If the intro started from black, the
 * device would show "bright K → black → K assembling", which is exactly the jump the previous
 * intro had. That's why the shaft starts at full opacity and the only thing that animates is its
 * fall: at frame zero there's already red on screen and the handoff isn't noticeable. Don't "fix"
 * this by adding an entrance fade.
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
    val measurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(durationMillis = TOTAL_MS, easing = LinearEasing))
        introDone = true
    }
    // The exit waits for the content to be ready: if the splash left as soon as the animation
    // finished, there'd be a black frame while the root still hadn't drawn anything.
    LaunchedEffect(introDone, canExit) {
        if (!introDone || !canExit) return@LaunchedEffect
        exitAnim.animateTo(1f, tween(durationMillis = EXIT_MS, easing = FastOutLinearInEasing))
        onFinished()
    }

    val style = TextStyle(
        color = Color.White,
        fontWeight = FontWeight.Black,
        fontSize = if (isTv) 84.sp else 52.sp,
        letterSpacing = if (isTv) 10.sp else 6.sp,
    )

    // `drawWithCache` and not `Canvas`: the block above runs ONCE per screen size (measuring the
    // text, resolving the lockup, building the paths and the two gradients) and `onDrawBehind`
    // runs per frame allocating nothing. The first version redid all of that 60 times a second and
    // the intro drew at ~10 fps; the full-screen gradients are the most expensive part, and on the
    // Fire Stick --weak GPU, 1.7 GB-- that's exactly what shouldn't happen per frame.
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
                // The lockup's size is derived from the TEXT, never from the screen height.
                //
                // Tying it to `size.height` is what broke the first version: the phone is VERTICAL,
                // so a 0.42 × height monogram came out giant and pushed the word off the right
                // edge. With the reference text, the same math works for both the phone and the
                // landscape TV.
                var measured = measurer.measure(AnnotatedString("KINO"), style)
                var side = measured.size.height * 1.30f
                var gap = measured.size.height * 0.42f
                var width = side + gap + measured.size.width

                // And if it still doesn't fit widthwise, the whole set shrinks by measuring again:
                // the text is in sp, it can't be scaled without re-measuring it.
                val available = size.width * 0.86f
                if (width > available) {
                    val f = available / width
                    measured = measurer.measure(
                        AnnotatedString("KINO"),
                        style.copy(fontSize = style.fontSize * f, letterSpacing = style.letterSpacing * f),
                    )
                    side = measured.size.height * 1.30f
                    gap = measured.size.height * 0.42f
                    width = side + gap + measured.size.width
                }

                val textWidth = measured.size.width.toFloat()
                val textHeight = measured.size.height.toFloat()
                val scale = side / 100f
                val origin = Offset((size.width - width) / 2f, (size.height - side) / 2f)
                val joint = Offset(
                    origin.x + KGeometry.JOINT_X * scale,
                    origin.y + KGeometry.JOINT_Y * scale,
                )
                val textX = origin.x + side + gap
                val textY = size.height / 2f - textHeight / 2f
                val underlineThickness = (size.height * 0.022f).coerceAtLeast(2f)
                val underlineY = textY + textHeight + size.height * 0.035f

                // The monogram's three paths, already in place. What animates are transforms on
                // these same objects.
                val shaftPath = pathFor(KGeometry.shaft, origin, scale)
                val upperArmPath = pathFor(KGeometry.upperArm, origin, scale)
                val lowerArmPath = pathFor(KGeometry.lowerArm, origin, scale)

                // The beam, built at its FINAL size. When drawn it's scaled from the joint, and
                // since length and spread grow together, a uniform scale is exactly the cone
                // opening: no need to rebuild the path.
                val beamLength = size.width - joint.x
                val beamSpread = size.height * 0.30f
                val beamPath = Path().apply {
                    moveTo(joint.x, joint.y)
                    lineTo(joint.x + beamLength, joint.y - beamSpread)
                    lineTo(joint.x + beamLength, joint.y + beamSpread)
                    close()
                }
                val beamBrush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFFFEEEE), Color.Transparent),
                    startX = joint.x,
                    endX = joint.x + beamLength,
                )
                // NO BACKGROUND GLOW GOES HERE. The intro used to have a red radial gradient
                // behind the monogram and it was, by far, the most expensive thing it drew.
                //
                // Measured on the Fire Stick with `gfxinfo`, three runs of each variant:
                //
                //   full screen             GPU 15 ms per frame (of a 16.7 ms budget: at 90%)
                //   capped to 0.55 of height    9-10 ms
                //   no glow                      3-4 ms   <- this one
                //
                // Five times less, and janky frames went from 55% to 10%. A gradient covering
                // 1920x1080 every frame is exactly what a Fire Stick's GPU can't take. If it's
                // ever brought back, do it over a cached layer and measure with `gfxinfo` before
                // and after, not by eye.

                onDrawBehind {
                    val t = intro.value * TOTAL_MS
                    val pShaft = easeBack(seg(t, 0f, 260f))
                    val pUpper = easeOut(seg(t, 160f, 400f))
                    val pLower = easeOut(seg(t, 220f, 460f))
                    val pBeam = seg(t, 380f, 700f)
                    val pWord = easeOut(seg(t, 460f, 800f))
                    val pGlow = seg(t, 700f, 880f)

                    drawRect(ArkivBlack)


                    if (pBeam > 0f) {
                        val opening = easeOut(min(pBeam / 0.55f, 1f))
                        val fade = if (pBeam < 0.55f) pBeam / 0.55f else 1f - (pBeam - 0.55f) / 0.45f * 0.72f
                        scale(opening, opening, pivot = joint) {
                            drawPath(beamPath, beamBrush, alpha = 0.30f * fade)
                        }
                    }

                    // The shaft falls from above, ALREADY VISIBLE (see the doc above).
                    translate(top = -side * 0.5f * (1f - pShaft)) {
                        drawPath(shaftPath, ArkivRed)
                    }
                    // The arms shoot out from the joint, one after the other.
                    if (pUpper > 0f) scale(pUpper, pUpper, pivot = joint) { drawPath(upperArmPath, ArkivRed) }
                    if (pLower > 0f) scale(pLower, pLower, pivot = joint) { drawPath(lowerArmPath, ArkivRed) }

                    // The word reveals INSIDE the beam, left to right.
                    if (pWord > 0f) {
                        clipRect(left = textX, right = textX + textWidth * pWord) {
                            drawText(measured, topLeft = Offset(textX, textY))
                        }
                        drawRect(
                            color = ArkivRed,
                            topLeft = Offset(textX, underlineY),
                            size = Size(textWidth * pWord, underlineThickness),
                        )
                    }
                }
            },
    )
}
