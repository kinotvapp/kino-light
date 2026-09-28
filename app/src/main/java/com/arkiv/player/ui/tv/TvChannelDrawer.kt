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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.DrawerFocus
import com.arkiv.player.ui.live.DrawerIndex
import com.arkiv.player.ui.live.FAVORITE_HINT
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.drawerProviderFor
import com.arkiv.player.ui.live.favoriteNotice
import com.arkiv.player.ui.live.filterChannels
import com.arkiv.player.ui.live.neighborAfterRemoval
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
 * A favourite's star on the TV's channel rows (drawer and guide). Amber, not the phone's red: a
 * focused row IS red here, and a red star on it would vanish exactly when you're looking at it.
 */
internal val FAVORITE_STAR = Color(0xFFFFC83D)

/**
 * The live channel drawer: opens with the left arrow over the video being watched, lists the
 * on-screen channel's provider's catalog by categories, has a search box, and closes with right.
 *
 * Locked to that ONE provider (R6, [drawerProviderFor]): its categories, its favourites and its
 * search, never another provider's -- so choosing from here, and the zapping that follows, stays
 * within the provider being watched. No provider badge: it shows one provider. Its model is keyed
 * per provider, so a channel from another provider opened later gets a drawer of its own.
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
    currentChannel: LiveChannel?,
) {
    val graph = rememberGraph()
    val provider = drawerProviderFor(currentChannel)
    val currentLiveCode = currentChannel?.liveCode
    val vm: LiveViewModel = viewModel(
        key = "drawer:$provider",
        factory = viewModelFactory {
            initializer {
                LiveViewModel(
                    graph.liveModule, graph.database.liveFavoriteDao(),
                    graph.database.liveChannelCacheDao(),
                    // Read on EVERY load, not once: unlocking 18+ from Settings has to show up
                    // on returning to the screen, without restarting the app.
                    adultsUnlocked = { graph.settings.adultsUnlocked.value },
                    onlyProvider = provider,
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
    val currentIndex = remember(channels, currentLiveCode) { DrawerIndex.indexFor(channels, currentLiveCode) }

    // Favourites from the remote: a long OK on a row opens TvFavoriteDialog (tv-material's
    // `onLongClick`, fired by the key's first repeat, which also swallows that press's click so
    // the channel isn't opened); its confirm button stars or unstars the channel, and focus comes
    // back to the same row (`returnCode`). Unstarring while "Favoritos" is on screen removes the row
    // (LiveViewModel.toggleFavorite's `dropFromFavoritesList`), and Compose clears focus with the
    // removed node: `refocusCode` is the row that takes it instead (neighborAfterRemoval), and
    // `refocusPending` keeps the landing effect below from yanking focus back to the on-screen
    // channel's row in the same frame (the removal shifts `currentIndex`).
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(noticeTick) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    val refocusRow = remember { FocusRequester() }
    var refocusCode by remember { mutableStateOf<String?>(null) }
    var removedCode by remember { mutableStateOf<String?>(null) }
    var refocusPending by remember { mutableStateOf(false) }
    var confirmChannel by remember { mutableStateOf<LiveChannel?>(null) }
    val returnRow = remember { FocusRequester() }
    var returnCode by remember { mutableStateOf<String?>(null) }
    fun toggleFavorite(channel: LiveChannel) {
        val wasFavorite = channel.liveCode in state.favorites
        if (wasFavorite && state.activeCategory == CATEGORY_FAVORITES) {
            removedCode = channel.liveCode
            refocusCode = neighborAfterRemoval(channels.map { it.liveCode }, channel.liveCode)
            refocusPending = true
        }
        vm.toggleFavorite(channel, dropFromFavoritesList = true)
        notice = favoriteNotice(added = !wasFavorite)
        noticeTick++
    }
    // The dialog closed without removing its row: hand focus back to that row. (When the row did
    // leave the list, the effect below moves focus instead, and this one stands down.)
    LaunchedEffect(confirmChannel, returnCode) {
        if (confirmChannel != null) return@LaunchedEffect
        val code = returnCode ?: return@LaunchedEffect
        if (code == removedCode) { returnCode = null; return@LaunchedEffect }
        repeat(20) {
            if (runCatching { returnRow.requestFocus() }.isSuccess) {
                returnCode = null
                return@LaunchedEffect
            }
            delay(50)
        }
        returnCode = null
    }
    LaunchedEffect(channels, removedCode) {
        val gone = removedCode ?: return@LaunchedEffect
        // Still listed: the model hasn't pruned it yet (the write is async).
        if (channels.any { it.liveCode == gone }) return@LaunchedEffect
        removedCode = null
        if (refocusCode == null) {
            // It was the last favourite: the list is empty, so the categories column takes focus
            // (and the drawer's own state says so, or the arrows would read the wrong column).
            refocusPending = false
            onFocus(DrawerFocus.CATEGORIES)
            return@LaunchedEffect
        }
        repeat(20) {
            if (runCatching { refocusRow.requestFocus() }.isSuccess) {
                refocusPending = false
                refocusCode = null
                return@LaunchedEffect
            }
            delay(50)
        }
        refocusPending = false
    }

    // Android's focus takes a while to exist: the row to go to may not be composed yet when
    // `focus` changes. It retries for a short while instead of requesting it just once -- same
    // pattern TvLiveGuideScreen already uses for its initial chip.
    LaunchedEffect(focus, currentIndex, channels.isEmpty()) {
        // A favourite just left the list under focus: the effect above hands focus to its
        // neighbour, not to the on-screen channel's row.
        if (refocusPending && focus == DrawerFocus.CHANNELS) return@LaunchedEffect
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
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    // Its provider left the module (the player stops the channel with its own message).
                    state.moduleEmpty ->
                        DrawerMessage("Este proveedor ya no está disponible")
                    state.error != null && state.channels.isEmpty() ->
                        DrawerMessage(state.error!!)
                    state.loading && state.channels.isEmpty() ->
                        DrawerMessage("Cargando canales…")
                    channels.isEmpty() && search.isNotBlank() ->
                        DrawerMessage("Sin resultados para “$search”")
                    channels.isEmpty() && state.activeCategory == CATEGORY_FAVORITES ->
                        DrawerMessage("Aún no tienes favoritos")
                    channels.isEmpty() ->
                        DrawerMessage("Sin canales en esta categoría")
                    else -> LazyColumn(
                        state = channelsList,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        itemsIndexed(channels) { i, channel ->
                            var rowModifier: Modifier = if (i == currentIndex) Modifier.focusRequester(channelsFocus) else Modifier
                            if (channel.liveCode == refocusCode) rowModifier = rowModifier.focusRequester(refocusRow)
                            if (channel.liveCode == returnCode) rowModifier = rowModifier.focusRequester(returnRow)
                            DrawerChannelRow(
                                channel = channel,
                                onScreen = channel.liveCode == currentLiveCode,
                                isFavorite = channel.liveCode in state.favorites,
                                // Choosing changes the channel and closes: the caller decides both
                                // things. It's given the FILTERED list because that's the one
                                // up/down zapping has to go through after -- if you searched
                                // "deportes", zapping should move between those, not the whole
                                // catalog.
                                onClick = { onChooseChannel(channels, channel) },
                                onLongClick = {
                                    returnCode = channel.liveCode
                                    confirmChannel = channel
                                },
                                modifier = rowModifier,
                            )
                        }
                    }
                }
            }
            // The only way to learn the long OK exists: nothing on a TV row can be hovered or
            // tapped to discover it. Swapped for the confirmation for a moment after a toggle.
            Text(
                notice ?: FAVORITE_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = if (notice != null) Color.White else ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    confirmChannel?.let { channel ->
        TvFavoriteDialog(
            channelName = channel.name,
            isFavorite = channel.liveCode in state.favorites,
            onConfirm = {
                toggleFavorite(channel)
                confirmChannel = null
            },
            onDismiss = { confirmChannel = null },
        )
    }
}

/** How long "Agregado a favoritos" / "Quitado de favoritos" replaces the hint. */
private const val NOTICE_MS = 2000L

/** Lazy list's `itemsIndexed`, with the channel's stable key. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    channels: List<LiveChannel>,
    crossinline row: @Composable (Int, LiveChannel) -> Unit,
) = items(count = channels.size, key = { channels[it].liveCode }) { i -> row(i, channels[i]) }

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
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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
            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorito",
                    tint = FAVORITE_STAR,
                    modifier = Modifier.padding(horizontal = 6.dp).size(18.dp),
                )
            }
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
