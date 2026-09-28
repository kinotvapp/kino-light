package com.arkiv.player.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.components.ContinueCard
import com.arkiv.player.ui.components.SectionHeader
import com.arkiv.player.ui.components.rememberSkeletonShimmer
import com.arkiv.player.ui.components.skeleton
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.countryChannelsForHome
import com.arkiv.player.ui.live.recentChannelsForHome
import com.arkiv.player.ui.live.homeLiveRow
import com.arkiv.player.ui.live.providerBadge
import com.arkiv.player.ui.live.ProviderBadge
import com.arkiv.player.data.live.LiveProviderTab
import com.arkiv.player.ui.live.homeChannelsRow
import com.arkiv.player.ui.live.liveCacheForRecents
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.titleinfo.rememberTitleOpener
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Home sizes based on the screen's shape. See [isLandscapeTablet]. */
private data class HomeSizes(
    val heroHeight: Dp,
    val posterWidth: Dp,
    val continueWidth: Dp,
    val channelDiameter: Dp,
)

@Composable
private fun homeSizes(): HomeSizes =
    if (isLandscapeTablet()) {
        HomeSizes(
            heroHeight = 420.dp,
            posterWidth = 180.dp,
            continueWidth = 320.dp,
            channelDiameter = 96.dp,
        )
    } else {
        HomeSizes(
            heroHeight = 220.dp,
            posterWidth = 120.dp,
            continueWidth = 220.dp,
            channelDiameter = 72.dp,
        )
    }

/**
 * Discovery home (Amazon/Netflix style): hero of what was last watched, library, and the installed
 * plugins' Home rows (Xuper's among them, see `PluginHomeRows`).
 */
@Composable
fun HomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    /** A plugin card was tapped: the route of its info page (see `titleRoute`). */
    onOpenTitleRoute: (String) -> Unit,
    /** Plays a live channel directly (channel code), without going through the "En vivo" tab. */
    onPlayLive: (String) -> Unit,
    /** Opens the "En vivo" tab with the full grid (channel row's last card). */
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    contentPadding: PaddingValues,
    /** "Ver más" of a plugin row that carries a `ref` (the plugin declares `browse`). */
    onBrowsePluginRow: (com.arkiv.player.ui.plugin.PluginMoreTarget) -> Unit = {},
    /** "Agregar plugin" of the empty state: opens "Elige tus fuentes". */
    onOpenSourcePicker: () -> Unit = {},
) {
    val graph = rememberGraph()
    val sizes = homeSizes()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    graph.repository, graph.settings, graph.homeReloads, graph.pluginHomeRows, graph.pluginsChanged,
                    online = graph.hasInternet, onBackOnline = { graph.magisHomeCatalog.forgetFailedPass() },
                )
            }
        },
    )
    HomeFreshnessEffect(vm)
    // This screen doesn't collect `vm.library` (ordered by addedAt): that subscription only lives
    // in the VM's `init`, for `ensureArtwork`/the TV home's hero. The "Mi biblioteca" row uses
    // `orderedLibrary` to match the grid's order (same rule, see LibraryOrder).
    val orderedLibrary by vm.orderedLibrary.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val pluginRows by vm.pluginRows.collectAsStateWithLifecycle()
    val pluginRowsSettled by vm.pluginRowsSettled.collectAsStateWithLifecycle()
    val seedsExhausted by graph.seedsExhausted.collectAsStateWithLifecycle()
    val openPlugin = rememberTitleOpener(onOpenRoute = onOpenTitleRoute, onPlay = onPlayEpisode)
    val scope = rememberCoroutineScope()
    // The hero's fallback when nothing is in progress (see pluginHeroPick).
    val heroPick = remember(pluginRows) { pluginHeroPick(pluginRows) }

    // On tapping a library item: if it's a movie, play it directly; if it's a series, open the detail screen.
    fun open(row: LibraryRow) {
        if (row.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(row.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(row.identifier)
            }
        } else {
            onOpenItem(row.identifier)
        }
    }

    // Recent live channels, for the row that avoids entering "En vivo" (see
    // recentChannelsForHome). Read straight from Room, same as LiveScreen does with its own
    // "Recientes" tab -- no need to spin up LiveViewModel (which talks to the gateway) just for
    // this. Capped at 10: it's a quick-access row within reach, not the full history (that's what
    // "En vivo"'s "Recientes" tab is for, with no cap).
    val liveRecentDao = remember { graph.database.liveRecentDao() }
    val liveCacheDao = remember { graph.database.liveChannelCacheDao() }
    val rawRecent by liveRecentDao.flowRecent(10).collectAsStateWithLifecycle(initialValue = emptyList())
    var cacheByLiveCode by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(rawRecent) {
        if (rawRecent.isNotEmpty()) {
            cacheByLiveCode = liveCacheForRecents(rawRecent, liveCacheDao)
        }
    }
    val recentChannels = remember(rawRecent, cacheByLiveCode) {
        recentChannelsForHome(rawRecent, cacheByLiveCode)
    }

    // Channels from the device's country, so the row is useful from the very first opening (with
    // nothing watched yet) -- see countryChannelsForHome: it detects the country, comes from the
    // Room cache if it's fresh, and doesn't break anything if there's no network or no detectable
    // country.
    //
    // The country part follows the Xuper plugin (AppGraph.xuperLive): off, the country's channels
    // aren't even asked for; back on, they're fetched again. The row itself follows the whole live
    // module (homeLiveRow): any provider, Xuper or a plugin with channels. Recents stay in Room.
    val context = LocalContext.current
    val xuperLive by graph.xuperLive.collectAsStateWithLifecycle()
    var countryChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(xuperLive) {
        countryChannels = if (!xuperLive) emptyList() else countryChannelsForHome(
            context = context,
            api = graph.liveCatalog,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    // The module's providers right now: a switched-off plugin's recents leave the row (they are kept, not deleted).
    val liveOn by graph.liveModule.available.collectAsStateWithLifecycle()
    val installedPlugins by graph.pluginAdmin.plugins.collectAsStateWithLifecycle()
    val homeEmpty = homeShowsEmptyState(installedPlugins, pluginRows.size, liveOn)
    val emptyCopy = homeEmptyCopy(installedPlugins, isTv = false)
    val liveTabs by graph.liveModule.tabs.collectAsStateWithLifecycle()
    // null = no row (empty module, or no channel to list yet); never just "Ver más canales".
    val liveRow = remember(recentChannels, countryChannels, liveOn, liveTabs) {
        homeLiveRow(liveOn, recentChannels, countryChannels, available = liveTabs.map { it.id }.toSet())
    }
    val channelsRow = liveRow.orEmpty()
    // Plugins still answering: grey skeleton rows hold the place of the plugin rows to come, each real row
    // taking one's place (homeSkeletonRowCount), instead of a blank gap or a spinner.
    val skeletonRows = homeSkeletonRowCount(
        loading = homePluginRowsLoading(installedPlugins, pluginRowsSettled),
        rowsAbove = 0,
        pluginRowCount = pluginRows.size,
        slots = PHONE_HOME_SKELETON_SLOTS,
    )
    val reducedEffects = com.arkiv.player.ui.rememberReducedEffects()
    // Only while a skeleton is on screen: the shimmer's transition keeps the frame clock ticking.
    val skeletonShimmer = if (skeletonRows > 0) rememberSkeletonShimmer(reducedEffects) else null
    // Belt-and-braces: when the row's first channel changes (it appears, or a new recent lands
    // first), start from it. LazyRow otherwise keeps its key-anchored first visible item and can
    // open scrolled to the end, the first card cut at the left edge (measured on the phone).
    val channelsRowState = rememberLazyListState()
    LaunchedEffect(channelsRow.firstOrNull()?.liveCode) {
        if (channelsRow.isNotEmpty()) channelsRowState.scrollToItem(0)
    }

    fun playChannel(channel: LiveChannel) {
        // Pins the list it was "entered" with, same mechanism as LiveScreen.open -- so
        // up/down in the player goes through the same channels the row shows.
        LiveZappingSource.list = channelsRow
        onPlayLive(channel.liveCode)
    }

    val hasInternet by graph.hasInternet.collectAsStateWithLifecycle()

    if (!hasInternet) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFFB00020))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
                androidx.compose.material3.Text(
                    text = "Sin conexión — revisa tu red",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    val listState = rememberLazyListState()

    // Whether the person has scrolled the list by hand. rememberSaveable so it survives coming
    // back from a detail screen with the same value -- once true, the auto-snap below never fires
    // again for this screen instance. Only a real drag/fling sets it: `isScrollInProgress` is also
    // true during the auto-snap's own `scrollToItem`, so it can't be used to tell the two apart.
    var userScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrolled = true
        }
    }

    // Shape of the top sections right now (see TopSectionsSignature/shouldSnapHomeToTop): those
    // sections are always-present, stably-keyed items below, but they still grow from zero height
    // to their real content as their data loads. While the person hasn't scrolled, snap back to
    // the top whenever that shape changes -- the safety net for staying at the top of the list.
    val topSectionsSignature = TopSectionsSignature(
        heroVisible = continueWatching.firstOrNull() != null || heroPick != null,
        continueWatchingCount = (continueWatching.size - 1).coerceAtLeast(0),
        channelsCount = channelsRow.size,
        libraryCount = orderedLibrary.size,
    )
    var lastTopSectionsSignature by remember { mutableStateOf<TopSectionsSignature?>(null) }
    LaunchedEffect(topSectionsSignature, userScrolled) {
        val signatureChanged = topSectionsSignature != lastTopSectionsSignature
        lastTopSectionsSignature = topSectionsSignature
        if (
            shouldSnapHomeToTop(
                userScrolled = userScrolled,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                signatureChanged = signatureChanged,
            )
        ) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Backup pool exhausted for this geo-blocked device: every freshly-downloaded seed came back
        // dead too. Nothing the person can do but wait for a new pool to be published, so say so
        // instead of leaving the catalog silently empty.
        item(key = "seeds_exhausted") {
            if (seedsExhausted) {
                Text(
                    "Por ahora no hay sesiones disponibles para tu zona. Estamos publicando nuevas; " +
                        "volvé a intentar en un rato o toca \"Sembrar semillas\" en Ajustes → App.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        // 1. Hero: the last thing watched, or if nothing's in progress, the first item of the first
        // plugin Home row (once loaded, see pluginHeroPick).
        // Always-present, keyed item (renders nothing until it has data) -- see TopSectionsSignature:
        // an unkeyed, conditionally-emitted item here is what let this section get inserted ABOVE the
        // already-visible, keyed remote rows and land the person mid-list.
        item(key = "hero") {
            val heroContinue = continueWatching.firstOrNull()
            if (heroContinue != null) {
                val backdrop = ThumbnailChoice.choose(
                    heroContinue.framePath,
                    artwork[heroContinue.itemId]?.backdrops?.firstOrNull(),
                    heroContinue.itemThumbnailUrl,
                )
                Hero(
                    sizes = sizes,
                    backdropUrl = backdrop,
                    title = heroContinue.itemTitle,
                    // The chapter data, the SAME line the TV hero builds: number, name and how much
                    // is left, omitting what isn't known. This used to just have the chapter name,
                    // with no number or time. If no part is left (a movie with no known duration)
                    // it falls back to the usual name, so the hero doesn't end up with an empty line.
                    subtitle = com.arkiv.player.ui.ChapterLabel.heroLine(
                        isMovie = heroContinue.isMovie,
                        season = heroContinue.season,
                        episode = heroContinue.episode,
                        orderIndex = heroContinue.orderIndex,
                        itemId = heroContinue.itemId,
                        section = heroContinue.section,
                        name = heroContinue.episodeTitle,
                        positionMs = heroContinue.positionMs,
                        durationMs = heroContinue.durationMs,
                    ).ifBlank { heroContinue.episodeTitle ?: heroContinue.displayName },
                    actionLabel = "Reanudar",
                    onAction = { onPlayEpisode(heroContinue.episodeId) },
                    onClick = { onPlayEpisode(heroContinue.episodeId) },
                )
            } else if (heroPick != null) {
                // The landscape image first on every device, not just wide/tablet ones: the hero
                // box itself is full-width × 220 dp landscape, so a portrait poster would need a
                // big upscale and a crop to fill it (see PluginHeroPick.imageUrl).
                Hero(
                    sizes = sizes,
                    backdropUrl = heroPick.imageUrl,
                    title = heroPick.item.title,
                    subtitle = heroPick.meta(),
                    actionLabel = null,
                    onAction = null,
                    onClick = { openPlugin(heroPick.item) },
                )
            }
        }

        // 2. Continue watching (the rest, without repeating the hero). Always-present, keyed item
        // -- see the hero comment above.
        item(key = "continuar") {
            if (continueWatching.size > 1) {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionHeader("Continuar viendo", modifier = Modifier.padding(start = 16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(continueWatching.drop(1), key = { it.episodeId }) { row ->
                            val progress = if (row.durationMs > 0) row.positionMs.toFloat() / row.durationMs else 0f
                            // The captured frame wins if it exists; if not, TMDB's still and last
                            // the item's cover. The archive.org thumb that used to go in the middle
                            // was deleted in this branch's pruning along with that source.
                            val thumb = ThumbnailChoice.choose(
                                row.framePath,
                                row.stillUrl,
                                null,
                                row.itemThumbnailUrl,
                            )
                            ContinueCard(
                                title = row.itemTitle,
                                subtitle = row.episodeTitle ?: row.displayName,
                                imageUrl = thumb,
                                progress = progress,
                                modifier = Modifier.width(sizes.continueWidth),
                                onClick = { onPlayEpisode(row.episodeId) },
                            )
                        }
                    }
                }
            }
        }

        // 3. Live channels -- direct access without going through "En vivo": what was last watched
        // on the left, then the country's channels without repeating the ones already seen, and
        // at the end the way out to the full grid (see `homeLiveRow`). With no channel to show
        // (no live provider, or nothing watched and no country channels), the row isn't drawn:
        // no empty gap. Always-present, keyed item -- see the hero
        // comment above.
        item(key = "canales") {
            if (channelsRow.isNotEmpty()) {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionHeader("Canales en vivo", modifier = Modifier.padding(start = 16.dp))
                    LazyRow(
                        state = channelsRowState,
                        // Each circle's item is wider than the circle (room for the name), so the
                        // padding and the spacing give that back: the first CIRCLE lines up with the
                        // header and circles sit about as far apart as the other rows' cards.
                        contentPadding = PaddingValues(
                            horizontal = 16.dp - com.arkiv.player.ui.live.channelCircle(sizes.channelDiameter.value)
                                .let { (it.itemWidthDp - it.diameterDp) / 2f }.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(channelsRow, key = { it.liveCode }) { channel ->
                            LiveChannelCard(
                                channel = channel,
                                badge = providerBadge(channel, liveTabs),
                                diameter = sizes.channelDiameter,
                                onClick = { playChannel(channel) },
                            )
                        }
                        // At the end of the row, the way out to the full grid: recents are a
                        // shortcut, not the catalog.
                        item(key = "live_ver_mas") {
                            SeeMoreChannelsCard(diameter = sizes.channelDiameter, onClick = onOpenLive)
                        }
                    }
                }
            }
        }

        // 4. Mi biblioteca (with "Ver todo" toward the full grid). Always-present, keyed item --
        // see the hero comment above.
        item(key = "biblioteca") {
            if (orderedLibrary.isNotEmpty()) {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SectionHeader("Mi biblioteca", modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenLibrary) { Text("Ver todo", color = ArkivRed) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(orderedLibrary, key = { it.identifier }) { row ->
                            com.arkiv.player.ui.components.PosterCard(
                                title = row.title,
                                imageUrl = row.thumbnailUrl,
                                modifier = Modifier.width(sizes.posterWidth),
                                onClick = { open(row) },
                            )
                        }
                    }
                }
            }
        }

        // 5. Nothing can fill Home (spec 2026-09-28 §5): say so and offer the picker. Always-present, keyed
        // item -- see the hero comment above.
        item(key = "empty_sources") {
            if (homeEmpty) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(emptyCopy.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(emptyCopy.line, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                    Button(
                        onClick = onOpenSourcePicker,
                        colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(emptyCopy.action)
                    }
                }
            }
        }

        // 6. Plugin rows (Xuper's among them): each titled by the plugin's row with the plugin as a chip.
        pluginRows.forEach { row ->
            item(key = "plugin-${row.pluginId}-${row.id}") {
                PluginRow(
                    row = row, sizes = sizes, onOpen = openPlugin,
                    onSeeMore = row.ref?.let { ref -> { onBrowsePluginRow(com.arkiv.player.ui.plugin.PluginMoreTarget.Browse(row.pluginId, row.title, ref)) } },
                )
            }
        }
        // 7. Plugin rows still to come: placeholders after the real ones, stable keys so a real row
        // replacing one does not move the list. Not clickable; a screen reader hears the first once.
        items(skeletonRows, key = { homeSkeletonKey(it) }) { index ->
            PhoneSkeletonRow(sizes = sizes, shimmer = skeletonShimmer, announce = index == 0)
        }
    }
}

/** Full-width feature: backdrop, bottom gradient, title/subtitle and optional action. */
@Composable
private fun Hero(
    sizes: HomeSizes,
    backdropUrl: String?,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(sizes.heroHeight)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, ArkivBlack))),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                // 2 lines: with just 1, the chapter's data line (~48 characters) got ellipsized
                // right where it matters -- "te faltan N min" is the first thing lost.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * "Canales en vivo" row's last item: opens the "En vivo" tab with the full grid. The same circle as
 * [LiveChannelCard] (same size, label below) so the row keeps its rhythm at the end.
 */
@Composable
private fun SeeMoreChannelsCard(diameter: Dp, onClick: () -> Unit) {
    ChannelCircleItem(diameter = diameter, label = "Ver más", badge = null, onClick = onClick) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = ArkivRed,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * A recent channel in the home's "Canales en vivo" row: a round tile with the logo centered and fitted
 * with room to spare (wide logos never touch the edge) and the name below. Logo if the cache has it
 * (see [recentChannelsForHome]); if not, the same treatment as `ChannelCard` in `LiveScreen.kt` --
 * gradient + the channel number, so it looks deliberate and not like a broken logo. If not even the
 * number is known (a just-watched channel, cache with no such `code`), it falls back further still:
 * the name's initials, to not show a "0" that means nothing.
 */
@Composable
private fun LiveChannelCard(channel: LiveChannel, badge: LiveProviderTab? = null, diameter: Dp, onClick: () -> Unit) {
    val spec = com.arkiv.player.ui.live.channelCircle(diameter.value)
    ChannelCircleItem(diameter = diameter, label = channel.name, badge = badge, onClick = onClick) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(spec.logoPaddingDp.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (channel.number > 0) channel.number.toString() else channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * The shared frame of the live row's circles: the round tile, the optional provider badge on its
 * top-right (only with more than one provider, ruling R6: the row mixes their recents) and the
 * one-line name below. The whole item is the tap target.
 */
@Composable
private fun ChannelCircleItem(
    diameter: Dp,
    label: String,
    badge: LiveProviderTab?,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val spec = com.arkiv.player.ui.live.channelCircle(diameter.value)
    Column(
        modifier = Modifier
            .width(spec.itemWidthDp.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(diameter)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(diameter)
                    .clip(CircleShape)
                    .background(ArkivSurfaceHigh),
            ) { content() }
            badge?.let { ProviderBadge(it, Modifier.align(Alignment.TopEnd)) }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spec.nameGapDp.dp),
        )
    }
}

/** A plugin's Home row: its title, the plugin's name as a chip, its cards and, with [onSeeMore], a last "Ver más" card. */
@Composable
private fun PluginRow(
    row: com.arkiv.player.data.plugin.PluginHomeRow,
    sizes: HomeSizes,
    onOpen: (com.arkiv.player.data.gateway.GatewayResult) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            com.arkiv.player.ui.catalog.MetaChip(row.pluginName, Color(row.color))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.items, key = { "${row.pluginId}-${row.id}-${it.extra["pluginItemId"]}" }) { item ->
                com.arkiv.player.ui.components.PosterCard(
                    title = item.title,
                    imageUrl = item.extra["poster"]?.ifBlank { null },
                    modifier = Modifier.width(sizes.posterWidth),
                    // A live channel says so on its card (red, like the native live row); a title wears nothing here.
                    badge = com.arkiv.player.ui.catalog.liveBadge(item),
                    onClick = { onOpen(item) },
                    onLongClick = { onOpen(item) },
                )
            }
            if (onSeeMore != null) {
                item(key = "${row.pluginId}-${row.id}-ver-mas") { SeeMorePosterCard(width = sizes.posterWidth, onClick = onSeeMore) }
            }
        }
    }
}

@Composable
private fun SeeMorePosterCard(width: Dp = 120.dp, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(width).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ver más",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * A placeholder for a plugin row that has not arrived: a grey title bar and a line of grey poster cards
 * the size of the real ones ([PluginRow]'s). Draws only: no click, and a row that does not scroll.
 */
@Composable
private fun PhoneSkeletonRow(sizes: HomeSizes, shimmer: State<Float>?, announce: Boolean) {
    Column(
        Modifier
            .padding(top = 16.dp)
            .clearAndSetSemantics { if (announce) contentDescription = HOME_LOADING_LINE },
    ) {
        Box(
            Modifier
                .padding(start = 16.dp, top = 4.dp, bottom = 12.dp)
                .width(160.dp)
                .height(16.dp)
                .skeleton(shimmer, RoundedCornerShape(4.dp)),
        )
        LazyRow(
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(PHONE_SKELETON_CARDS) {
                Column(Modifier.width(sizes.posterWidth)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeleton(shimmer))
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(0.7f)
                            .height(10.dp)
                            .skeleton(shimmer, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

/** Enough poster placeholders to run past the right edge of a phone or tablet. */
private const val PHONE_SKELETON_CARDS = 8
