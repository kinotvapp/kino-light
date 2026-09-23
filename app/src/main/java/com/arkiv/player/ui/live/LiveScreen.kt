package com.arkiv.player.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Views that don't come from the gateway: built from local data (Room), not [LiveViewModel.chooseCategory]. */
private enum class LocalView { NONE, RECENT }

/**
 * "Live" tab: channel grid with search box, categories (with Favorites/Recent first), and "now
 * on screen". It's the section's first piece of UI, so it takes care of the two states that
 * matter: it opens instantly with what's cached, and doesn't leave a raw error if the gateway is
 * slow or down.
 *
 * Doesn't require a linked Magis account: the channel catalog uses the gateway's anonymous
 * session (by device serial number, same as the magic CLI) when there's no linked account -- see
 * `MagisSession` in the gateway. If the user DOES have a linked account, the GATEWAY resolves it
 * on its own, from the authenticated session (the client no longer needs to send the accountId
 * as a header -- that allowed requesting with someone else's Magis account), with this screen not
 * needing to know anything about it.
 *
 * [onOpenChannel] receives the tapped channel's code; the caller (`ArkivRoot`) decides what to do
 * with that code -- today, navigate to the player in live mode (Task 14). Before invoking it,
 * `open()` sets in [LiveZappingSource] the list it was entered with (so the player's zapping can
 * go through it), so this screen doesn't need to know anything about the player.
 *
 * Until Task 5, tapping a channel with a paired TV opened a destination dialog ("This phone" /
 * "On the TV", same pattern as VOD's `playChoice` in `ArkivRoot`) -- removed along with the rest
 * of pairing/remote control in the "Arkiv Light" pruning. Tapping always opens here now.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onOpenChannel: (String) -> Unit,
    contentPadding: PaddingValues,
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
    var view by remember { mutableStateOf(LocalView.NONE) }
    // rememberSaveable: the brief asks for the mode to survive rotation (a configuration change
    // recomposes the whole screen from scratch, and with `remember` it would always go back to
    // the grid).
    var guideMode by rememberSaveable { mutableStateOf(false) }

    // Recent: doesn't go through LiveViewModel.chooseCategory (it isn't a portal category), read
    // directly from Room. With no number/logo of their own (Task 10 doesn't store them for
    // "recent"), so they get enriched with whatever's already loaded in `state.channels`, if the
    // channel shows up there.
    val recentDao = remember { graph.database.liveRecentDao() }
    val rawRecents by recentDao.flowRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val recents = remember(rawRecents, state.channels) {
        rawRecents.map { r ->
            state.channels.find { it.code == r.code }?.copy(name = r.nombre)
                ?: LiveChannel(r.code, r.nombre, 0, null)
        }
    }
    LaunchedEffect(recents) {
        if (recents.isNotEmpty()) vm.requestEpg(recents.map { it.code })
    }

    // Preheat favorites (bounded): they're the channels most likely to be opened next, and
    // resolving costs ~3s (see LiveController) -- having them already resolved by the time the
    // live player exists (Task 14) is free and best-effort (preheat() never throws).
    LaunchedEffect(state.favorites) {
        state.favorites.take(5).forEach { code -> launch { graph.liveController.preheat(code) } }
    }

    // The list "entered with" (category/favorites, or recent) -- Task 14: it's the one the
    // player's zapping goes through, not the full catalog. Set in LiveZappingSource BEFORE
    // opening: a list of LiveChannel doesn't cross the navigation route (a String) well, see
    // LiveZappingSource's KDoc (LiveZapping.kt).
    val activeList = if (view == LocalView.RECENT) filterChannels(recents, state.search) else state.visible
    fun open(channel: LiveChannel) {
        LiveZappingSource.list = activeList
        onOpenChannel(channel.code)
    }
    fun favorite(channel: LiveChannel) = vm.toggleFavorite(channel)

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = vm::search,
                placeholder = { Text("Buscar por nombre o número…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.search.isNotEmpty()) {
                        IconButton(onClick = { vm.search("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { guideMode = !guideMode }) {
                Icon(
                    imageVector = if (guideMode) Icons.Default.GridView else Icons.Default.ViewAgenda,
                    contentDescription = if (guideMode) "Ver como grilla" else "Ver guía de programación",
                    tint = if (guideMode) ArkivRed else ArkivTextSecondary,
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CategoryChip(
                    label = "Favoritos",
                    icon = Icons.Default.Star,
                    selected = view == LocalView.NONE && state.activeCategory == CATEGORY_FAVORITES,
                    onClick = { view = LocalView.NONE; vm.chooseCategory(CATEGORY_FAVORITES) },
                )
            }
            item {
                CategoryChip(
                    label = "Recientes",
                    icon = Icons.Default.History,
                    selected = view == LocalView.RECENT,
                    onClick = { view = LocalView.RECENT },
                )
            }
            items(state.categories, key = { it.id }) { cat ->
                CategoryChip(
                    label = cat.name,
                    icon = null,
                    selected = view == LocalView.NONE && state.activeCategory == cat.id,
                    onClick = { view = LocalView.NONE; vm.chooseCategory(cat.id) },
                )
            }
        }

        // Subtle refresh notice: the grid already has something painted (cache or a previous
        // load), so a full-screen spinner would be worse than saying nothing -- just a thin bar
        // up top.
        if (state.loading && (view == LocalView.NONE && state.channels.isNotEmpty())) {
            LinearProgressIndicator(color = ArkivRed, modifier = Modifier.fillMaxWidth())
        }

        val gridPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )

        when {
            view == LocalView.RECENT -> {
                val visible = filterChannels(recents, state.search)
                if (visible.isEmpty()) {
                    EmptyState(
                        "Sin canales recientes",
                        "Los canales que abras van a aparecer acá.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (guideMode) {
                    LiveGuideList(visible, state.programming, ::open, vm::requestEpg, gridPadding)
                } else {
                    ChannelGrid(visible, state.current, state.favorites, gridPadding, ::open, ::favorite)
                }
            }
            state.error != null && state.channels.isEmpty() -> {
                ErrorWithRetry(state.error!!) { vm.chooseCategory(state.activeCategory) }
            }
            state.loading && state.channels.isEmpty() -> {
                PlaceholderGrid(gridPadding)
            }
            state.visible.isEmpty() -> {
                val (title, subtitle) = when {
                    state.search.isNotBlank() -> "Sin resultados" to "Prueba con otro nombre o número de canal."
                    state.activeCategory == CATEGORY_FAVORITES -> "Sin favoritos todavía" to
                        "Mantén pulsado un canal para agregarlo."
                    else -> "Sin canales" to "No encontramos canales en esta categoría."
                }
                EmptyState(title, subtitle, modifier = Modifier.fillMaxSize())
            }
            guideMode -> LiveGuideList(state.visible, state.programming, ::open, vm::requestEpg, gridPadding)
            else -> ChannelGrid(state.visible, state.current, state.favorites, gridPadding, ::open, ::favorite)
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ArkivRed,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
    )
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary, textAlign = TextAlign.Center)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Reintentar") }
    }
}

@Composable
private fun ChannelGrid(
    channels: List<LiveChannel>,
    current: Map<String, LiveProgram?>,
    favorites: Set<String>,
    contentPadding: PaddingValues,
    onOpen: (LiveChannel) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(2, isLandscapeTablet())),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(channels, key = { it.code }) { channel ->
            ChannelCard(
                channel = channel,
                currentProgram = current[channel.code],
                isFavorite = channel.code in favorites,
                onClick = { onOpen(channel) },
                onLongClick = { onFavorite(channel) },
            )
        }
    }
}

/** Placeholder grid while the first load is in flight (no cache yet to show). */
@Composable
private fun PlaceholderGrid(contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(2, isLandscapeTablet())),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(8) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArkivSurfaceHigh),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    currentProgram: LiveProgram?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            } else {
                // Not confirmed that the portal sends logos: without one, the same visual
                // treatment as CardPlaceholder (ui/tv/TvComponents.kt) -- dark gradient -- but
                // with the channel number instead of the generic icon, so the card looks
                // deliberate and not like a broken logo.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = channel.number.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorito",
                    tint = ArkivRed,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        // "Now on screen": nothing if there's no EPG for this channel yet (never a fixed hole or
        // a per-card blinking "loading" -- see the brief).
        if (currentProgram != null) {
            Text(
                text = "Ahora: ${currentProgram.title}",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(programProgress(currentProgram))
                        .fillMaxSize()
                        .background(ArkivRed),
                )
            }
        }
    }
}
