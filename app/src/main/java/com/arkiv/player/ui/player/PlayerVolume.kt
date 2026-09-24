package com.arkiv.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.arkiv.player.ui.theme.ArkivRed

private val TRACK_HEIGHT = 140.dp

/**
 * Phone volume control: a speaker button in the transport's secondary row that, on tap, pops a
 * vertical slider just above it. The slider drives the system media volume through [GesturesState]
 * (STREAM_MUSIC). This is now the ONLY in-player way to change volume: the right-edge volume swipe
 * was removed. Lives only where the secondary row does (phone, not casting).
 */
@Composable
internal fun VolumeButton(gestures: GesturesState) {
    var open by remember { mutableStateOf(false) }
    var volume by remember { mutableIntStateOf(gestures.currentVolume()) }
    // Lift the popup by its own height so it sits ABOVE the button instead of over the controls.
    val popupUpPx = with(LocalDensity.current) { (TRACK_HEIGHT + 68.dp).roundToPx() }

    Box {
        IconButton(onClick = {
            volume = gestures.currentVolume() // reflect any change from the hardware volume keys
            open = !open
        }) {
            Icon(
                imageVector = when {
                    volume <= 0 -> Icons.AutoMirrored.Filled.VolumeOff
                    volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = "Volumen",
                tint = Color.White,
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -popupUpPx),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                VolumeSliderPanel(volume) { v ->
                    volume = v
                    gestures.setVolume(v)
                }
            }
        }
    }
}

@Composable
private fun VolumeSliderPanel(volume: Int, onVolume: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$volume%", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(TRACK_HEIGHT)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f))
                .pointerInput(Unit) {
                    // Touch anywhere sets the level (top = 100, bottom = 0); dragging keeps updating.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun setFrom(y: Float) {
                            val fraction = ((size.height - y) / size.height).coerceIn(0f, 1f)
                            onVolume((fraction * 100f).toInt())
                        }
                        setFrom(down.position.y)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change != null && change.pressed) {
                                setFrom(change.position.y)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (volume > 0) {
                Box(
                    Modifier
                        .width(6.dp)
                        .fillMaxHeight((volume / 100f).coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(ArkivRed),
                )
            }
        }
    }
}
