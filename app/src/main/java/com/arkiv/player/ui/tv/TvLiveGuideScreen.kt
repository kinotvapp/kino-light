package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.filterChannels
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** Height of each channel row -- large on purpose, so it's recognizable from three meters away. */
private val ROW_HEIGHT = 76.dp

/** Fixed width of the search panel (keyboard), same criterion as the keyboard column in TvSearchScreen. */
private val SEARCH_WIDTH = 340.dp

/** Local views that don't come from the gateway -- same pattern as `LiveScreen` (mobile). */
private enum class TvLocalView { NONE, RECENT }

/**
 * The TV's "Live" screen: find a channel and put it on. It's a list of channels (category chips
 * + search box + rows), not a programming guide.
 *
 * ROOT CAUSE of the redesign (verified against the real portal and against Magis's decompiled
 * APK, including the request equivalent to the official app's): `programList` ALWAYS comes back
 * empty -- the platform doesn't serve programming, only a sports schedule (which is a different
 * thing). This screen's previous version was a channel×hour guide with focus and click placed on
 * the program blocks (`TvBloquePrograma`); with no programming those blocks never existed, so
 * there was nothing to focus and nothing to play with OK, and the rows all looked the same
 * because focus lived on an element that never got painted. The owner asked for it explicitly:
 * "if there's no programming let's not waste effort on it, not on the TV or the phone". The
 * timeline, hour header, "now" line, and per-row EPG request were removed here -- all of it was,
 * in practice, dead code (it never had data to show). The phone is NOT touched: its guide
 * (`LiveGuideList.kt`) already handles "no programming" with a text and keeps working through its
 * own "Ver ahora" button, so it doesn't share this screen's focus/click problem and there was
 * nothing to fix there. `currentProgram`/`progressOf` (`LiveGuideList.kt`) are still used there
 * unchanged; `anchoDp`/`ventanaDe` (the timeline's helpers, used only by THIS screen) were removed
 * along with their tests.
 *
 * Focus with the remote -- all FOUR directions are covered by Compose's standard navigation
 * between `focusable`/`Surface` elements, with no manual escapes (not needed: there are no more
 * horizontal timelines to "escape" from):
 * - Up/Down: inside the keyboard (grid), inside the chips (a single row, doesn't move), and
 *   between chips ↔ first channel row ↔ the rest of the `LazyColumn`'s rows -- Compose looks for
 *   the closest focus in that direction, and since everything is stacked vertically in a narrow
 *   column the result is predictable.
 * - Left/Right: between the keyboard panel (fixed column on the left) and the chips+rows panel
 *   (on the right) -- same 2D search mechanism `TvSearchScreen` already uses to move between its
 *   keyboard and its results grid, with no extra code.
 * The initial focus is the first chip ("Favoritos"): entering the screen must show something
 * navigable right away, not start parked on the keyboard.
 *
 * Playing: the ROW is the focusable and clickable element (not a sub-block inside it). Pressing
 * OK on a row calls `onWatchChannel` directly -- works whether there's programming or not,
 * because it no longer depends on programming existing.
 *
 * Search: reuses `TvKeyboard` (same visual and focus pattern as `TvSearchScreen`) and
 * `filterChannels` (`LiveViewModel.kt`, already filters by name without accents/case and by exact
 * number -- the same function the phone's guide uses). Unlike `TvSearchScreen` (which searches
 * TMDB over the network and so waits for the "Buscar" button), here the filter is over the
 * ALREADY loaded in-memory channel list -- it filters on every key, with no network round trip to
 * justify a button.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLiveGuideScreen(onWatchChannel: (LiveChannel) -> Unit, onBack: () -> Unit) {
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

    var view by remember { mutableStateOf(TvLocalView.NONE) }

    BackHandler { onBack() }

    // Recent: same as LiveScreen (mobile) -- read directly from Room, with no number/logo of its
    // own, enriched with whatever's already loaded in state.channels if the channel shows up there.
    val recentDao = remember { graph.database.liveRecentDao() }
    val rawRecents by recentDao.flowRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val recents = remember(rawRecents, state.channels) {
        rawRecents.map { r ->
            state.channels.find { it.code == r.code }?.copy(name = r.nombre)
                ?: LiveChannel(r.code, r.nombre, 0, null)
        }
    }

    val baseChannels = if (view == TvLocalView.RECENT) recents else state.channels

    // Search filters on top of the active category/view, same as LiveScreen (mobile): searching
    // doesn't replace the chosen category, it narrows it.
    var search by remember { mutableStateOf("") }
    val channels = remember(baseChannels, search) { filterChannels(baseChannels, search) }

    // Watch the channel now: sets in LiveZappingSource the FILTERED list (the one the user is
    // looking at right now) BEFORE delegating to `onWatchChannel` -- it's the one the player's
    // zapping goes through. Same criterion as `LiveScreen.open` (mobile): if there's an active
    // search, zapping goes through the search results, not the whole category.
    fun watchChannel(channel: LiveChannel) {
        LiveZappingSource.list = channels
        onWatchChannel(channel)
    }

    // Screen's initial focus: the first chip ("Favoritos"), so entering the screen shows
    // something navigable right away instead of starting parked on the keyboard.
    val chipsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { chipsFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack)
            .padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(
            "En vivo",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(Modifier.fillMaxSize()) {
            // --- Left panel: search box (same visual pattern as TvSearchScreen). ---
            Column(modifier = Modifier.fillMaxHeight().width(SEARCH_WIDTH).padding(end = 24.dp)) {
                Text(
                    search.ifBlank { "Buscar canal por nombre o número…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (search.isBlank()) ArkivTextSecondary else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TvKeyboardWithNative(text = search, onTextChange = { search = it })
                if (search.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { search = "" },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = arkivTvSurfaceColors(),
                        border = arkivTvSurfaceBorder(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Borrar búsqueda", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // --- Right panel: category chips + channel list. ---
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyRow(
                    contentPadding = PaddingValues(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        TvCategoryChip(
                            label = "Favoritos",
                            icon = Icons.Default.Star,
                            selected = view == TvLocalView.NONE && state.activeCategory == CATEGORY_FAVORITES,
                            onClick = { view = TvLocalView.NONE; vm.chooseCategory(CATEGORY_FAVORITES) },
                            modifier = Modifier.focusRequester(chipsFocus),
                        )
                    }
                    item {
                        TvCategoryChip(
                            label = "Recientes",
                            icon = Icons.Default.History,
                            selected = view == TvLocalView.RECENT,
                            onClick = { view = TvLocalView.RECENT },
                        )
                    }
                    items(state.categories, key = { it.id }) { cat ->
                        TvCategoryChip(
                            label = cat.name,
                            icon = null,
                            selected = view == TvLocalView.NONE && state.activeCategory == cat.id,
                            onClick = { view = TvLocalView.NONE; vm.chooseCategory(cat.id) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                when {
                    view == TvLocalView.RECENT && baseChannels.isEmpty() ->
                        TvGuideMessage("Sin canales recientes", "Los canales que abras van a aparecer acá.")
                    state.error != null && state.channels.isEmpty() ->
                        TvGuideMessage(state.error!!, "Presiona OK para reintentar.") { vm.chooseCategory(state.activeCategory) }
                    state.loading && state.channels.isEmpty() ->
                        TvGuideMessage("Cargando canales…", null)
                    search.isNotBlank() && channels.isEmpty() ->
                        TvGuideMessage("Sin resultados", "Prueba con otro nombre o número de canal.")
                    baseChannels.isEmpty() ->
                        TvGuideMessage("Sin canales", "No encontramos canales en esta categoría.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(end = 24.dp, bottom = 16.dp),
                    ) {
                        items(channels, key = { it.code }) { channel ->
                            TvChannelRow(
                                channel = channel,
                                onClick = { watchChannel(channel) },
                                modifier = Modifier.height(ROW_HEIGHT),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A channel row: the screen's focusable and clickable element (no longer a program sub-block
 * inside it). Pressing OK plays the channel directly, whether there's programming or not.
 *
 * `Surface` (tv-material3), not a `Box` + `clickable` + `focusable` by hand like the previous
 * program block had: it's the same component `TvRefineRow`/`TvSeasonChip` already use for
 * navigable rows, and the focus (red background + 3dp white border) reads clearly from three
 * meters away.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChannelRow(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurface,
            focusedContainerColor = ArkivRed,
            pressedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Logo -- already works (posterList[].fileUrl): it's the fastest way to recognize a
            // channel at a glance. With no logo, the number acts as a stand-in (same criterion as
            // ChannelCard/GuideChannelRow, which already handle this fallback).
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        channel.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Column {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Number always visible, not just as the logo's fallback: it's what the search
                // box matches by exact number, so it's worth seeing even when the logo is there too.
                Text(
                    "Canal ${channel.number}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArkivTextSecondary,
                )
            }
        }
    }
}

/** Category chip with the same manual visual treatment as `TvSourceChip` (TvEpisodeChip.kt). */
@Composable
private fun TvCategoryChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) ArkivRed else if (focused) ArkivSurfaceHigh else ArkivSurface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** Simple centered message, with an optional retry -- for "no channels"/"loading"/error/empty search. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvGuideMessage(title: String, subtitle: String?, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Reintentar") }
        }
    }
}
