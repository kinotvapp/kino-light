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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
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
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.live.LiveProviderTab
import com.arkiv.player.data.live.OwnLive
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.FAVORITE_HINT
import com.arkiv.player.ui.live.LiveSearchView
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.OwnSourcesCopy
import com.arkiv.player.ui.live.OwnSourcesViewModel
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.ProviderBadge
import com.arkiv.player.ui.live.favoriteNotice
import com.arkiv.player.ui.live.neighborAfterRemoval
import com.arkiv.player.ui.live.TvGuideFocus
import com.arkiv.player.ui.live.listOnScreen
import com.arkiv.player.ui.live.liveSearchView
import com.arkiv.player.ui.live.recentsForScreen
import com.arkiv.player.ui.live.selectedProviderChip
import com.arkiv.player.ui.live.tvGuideFocusTarget
import com.arkiv.player.ui.live.tvGuideShouldLand
import com.arkiv.player.ui.player.WAIT_BETWEEN_FOCUS_ATTEMPTS_MS
import com.arkiv.player.ui.player.retryFocus
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
 * The initial focus is the first provider chip, or "Favoritos" with one provider: entering the
 * screen must show something navigable right away, not start parked on the keyboard.
 *
 * Playing: the ROW is the focusable and clickable element (not a sub-block inside it). Pressing
 * OK on a row calls `onWatchChannel` directly -- works whether there's programming or not,
 * because it no longer depends on programming existing.
 *
 * Search: reuses `TvKeyboard` (same visual and focus pattern as `TvSearchScreen`). A non-blank
 * query searches EVERY provider (Amendment A1, [LiveViewModel.crossSearch], the same merged search
 * the phone shows): the provider and category rows give way to one results list, each row with its
 * provider's badge, and the note of providers not fully loaded. Nothing asks the network per key:
 * the search runs over the channels each provider already holds.
 *
 * Providers (spec §4): with more than one, a provider row sits above the categories and is where
 * the remote lands on opening ([com.arkiv.player.ui.live.tvGuideFirstFocus]); down reaches the
 * categories, down again the list -- plain Compose focus search in a `Column`. Any row can vanish
 * under focus (a provider switched off, the search cleared by its own button): Compose then clears
 * focus instead of moving it, so a screen-wide watch hands it back to the row to land on
 * ([tvGuideShouldLand], [tvGuideFocusTarget]), retried against that row's OWN focus state.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLiveGuideScreen(onWatchChannel: (LiveChannel) -> Unit, onBack: () -> Unit) {
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
    val ownVm: OwnSourcesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OwnSourcesViewModel(graph.ownLiveStore, graph.ownProbe, onSaved = { vm.reload() }) }
        },
    )
    var ownManager by remember { mutableStateOf(false) }
    val ownFormOpen by ownVm.ui.collectAsStateWithLifecycle()
    val ownButtonFocus = remember { FocusRequester() }
    var ownButtonFocused by remember { mutableStateOf(false) }
    var ownFlowWasOpen by remember { mutableStateOf(false) }

    var view by remember { mutableStateOf(TvLocalView.NONE) }

    BackHandler { onBack() }

    // Recent: same as LiveScreen (mobile) -- read directly from Room, with no number/logo of its
    // own, enriched with whatever's already loaded in state.channels if the channel shows up there.
    val recentDao = remember { graph.database.liveRecentDao() }
    val rawRecents by recentDao.flowRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val recents = remember(rawRecents, state.channels, state.providers) {
        recentsForScreen(rawRecents, state.channels, state.providers.map { it.id }.toSet())
    }

    val baseChannels = if (view == TvLocalView.RECENT) recents else state.channels

    // Amendment A1: a non-blank query is one merged list over every provider, not a filter of the
    // chosen category. It lives in the model (vm.search), which feeds the cross-provider search.
    val search = state.search
    val cross by vm.crossSearch.collectAsStateWithLifecycle()
    val searchView = remember(search, cross) { liveSearchView(search, cross) }
    val searching = searchView != LiveSearchView.Off
    val channels = listOnScreen(searchView, baseChannels)

    // Watch the channel now: sets in LiveZappingSource the list on screen BEFORE delegating to
    // `onWatchChannel` -- it's the one the player's zapping goes through. Search results mix
    // providers: the player narrows them to the opened channel's provider (zappingListFor).
    fun watchChannel(channel: LiveChannel) {
        LiveZappingSource.list = channels
        onWatchChannel(channel)
    }

    // Focus: lands on opening on the provider row (with more than one provider) or on "Favoritos",
    // never parked on the keyboard; with a search on screen (it survives a trip to the player), on
    // the keyboard. Afterwards it only moves when a row that held it vanished and nothing on the
    // screen holds focus (tvGuideShouldLand). `FocusRequester.requestFocus()` never reports
    // failure, so success is each target's OWN focus state (see retryFocus).
    val providersFocus = remember { FocusRequester() }
    val chipsFocus = remember { FocusRequester() }
    val keyboardFocus = remember { FocusRequester() }
    var providersFocused by remember { mutableStateOf(false) }
    var chipsFocused by remember { mutableStateOf(false) }
    var keyboardFocused by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    var landedOnce by remember { mutableStateOf(false) }
    val focusTarget = tvGuideFocusTarget(state.showProviders, searching)
    // Keyed on every row that can vanish under focus: the providers, the categories, the list.
    LaunchedEffect(focusTarget, state.providers.map { it.id }, state.categories.map { it.id }, channels) {
        // One frame for a removed node to clear focus before asking who holds it.
        if (landedOnce) delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
        if (!tvGuideShouldLand(landedOnce, screenHasFocus)) return@LaunchedEffect
        landedOnce = true
        retryFocus(
            attempts = 20,
            isAlreadyFocused = {
                when (focusTarget) {
                    TvGuideFocus.PROVIDERS -> providersFocused
                    TvGuideFocus.CATEGORIES -> chipsFocused
                    TvGuideFocus.KEYBOARD -> keyboardFocused
                }
            },
            wait = { delay(50) },
            request = {
                when (focusTarget) {
                    TvGuideFocus.PROVIDERS -> providersFocus
                    TvGuideFocus.CATEGORIES -> chipsFocus
                    TvGuideFocus.KEYBOARD -> keyboardFocus
                }.requestFocus()
            },
        )
    }

    // Favourites from the remote: a long OK on any channel row opens TvFavoriteDialog (tv-material's
    // `onLongClick`: fired by the key's first repeat, and that press's click is swallowed, so the
    // channel doesn't open); its confirm button stars or unstars the channel, and focus comes back
    // to the same row (`returnCode`). Unstarring on the "Favoritos" list removes the row
    // (`dropFromFavoritesList`); Compose clears focus with the removed node, so it's handed to the
    // neighbour row (neighborAfterRemoval) or, with the list emptied, to the "Favoritos" chip --
    // before the screen-wide watch above would land it on the provider row instead.
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
    var focusedRowCode by remember { mutableStateOf<String?>(null) }
    var confirmChannel by remember { mutableStateOf<LiveChannel?>(null) }
    val returnRow = remember { FocusRequester() }
    var returnCode by remember { mutableStateOf<String?>(null) }
    fun askFavorite(channel: LiveChannel) {
        returnCode = channel.liveCode
        confirmChannel = channel
    }
    fun toggleFavorite(channel: LiveChannel) {
        val wasFavorite = channel.liveCode in state.favorites
        if (wasFavorite && !searching && view == TvLocalView.NONE && state.activeCategory == CATEGORY_FAVORITES) {
            removedCode = channel.liveCode
            refocusCode = neighborAfterRemoval(channels.map { it.liveCode }, channel.liveCode)
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
        if (code != removedCode) {
            retryFocus(
                attempts = 20,
                isAlreadyFocused = { focusedRowCode == code },
                wait = { delay(50) },
                request = { returnRow.requestFocus() },
            )
        }
        returnCode = null
    }
    LaunchedEffect(channels, removedCode) {
        val gone = removedCode ?: return@LaunchedEffect
        // Still listed: the model hasn't pruned it yet (the write is async).
        if (channels.any { it.liveCode == gone }) return@LaunchedEffect
        removedCode = null
        val target = refocusCode
        retryFocus(
            attempts = 20,
            isAlreadyFocused = { if (target == null) chipsFocused else focusedRowCode == target },
            wait = { delay(50) },
            request = { (if (target == null) chipsFocus else refocusRow).requestFocus() },
        )
        refocusCode = null
    }
    val favoriteRow: (LiveChannel) -> Modifier = { channel ->
        Modifier
            .onFocusChanged { if (it.isFocused) focusedRowCode = channel.liveCode }
            .then(if (channel.liveCode == refocusCode) Modifier.focusRequester(refocusRow) else Modifier)
            .then(if (channel.liveCode == returnCode) Modifier.focusRequester(returnRow) else Modifier)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack)
            .onFocusChanged { screenHasFocus = it.hasFocus }
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
                TvKeyboardWithNative(
                    text = search,
                    onTextChange = vm::search,
                    modifier = Modifier.onFocusChanged { keyboardFocused = it.hasFocus },
                    firstKeyFocus = keyboardFocus,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = { vm.reload() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = arkivTvSurfaceColors(),
                    border = arkivTvSurfaceBorder(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Recargar canales", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = { ownManager = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(ownButtonFocus).onFocusChanged { ownButtonFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = arkivTvSurfaceColors(),
                    border = arkivTvSurfaceBorder(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(OwnSourcesCopy.MY_SOURCES, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (search.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        // Removes this very button: the focus watch above hands focus to the rows.
                        onClick = { vm.search("") },
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

            // --- Right panel: provider chips + category chips + channel list. ---
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (state.moduleEmpty) {
                    // Defensive: ArkivTvRoot already leaves the route when the module empties.
                    TvGuideMessage("Sin canales en vivo", "Instala un plugin con canales o activa Xuper en Ajustes ▸ Plugins.")
                    return@Column
                }
                if (searching) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TvSearchResults(
                            searchView, state::tabOf, ::watchChannel,
                            isFavorite = { it.liveCode in state.favorites },
                            onToggleFavorite = ::askFavorite,
                            rowModifier = favoriteRow,
                        )
                    }
                    TvFavoriteHint(notice)
                    return@Column
                }
                if (state.showProviders) {
                    val lit = selectedProviderChip(state, recentView = view == TvLocalView.RECENT)
                    LazyRow(
                        contentPadding = PaddingValues(end = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 10.dp).onFocusChanged { providersFocused = it.hasFocus },
                    ) {
                        itemsIndexed(state.providers, key = { _, tab -> tab.id }) { i, tab ->
                            TvCategoryChip(
                                label = tab.name,
                                icon = null,
                                selected = lit == tab.id,
                                onClick = { view = TvLocalView.NONE; vm.chooseProvider(tab.id) },
                                modifier = if (i == 0) Modifier.focusRequester(providersFocus) else Modifier,
                            )
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.onFocusChanged { chipsFocused = it.hasFocus },
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

                // The active provider's own note (a plugin list cut to the caps): "Lista recortada: …".
                state.notice?.let { TvNoteLine(it) }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        view == TvLocalView.RECENT && baseChannels.isEmpty() ->
                            TvGuideMessage("Sin canales recientes", "Los canales que abras van a aparecer acá.")
                        state.error != null && state.channels.isEmpty() ->
                            TvGuideMessage(state.error!!, "Presiona OK para reintentar.") { vm.retry() }
                        state.loading && state.channels.isEmpty() ->
                            TvGuideMessage("Cargando canales…", null)
                        baseChannels.isEmpty() && state.activeCategory == CATEGORY_FAVORITES ->
                            TvGuideMessage("Aún no tienes favoritos", "Mantén OK sobre un canal para agregarlo.")
                        baseChannels.isEmpty() && state.activeProvider == OwnLive.PROVIDER && state.categories.isEmpty() ->
                            TvGuideMessage(OwnSourcesCopy.EMPTY_TITLE, "Elige «${OwnSourcesCopy.MY_SOURCES}» a la izquierda para agregar un canal .m3u8 o una lista M3U. Si tienes un celular o una TV vinculados, se copiarán solos.")
                        baseChannels.isEmpty() ->
                            TvGuideMessage("Sin canales", "No encontramos canales en esta categoría.")
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(end = 24.dp, bottom = 16.dp),
                        ) {
                            items(channels, key = { it.liveCode }) { channel ->
                                TvChannelRow(
                                    channel = channel,
                                    badge = state.tabOf(channel),
                                    isFavorite = channel.liveCode in state.favorites,
                                    onClick = { watchChannel(channel) },
                                    onLongClick = { askFavorite(channel) },
                                    modifier = Modifier.height(ROW_HEIGHT).then(favoriteRow(channel)),
                                )
                            }
                        }
                    }
                }
                TvFavoriteHint(notice)
            }
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

    TvOwnSourceDialogs(ownVm, showManager = ownManager, onCloseManager = { ownManager = false })
    // A closed dialog hands focus back to the button that opened the flow (Compose clears it with the dialog).
    val ownFlowOpen = ownManager || ownFormOpen.open
    LaunchedEffect(ownFlowOpen) {
        if (ownFlowOpen) {
            ownFlowWasOpen = true
            return@LaunchedEffect
        }
        // Not on the first composition: only when a dialog of this flow has just closed.
        if (!ownFlowWasOpen) return@LaunchedEffect
        ownFlowWasOpen = false
        retryFocus(attempts = 20, isAlreadyFocused = { ownButtonFocused }, wait = { delay(50) }, request = { ownButtonFocus.requestFocus() })
    }
}

/**
 * The merged search across every provider (Amendment A1): the results list, each row with its
 * provider's badge ([badgeOf], non-null only with more than one provider), "Sin resultados", or
 * "Buscando…" while the first answer is computed; above it, which providers were not fully searched.
 */
@Composable
private fun TvSearchResults(
    view: LiveSearchView,
    badgeOf: (LiveChannel) -> LiveProviderTab?,
    onWatch: (LiveChannel) -> Unit,
    isFavorite: (LiveChannel) -> Boolean,
    onToggleFavorite: (LiveChannel) -> Unit,
    rowModifier: (LiveChannel) -> Modifier,
) {
    val note = when (view) {
        is LiveSearchView.Results -> view.note
        is LiveSearchView.NoResults -> view.note
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        note?.let { TvNoteLine(it) }
        when (view) {
            is LiveSearchView.Results -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 24.dp, bottom = 16.dp),
            ) {
                items(view.channels, key = { it.liveCode }) { channel ->
                    TvChannelRow(
                        channel = channel,
                        badge = badgeOf(channel),
                        isFavorite = isFavorite(channel),
                        onClick = { onWatch(channel) },
                        onLongClick = { onToggleFavorite(channel) },
                        modifier = Modifier.height(ROW_HEIGHT).then(rowModifier(channel)),
                    )
                }
            }
            is LiveSearchView.NoResults ->
                TvGuideMessage("Sin resultados", "Prueba con otro nombre o número de canal.")
            else -> TvGuideMessage("Buscando…", null)
        }
    }
}

/** How long "Agregado a favoritos" / "Quitado de favoritos" replaces the hint. */
private const val NOTICE_MS = 2000L

/**
 * The line under the channel list: how to star a channel (nothing on a TV row can be hovered or
 * tapped to discover a long press), swapped for the confirmation [notice] for a moment after one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvFavoriteHint(notice: String?) {
    Text(
        notice ?: FAVORITE_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = if (notice != null) Color.White else ArkivTextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** A small secondary line above the list: a provider's notice or the search's not-loaded note. Never focusable. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvNoteLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/**
 * A channel row: the screen's focusable and clickable element (no longer a program sub-block
 * inside it). Pressing OK plays the channel directly, whether there's programming or not.
 *
 * `Surface` (tv-material3), not a `Box` + `clickable` + `focusable` by hand like the previous
 * program block had: the focus (red background + 3dp white border) reads clearly from three
 * meters away.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChannelRow(
    channel: LiveChannel,
    badge: LiveProviderTab?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFavorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorito",
                            tint = FAVORITE_STAR,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp),
                        )
                    }
                    // Which provider the row is from, only with more than one ("CNN · Tu servidor").
                    badge?.let { ProviderBadge(it, Modifier.padding(start = 8.dp)) }
                }
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
