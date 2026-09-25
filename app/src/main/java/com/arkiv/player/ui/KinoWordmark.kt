package com.arkiv.player.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/** The logo's own red (the dot). Not `ArkivRed`: the logo was drawn with this one. */
internal val LogoRed = Color(0xFFE53224)

/**
 * The kino TV wordmark, in the coordinates of `docs/brand/kino-tv-logo.svg` (Y down).
 *
 * The SAME numbers as that SVG: if they're touched here and not there (or the other way around),
 * the app's logo stops matching it. The "o" also has to keep the proportions of the launcher
 * icon's ring (`ic_launcher_foreground.xml`), since the splash starts from it.
 */
internal object Wordmark {
    const val STROKE = 42f
    const val TV_STROKE = 26f

    // Bounding box of the whole lockup, strokes included.
    const val LEFT = 126f
    const val RIGHT = 984f
    const val TOP = 84f
    const val BOTTOM = 350f
    const val WIDTH = RIGHT - LEFT
    const val HEIGHT = BOTTOM - TOP

    val oCenter = Offset(687f, 252f)
    const val O_RADIUS = 80f
    const val O_STROKE = 35f
    const val O_OUTER = O_RADIUS + O_STROKE / 2f
    const val DOT_RADIUS = 30.5f

    val iDot = Offset(343f, 123f)
    const val I_DOT_RADIUS = 25f

    /** Center of the TV, the pivot the splash zooms it around. */
    val tvCenter = Offset(881f, 140f)

    fun k() = Path().apply {
        moveTo(147f, 133f); lineTo(147f, 324f)
        moveTo(267f, 170f); lineTo(199f, 250f); lineTo(270f, 324f)
        moveTo(152f, 250f); lineTo(199f, 250f)
    }

    fun iStem() = Path().apply { moveTo(343f, 185f); lineTo(343f, 324f) }

    fun n() = Path().apply {
        moveTo(422f, 324f); lineTo(422f, 185f)
        moveTo(422f, 255f)
        cubicTo(422f, 210f, 452f, 182f, 490f, 182f)
        cubicTo(524f, 182f, 544f, 206f, 544f, 242f)
        lineTo(544f, 324f)
    }

    fun tv() = Path().apply {
        moveTo(791f, 97f); lineTo(862f, 97f)
        moveTo(826f, 97f); lineTo(826f, 182f)
        moveTo(896f, 97f); lineTo(933f, 182f); lineTo(971f, 97f)
    }

    val letterStroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val tvStroke = Stroke(width = TV_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val ringStroke = Stroke(width = O_STROKE)
}

/**
 * The static kino TV logo, [height] tall (the width follows the logo's aspect ratio). Used in the
 * app bars and menus; the animated version is [ArkivSplash].
 */
@Composable
fun KinoWordmark(height: Dp, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(height)
            .aspectRatio(Wordmark.WIDTH / Wordmark.HEIGHT)
            .semantics { contentDescription = "Kino TV" }
            .drawWithCache {
                val scale = size.width / Wordmark.WIDTH
                val paths = listOf(Wordmark.k(), Wordmark.iStem(), Wordmark.n())
                val tv = Wordmark.tv()
                onDrawBehind {
                    withTransform({
                        scale(scale, scale, pivot = Offset.Zero)
                        translate(-Wordmark.LEFT, -Wordmark.TOP)
                    }) {
                        paths.forEach { drawPath(it, Color.White, style = Wordmark.letterStroke) }
                        drawCircle(Color.White, Wordmark.I_DOT_RADIUS, Wordmark.iDot)
                        drawCircle(Color.White, Wordmark.O_RADIUS, Wordmark.oCenter, style = Wordmark.ringStroke)
                        drawCircle(LogoRed, Wordmark.DOT_RADIUS, Wordmark.oCenter)
                        drawPath(tv, Color.White, style = Wordmark.tvStroke)
                    }
                }
            },
    )
}
