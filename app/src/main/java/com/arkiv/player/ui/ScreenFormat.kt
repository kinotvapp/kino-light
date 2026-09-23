package com.arkiv.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Are we on a tablet in landscape? It's the only thing that decides whether the wide layout is used.
 *
 * Looks at the device's SMALLEST side ([smallestWidthDp]) and not the current width, because the
 * current width would lie: a Galaxy S24+ lying flat measures 1040dp wide and would get the tablet
 * layout, which is exactly what's not wanted. The smallest side is rotation-invariant: 480dp on
 * that phone (never qualifies) and ~800dp on a 10" tablet (always qualifies).
 *
 * 600dp is Android's standard threshold for "tablet" (`sw600dp`).
 */
fun isLandscapeTablet(smallestWidthDp: Int, orientation: Int): Boolean =
    smallestWidthDp >= TABLET_THRESHOLD_DP && orientation == Configuration.ORIENTATION_LANDSCAPE

const val TABLET_THRESHOLD_DP = 600

/** Same question, reading the current configuration. Only recomposes on rotation. */
@Composable
fun isLandscapeTablet(): Boolean {
    val config = LocalConfiguration.current
    return isLandscapeTablet(config.smallestScreenWidthDp, config.orientation)
}

/**
 * Columns for a grid. Twice as many fit in wide mode: with 3 columns at 1280dp each card would be
 * 400dp, bigger than a phone screen.
 */
fun gridColumns(base: Int, isWide: Boolean): Int = if (isWide) base * 2 else base

/**
 * Width cap for content meant to be READ (settings, downloads, the intro screen): on a landscape
 * tablet a 1280dp-wide line is unreadable, so the content is capped and centered.
 *
 * Lives here instead of repeated in each screen so there's a single number: three copies of the
 * same `720.dp` would drift out of sync the first time someone tweaks one.
 *
 * Mind the order: this modifier has to come BEFORE a `fillMaxSize()`/`fillMaxWidth()`, or the
 * latter overrides the constraint and the cap does nothing.
 */
@Composable
fun Modifier.readingWidth(): Modifier =
    widthIn(max = if (isLandscapeTablet()) 720.dp else Dp.Unspecified)
