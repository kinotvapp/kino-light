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
import androidx.compose.material.icons.filled.Refresh
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
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.live.LiveProviderTab
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
 * Several providers (Xuper and plugins with channels, see `AppGraph.liveModule`): a chip row
 * picks the provider whose categories show, and every card carries its provider's badge, so a
 * favourites/recents list mixing providers says where each channel comes from (ruling R6: both
 * only when there is more than one provider). The search field searches every provider at once
 * (Amendment A1, [LiveViewModel.crossSearch]). The guide toggle exists only while the active
 * provider has programme data ([LiveUiState.hasGuide]).
 *
 * [onOpenChannel] receives the tapped channel's live code; the caller (`ArkivRoot`) decides what to do
 * with it -- today, navigate to the player in live mode (`live:<liveCode>`). Before invoking it,
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
                    graph.liveModule, graph.database.liveFavoriteDao(),
                    graph.database.liveChannelCacheDao(),
                    // Read on EVERY load, not once: unlocking 18+ from Settings has to show up
                    // on returning to the screen, without restarting the app.
                    adultsUnlocked = { graph.settings.adultsUnlocked.value },
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val cross by vm.crossSearch.collectAsStateWithLifecycle()
    val searchView = remember(state.search, cross) { liveSearchView(state.search, cross) }
    var view by remember { mutableStateOf(LocalView.NONE) }
    // rememberSaveable: the brief asks for the mode to survive rotation (a configuration change
    // recomposes the whole screen from scratch, and with `remember` it would always go back to
    // the grid).
    var guideMode by rememberSaveable { mutableStateOf(false) }
    // A provider without a guide (plugin with no EPG) has no toggle: back to the grid.
    LaunchedEffect(state.hasGuide) { if (!state.hasGuide) guideMode = false }

    // Recent: doesn't go through LiveViewModel.chooseCategory (it isn't a provider category),
    // read directly from Room, only for providers still in the module (see recentsForScreen).
    val recentDao = remember { graph.database.liveRecentDao() }
    val rawRecents by recentDao.flowRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val recents = remember(rawRecents, state.channels, state.providers) {
        recentsForScreen(rawRecents, state.channels, state.providers.map { it.id }.toSet())
    }
    LaunchedEffect(recents) {
        if (recents.isNotEmpty()) vm.requestEpg(recents)
    }

    // Preheat favorites (bounded): they're the channels most likely to be opened next, and
    // resolving costs ~3s (see LiveController) -- having them already resolved by the time the
    // live player exists (Task 14) is free and best-effort (preheat() never throws).
    LaunchedEffect(state.favorites) {
        // Only Xuper's: preheating is its session machinery; a plugin channel has none.
        state.favorites.mapNotNull { LiveChannelKeys.parse(it) }.filter { it.first == LiveChannelKeys.XUPER }
            .take(5).forEach { (_, code) -> launch { graph.liveController.preheat(code) } }
    }

    // The list "entered with" (category/favorites, or recent) -- Task 14: it's the one the
    // player's zapping goes through, not the full catalog. Set in LiveZappingSource BEFORE
    // opening: a list of LiveChannel doesn't cross the navigation route (a String) well, see
    // LiveZappingSource's KDoc (LiveZapping.kt).
    // A non-blank query never filters these: it shows the merged search across providers instead
    // (Amendment A1, see SearchResults below).
    val visible = state.channels
    val visibleRecents = recents
    val searchResults = (searchView as? LiveSearchView.Results)?.channels
    // A search result zaps within its OWN provider: the player narrows this list (zappingListFor).
    val activeList = searchResults ?: if (view == LocalView.RECENT) visibleRecents else visible
    fun open(channel: LiveChannel) {
        LiveZappingSource.list = activeList
        onOpenChannel(channel.liveCode)
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
            IconButton(onClick = { vm.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Recargar canales", tint = ArkivTextSecondary)
            }
            if (state.hasGuide) {
                IconButton(onClick = { guideMode = !guideMode }) {
                    Icon(
                        imageVector = if (guideMode) Icons.Default.GridView else Icons.Default.ViewAgenda,
                        contentDescription = if (guideMode) "Ver como grilla" else "Ver guía de programación",
                        tint = if (guideMode) ArkivRed else ArkivTextSecondary,
                    )
                }
            }
        }

        if (state.moduleEmpty) {
            // Defensive: ArkivRoot already leaves the tab when the module empties.
            EmptyState(
                "Sin canales en vivo",
                "Instala un plugin con canales o activa Xuper en Ajustes ▸ Plugins.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        val gridPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )

        // Amendment A1: a non-blank query is one merged list over every provider, with its badges.
        if (searchView != LiveSearchView.Off) {
            SearchResults(searchView, state, gridPadding, ::open, ::favorite)
            return@Column
        }

        if (state.showProviders) {
            val lit = selectedProviderChip(state, recentView = view == LocalView.RECENT)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                items(state.providers, key = { it.id }) { tab ->
                    CategoryChip(
                        label = tab.name,
                        icon = null,
                        selected = lit == tab.id,
                        onClick = { view = LocalView.NONE; vm.chooseProvider(tab.id) },
                    )
                }
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

        // The active provider's own note (a plugin list cut to the caps): "Lista recortada: …".
        state.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            view == LocalView.RECENT -> {
                val visible = visibleRecents
                if (visible.isEmpty()) {
                    EmptyState(
                        "Sin canales recientes",
                        "Los canales que abras van a aparecer acá.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (guideMode) {
                    LiveGuideList(visible, state.programming, ::open, vm::requestEpg, gridPadding)
                } else {
                    ChannelGrid(visible, state.current, state.favorites, gridPadding, ::open, ::favorite, state::tabOf)
                }
            }
            state.error != null && state.channels.isEmpty() -> {
                ErrorWithRetry(state.error!!) { vm.retry() }
            }
            state.loading && state.channels.isEmpty() -> {
                PlaceholderGrid(gridPadding)
            }
            visible.isEmpty() -> {
                val (title, subtitle) = when {
                    state.activeCategory == CATEGORY_FAVORITES -> "Sin favoritos todavía" to
                        "Mantén pulsado un canal para agregarlo."
                    else -> "Sin canales" to "No encontramos canales en esta categoría."
                }
                EmptyState(title, subtitle, modifier = Modifier.fillMaxSize())
            }
            guideMode -> LiveGuideList(visible, state.programming, ::open, vm::requestEpg, gridPadding)
            else -> ChannelGrid(visible, state.current, state.favorites, gridPadding, ::open, ::favorite, state::tabOf)
        }
    }
}

/**
 * The merged search across every provider (Amendment A1): the results grid (each card with its
 * provider's badge when there is more than one provider), "Sin resultados", or a thin bar while
 * the first answer is computed; under it, which providers were not fully searched.
 */
@Composable
private fun SearchResults(
    view: LiveSearchView,
    state: LiveUiState,
    gridPadding: PaddingValues,
    onOpen: (LiveChannel) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
) {
    val note = when (view) {
        is LiveSearchView.Results -> view.note
        is LiveSearchView.NoResults -> view.note
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (view) {
            is LiveSearchView.Results ->
                ChannelGrid(view.channels, state.current, state.favorites, gridPadding, onOpen, onFavorite, state::tabOf)
            is LiveSearchView.NoResults ->
                EmptyState("Sin resultados", "Prueba con otro nombre o número de canal.", modifier = Modifier.fillMaxSize())
            else -> LinearProgressIndicator(color = ArkivRed, modifier = Modifier.fillMaxWidth())
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
    badgeFor: (LiveChannel) -> LiveProviderTab?,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(2, isLandscapeTablet())),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(channels, key = { it.liveCode }) { channel ->
            ChannelCard(
                channel = channel,
                currentProgram = current[channel.liveCode],
                isFavorite = channel.liveCode in favorites,
                badge = badgeFor(channel),
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
    badge: LiveProviderTab?,
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
            badge?.let { ProviderBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
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
