package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.DrawerFocus
import com.arkiv.player.ui.live.DrawerIndex
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.filterChannels
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** Drawer's width. Leaves the video visible on the right: it's a drawer, not another screen. */
private val DRAWER_WIDTH = 560.dp
private val CATEGORIES_WIDTH = 200.dp
private val ITEM_HEIGHT = 52.dp

/**
 * The live channel drawer: opens with the left arrow over the video being watched, lists the
 * WHOLE catalog by categories, has a search box, and closes with right.
 *
 * It's a drawer and not the full guide ([TvLiveGuideScreen]) on purpose: the guide is a screen
 * you go to, and the point of this is to switch channels WITHOUT stopping watching what you're
 * watching. That's why it takes up a strip and the video keeps running next to it.
 *
 * The arrows are decided by [com.arkiv.player.ui.live.DrawerDpad], which is pure and covered by
 * tests: here it's just painted and focus is moved. That separation isn't ceremony — the project
 * has no UI tests, so a navigation rule written inside a composable can't be tested any way at
 * all.
 *
 * @param focus which column has focus; governed by the caller (the player), because it's the one
 *   that receives the remote's keys while the video has Android's focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvChannelDrawer(
    focus: DrawerFocus,
    onFocus: (DrawerFocus) -> Unit,
    onChooseChannel: (List<LiveChannel>, LiveChannel) -> Unit,
    currentChannel: String?,
) {
    val graph = rememberGraph()
    val vm: LiveViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LiveViewModel(
                    graph.liveCatalog, graph.database.liveFavoriteDao(),
                    graph.database.liveChannelCacheDao(),
                    // Read on EVERY load, not once: unlocking 18+ from Settings has to show up
                    // on returning to the screen, without restarting the app.
                    adultsUnlocked = { graph.settings.adultsUnlocked.value },
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val channels = remember(state.channels, search) { filterChannels(state.channels, search) }

    val categoriesFocus = remember { FocusRequester() }
    val channelsFocus = remember { FocusRequester() }
    val keyboardFocus = remember { FocusRequester() }

    // ONE single index to bring the row into view AND to decide which one carries the
    // FocusRequester. Used to be two separate effects -one scrolled to the live channel, the
    // other requested focus on item 0- and they fought: requesting focus on the first row drags
    // the whole list back to the start. With 1040 channels that looked like an endless scroll
    // upward that ended up far from the channel being watched. See [DrawerIndex].
    val channelsList = rememberLazyListState()
    val currentIndex = remember(channels, currentChannel) { DrawerIndex.indexFor(channels, currentChannel) }

    // Android's focus takes a while to exist: the row to go to may not be composed yet when
    // `focus` changes. It retries for a short while instead of requesting it just once -- same
    // pattern TvLiveGuideScreen already uses for its initial chip.
    LaunchedEffect(focus, currentIndex, channels.isEmpty()) {
        val target = when (focus) {
            DrawerFocus.CATEGORIES -> categoriesFocus
            DrawerFocus.CHANNELS -> if (channels.isEmpty()) categoriesFocus else channelsFocus
            DrawerFocus.KEYBOARD -> keyboardFocus
        }
        // Position BEFORE requesting focus, and without animating: a row that isn't composed
        // can't receive it, and the attempt makes the list jump to the one that is.
        if (target === channelsFocus) channelsList.scrollToItem(currentIndex)
        repeat(20) {
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(DRAWER_WIDTH)
            // Nearly opaque black, not entirely: lets the video behind be sensed and reminds you
            // haven't left to another screen.
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Column(Modifier.width(CATEGORIES_WIDTH).fillMaxHeight().padding(end = 12.dp)) {
            if (focus == DrawerFocus.KEYBOARD) {
                Text(
                    search.ifBlank { "Escribe para buscar…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (search.isBlank()) ArkivTextSecondary else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TvKeyboardWithNative(
                    text = search,
                    onTextChange = { search = it },
                    firstKeyFocus = keyboardFocus,
                )
            } else {
                DrawerItem(
                    label = if (search.isBlank()) "Buscar…" else "“$search”",
                    selected = search.isNotBlank(),
                    onClick = { onFocus(DrawerFocus.KEYBOARD) },
                    modifier = Modifier.focusRequester(categoriesFocus),
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item {
                        DrawerItem(
                            label = "Favoritos",
                            selected = state.activeCategory == CATEGORY_FAVORITES,
                            onClick = { vm.chooseCategory(CATEGORY_FAVORITES) },
                        )
                    }
                    items(state.categories, key = { it.id }) { cat ->
                        DrawerItem(
                            label = cat.name,
                            selected = state.activeCategory == cat.id,
                            onClick = { vm.chooseCategory(cat.id) },
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.error != null && state.channels.isEmpty() ->
                    DrawerMessage(state.error!!)
                state.loading && state.channels.isEmpty() ->
                    DrawerMessage("Cargando canales…")
                channels.isEmpty() && search.isNotBlank() ->
                    DrawerMessage("Sin resultados para “$search”")
                channels.isEmpty() ->
                    DrawerMessage("Sin canales en esta categoría")
                else -> LazyColumn(
                    state = channelsList,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(channels) { i, channel ->
                        DrawerChannelRow(
                            channel = channel,
                            onScreen = channel.code == currentChannel,
                            // Choosing changes the channel and closes: the caller decides both
                            // things. It's given the FILTERED list because that's the one
                            // up/down zapping has to go through after -- if you searched
                            // "deportes", zapping should move between those, not the whole
                            // catalog.
                            onClick = { onChooseChannel(channels, channel) },
                            modifier = if (i == currentIndex) Modifier.focusRequester(channelsFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** Lazy list's `itemsIndexed`, with the channel's stable key. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    channels: List<LiveChannel>,
    crossinline row: @Composable (Int, LiveChannel) -> Unit,
) = items(count = channels.size, key = { channels[it].code }) { i -> row(i, channels[i]) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ITEM_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ArkivSurface else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = if (selected) Color.White else ArkivTextSecondary,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // A RED bar on the left, not just the background. The "selected" background is
            // ArkivSurface (#181818) on a nearly-black drawer: 24 out of 255 difference, i.e.
            // invisible on a TV from three meters away. Noticed while using it -- "the category
            // doesn't look selected to me" -- and the comparison was exact: the on-screen channel
            // DOES stand out, because it has a red "● EN VIVO". This gives the category the same
            // signal, in the same visual language.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (selected) ArkivRed else Color.Transparent),
            )
            Box(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DrawerChannelRow(
    channel: LiveChannel,
    onScreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ITEM_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (onScreen) ArkivSurface else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channel.number > 0) {
                Text(
                    channel.number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.width(44.dp),
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (onScreen) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Marks which one is being watched: without this, with the drawer covering half the
            // screen you lose track of where you're standing.
            if (onScreen) {
                Text("● EN VIVO", style = MaterialTheme.typography.labelSmall, color = ArkivRed)
            }
        }
    }
}

@Composable
private fun DrawerMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}
