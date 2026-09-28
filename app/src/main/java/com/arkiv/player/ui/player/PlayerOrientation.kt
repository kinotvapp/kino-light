package com.arkiv.player.ui.player

import android.content.pm.ActivityInfo

/**
 * What to request when the player's rotate button is tapped: the opposite of what's on screen
 * now. Landscape allows either landscape rotation (SENSOR_LANDSCAPE, so flipping the phone still
 * works); portrait is locked upright. Scoped to the player screen only -- it's reset back to
 * SCREEN_ORIENTATION_UNSPECIFIED when the screen is left (see the exit DisposableEffect in
 * PlayerScreen), so the rest of the app keeps following the device's own orientation.
 */
internal fun nextPlayerOrientation(isLandscape: Boolean): Int =
    if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
