package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.live.DrawerAction
import com.arkiv.player.ui.live.DrawerDpad
import com.arkiv.player.ui.live.DrawerFocus
import com.arkiv.player.ui.live.currentProgram
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.TvChannelDrawer
import kotlinx.coroutines.delay

/** How long the channel card stays on screen after opening, zapping, or a tap. */
private const val CARD_VISIBLE_MS = 3000L

/**
 * The player's live mode: its own overlay and the TV's channel drawer.
 *
 * Live does NOT reuse VOD's `controlsVisible`/`interactionTick` — those govern the progress bar
 * and the transport row, which don't exist on a live stream. It has its own pair (visible +
 * tick), and that's why the whole block can be split out: nothing in VOD reads it.
 *
 * What did NOT move here is the video's key listener, which belongs to the screen: it mixes
 * zapping with VOD's keys and the overlay's focus, so pulling it out would have meant untangling
 * that knot too. [LiveState.openDrawer], [LiveState.showInfo], and [LiveState.toggleInfo] are
 * called from there.
 */
@Stable
internal class LiveState {
    /**
     * The channel card is on screen. Starts visible: the first channel announces itself, with no
     * need for the user to touch anything.
     */
    var infoVisible by mutableStateOf(true)
        private set

    /** Bumps with each announcement; relaunches the 3 s countdown, so zapping in a row sustains it. */
    var infoTick by mutableIntStateOf(0)
        private set

    /** The current channel's program, or null while there's no EPG. */
    var current by mutableStateOf<LiveProgram?>(null)
        private set

    /** The one that follows, with the same criterion. */
    var next by mutableStateOf<LiveProgram?>(null)
        private set

    /** The channel drawer is open (TV only). */
    var drawerOpen by mutableStateOf(false)
        private set

    /** Which column of the drawer has focus. The arrow rules live in [DrawerDpad]. */
    var drawerFocus by mutableStateOf(DrawerFocus.CHANNELS)
        private set

    /** VOD's `bump()` equivalent: announces the channel and resets the 3 s countdown. */
    fun showInfo() {
        infoVisible = true
        infoTick++
    }

    fun hideInfo() {
        infoVisible = false
    }

    /** A tap on the video: if the card is up it takes it out, and if not, it brings it. */
    fun toggleInfo() {
        if (infoVisible) hideInfo() else showInfo()
    }

    fun openDrawer() {
        drawerFocus = DrawerFocus.CHANNELS
        drawerOpen = true
        // The card would cover the drawer's footer, and besides, the drawer already says which
        // channel you're on.
        infoVisible = false
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun moveDrawerFocus(focus: DrawerFocus) {
        drawerFocus = focus
    }

    /**
     * The channel's "Now"/"Up next": requested best-effort straight from the gateway. Purely
     * informational for this overlay, not something the ViewModel needs in order to play, so it
     * isn't burdened with another dependency just for this.
     */
    suspend fun loadEpg(channel: LiveChannel?, liveApi: LiveCatalogGateway) {
        if (channel == null) return
        current = null
        next = null
        val epg = runCatching { liveApi.epg(listOf(channel.code)) }.getOrNull() ?: return
        val progs = epg.first[channel.code] ?: return
        val now = System.currentTimeMillis() / 1000
        val currentNow = currentProgram(progs, now)
        current = currentNow
        next = progs.firstOrNull { it.start >= (currentNow?.end ?: now) }
    }
}

@Composable
internal fun rememberLiveState(): LiveState = remember { LiveState() }

/**
 * Live mode's top band, phone only: the exit button and, on its other end, the cast buttons. The
 * "EN VIVO" badge that used to sit next to the back button was dropped -- redundant once you're
 * already watching a live channel -- which left this band with nothing to show on TV (its back
 * button and cast buttons were already phone-only), so the call site skips it there now instead
 * of composing an empty row.
 *
 * PERSISTENT, doesn't fade with the channel card — it's the screen's identity, not transient
 * information. Lives outside the card on purpose, same as VOD's Chromecast badge. Includes the
 * back button because with VOD's controls block hidden (`visible = !enVivo`), this is the ONLY
 * on-screen way to exit the player on the phone.
 *
 * [castButtons] is a slot: the screen puts the same DLNA/Chromecast buttons VOD uses in there,
 * which depend on state that isn't live's own.
 */
@Composable
internal fun BoxScope.LiveBanner(
    onBack: () -> Unit,
    castButtons: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().systemBarsPadding().padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        castButtons()
    }
}

/**
 * Channel card (number or logo, name, Now/Up next). TRANSIENT: shows for 3 s after opening,
 * zapping, or a tap, and goes away on its own.
 *
 * Brings its own two effects along —loading the channel's EPG and the 3 s countdown— because they
 * only compose in live mode and are of no use to anyone else.
 */
@Composable
internal fun BoxScope.ChannelCard(
    state: LiveState,
    channel: LiveChannel?,
    liveApi: LiveCatalogGateway,
) {
    LaunchedEffect(channel?.code) { state.loadEpg(channel, liveApi) }
    LaunchedEffect(state.infoTick) {
        delay(CARD_VISIBLE_MS)
        state.hideInfo()
    }

    AnimatedVisibility(
        visible = state.infoVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color(0x00000000), 1f to Color(0xD9000000)))
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurface),
                contentAlignment = Alignment.Center,
            ) {
                val logo = channel?.logo
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        (channel?.number ?: 0).toString(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    channel?.name.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Nothing if there's no EPG for this channel yet -- same criterion as the
                // grid/guide (LiveScreen/TvLiveGuideScreen): no fixed placeholder, no "loading".
                state.current?.let { p -> ProgramLine("Ahora: ${p.title}", Color.White.copy(alpha = 0.85f)) }
                state.next?.let { p -> ProgramLine("A continuación: ${p.title}", ArkivTextSecondary) }
            }
        }
    }
}

@Composable
private fun ProgramLine(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Live mode's channel drawer (TV only). Goes LAST inside the screen's Box so it stays above the
 * rest of the overlays -- and on the left, leaving the video visible to its right: it's a drawer,
 * not another screen.
 */
@Composable
internal fun BoxScope.LiveChannelDrawer(
    state: LiveState,
    currentChannel: String?,
    onChooseChannel: (List<LiveChannel>, LiveChannel) -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            // PREVIEW and not onKeyEvent: the preview comes down from the container BEFORE the
            // row that has focus keeps the key, which is the only way for "right" to close the
            // drawer instead of the list eating it.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (DrawerDpad.action(e.key.nativeKeyCode, open = true, focus = state.drawerFocus)) {
                    DrawerAction.CLOSE -> { state.closeDrawer(); true }
                    DrawerAction.TO_CHANNELS -> { state.moveDrawerFocus(DrawerFocus.CHANNELS); true }
                    DrawerAction.TO_CATEGORIES -> { state.moveDrawerFocus(DrawerFocus.CATEGORIES); true }
                    // From the list: let Compose's focus resolve it. `false` lets it continue;
                    // consuming it here would leave the list stuck.
                    else -> false
                }
            },
    ) {
        TvChannelDrawer(
            focus = state.drawerFocus,
            onFocus = { state.moveDrawerFocus(it) },
            currentChannel = currentChannel,
            onChooseChannel = { list, channel ->
                onChooseChannel(list, channel)
                state.closeDrawer()
                state.showInfo()
            },
        )
    }
}
