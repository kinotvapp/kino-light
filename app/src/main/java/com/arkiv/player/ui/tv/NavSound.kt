package com.arkiv.player.ui.tv

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.R
import com.arkiv.player.ui.rememberGraph

/**
 * Returns a function that plays a short navigation "tick" (Netflix-style) when the focused item
 * changes on TV. Releases the SoundPool on exit.
 *
 * Silent when the person turned "Sonidos de navegación" off in Ajustes (some can't stand the beep on
 * every card). In that case no SoundPool is even created: nothing to decode or hold in memory, which
 * matters on the weak boxes, and this is called from four screens. Flipping the setting takes effect
 * at once: the pool is built or dropped as the value changes.
 */
@Composable
fun rememberNavSound(): () -> Unit {
    val context = LocalContext.current
    val enabled by rememberGraph().settings.uiSoundsEnabled.collectAsState()
    val pool = remember(enabled) {
        if (!enabled) {
            null
        } else {
            SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        }
    }
    val soundId = remember(pool) { pool?.load(context, R.raw.nav_click, 1) ?: 0 }
    DisposableEffect(pool) {
        onDispose { pool?.release() }
    }
    return remember(pool, soundId) {
        { pool?.play(soundId, 0.5f, 0.5f, 1, 0, 1f) }
    }
}
