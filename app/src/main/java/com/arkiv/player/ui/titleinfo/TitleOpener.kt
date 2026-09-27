package com.arkiv.player.ui.titleinfo

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.PluginLive

/** What the person reads when a title cannot be opened. */
const val TITLE_OPEN_ERROR = "No se pudo abrir este título."

/**
 * What tapping a plugin card does ([titleTap]): a movie or series opens its information page
 * through [onOpenRoute]; a live channel is left for the player ([PluginLive.leave]) and played
 * through [onPlay], the same funnel every other playback takes. A result that has no route (blank
 * id, a ref that is not the plugin's own) says so in a toast: a tap is never silent.
 */
@Composable
fun rememberTitleOpener(onOpenRoute: (String) -> Unit, onPlay: (String) -> Unit): (GatewayResult) -> Unit {
    val context = LocalContext.current
    val currentOpen by rememberUpdatedState(onOpenRoute)
    val currentPlay by rememberUpdatedState(onPlay)
    return remember(context) {
        { result ->
            when (val tap = titleTap(result)) {
                is TitleTap.InfoPage -> currentOpen(tap.route)
                is TitleTap.PlayLive -> currentPlay(PluginLive.leave(tap.channel))
                TitleTap.CannotOpen -> Toast.makeText(context, TITLE_OPEN_ERROR, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
