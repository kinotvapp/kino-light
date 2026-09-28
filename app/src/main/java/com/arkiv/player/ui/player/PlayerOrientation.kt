package com.arkiv.player.ui.player

import android.content.pm.ActivityInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

/**
 * The phone's rotate button, shared by VOD's icon row and live's top band: requests
 * [nextPlayerOrientation] on tap. Never composed on TV.
 */
@Composable
internal fun PlayerRotateButton(isLandscape: Boolean, onRotate: (Int) -> Unit) {
    IconButton(onClick = { onRotate(nextPlayerOrientation(isLandscape)) }) {
        Icon(
            Icons.Default.ScreenRotation,
            contentDescription = if (isLandscape) "Cambiar a vertical" else "Cambiar a horizontal",
            tint = Color.White,
        )
    }
}
