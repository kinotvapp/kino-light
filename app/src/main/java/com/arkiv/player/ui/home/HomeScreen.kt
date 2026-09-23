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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.components.ContinueCard
import com.arkiv.player.ui.components.SectionHeader
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.countryChannelsForHome
import com.arkiv.player.ui.live.recentChannelsForHome
import com.arkiv.player.ui.live.homeChannelsRow
import com.arkiv.player.ui.isLandscapeTablet
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
    val channelWidth: Dp,
)

@Composable
private fun homeSizes(): HomeSizes =
    if (isLandscapeTablet()) {
        HomeSizes(
            heroHeight = 420.dp,
            posterWidth = 180.dp,
            continueWidth = 320.dp,
            channelWidth = 200.dp,
        )
    } else {
        HomeSizes(
            heroHeight = 220.dp,
            posterWidth = 120.dp,
            continueWidth = 220.dp,
            channelWidth = 140.dp,
        )
    }

/**
 * Discovery home (Amazon/Netflix style): hero of what was last watched, library and rows built
 * from the Magis catalog (see `MagisHomeCatalog`/`MagisHomeClassifier`), fetched in one pass and
 * classified on the device -- not the per-row lazy TMDB fetch this screen used to do.
 */
@Composable
fun HomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    /** Plays a live channel directly (channel code), without going through the "En vivo" tab. */
    onPlayLive: (String) -> Unit,
    /** Opens the "En vivo" tab with the full grid (channel row's last card). */
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    /** "Ver todo" of a Magis row: the grid with every title of that row. */
    onBrowseMagisRow: (rowId: String, title: String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val sizes = homeSizes()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.settings, graph.magisHomeCatalog, graph.hasInternet) } },
    )
    // A Magis root that failed on the way in (e.g. a cold start before the network is up) gets
    // another chance each time this screen comes back to the front; see HomeViewModel.magisRows.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    // This screen doesn't collect `vm.library` (ordered by addedAt): that subscription only lives
    // in the VM's `init`, for `ensureArtwork`/the TV home's hero. The "Mi biblioteca" row uses
    // `orderedLibrary` to match the grid's order (same rule, see LibraryOrder).
    val orderedLibrary by vm.orderedLibrary.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val magisRows by vm.magisRows.collectAsStateWithLifecycle()
    val seedsExhausted by graph.seedsExhausted.collectAsStateWithLifecycle()
    val openMagis = rememberMagisOpener(onPlay = onPlayEpisode)
    val scope = rememberCoroutineScope()

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
    var cacheByCode by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(rawRecent) {
        if (rawRecent.isNotEmpty()) {
            cacheByCode = liveCacheDao.byCodes(rawRecent.map { it.code }).associateBy { it.code }
        }
    }
    val recentChannels = remember(rawRecent, cacheByCode) {
        recentChannelsForHome(rawRecent, cacheByCode)
    }

    // Channels from the device's country, so the row is useful from the very first opening (with
    // nothing watched yet) -- see countryChannelsForHome: it detects the country, comes from the
    // Room cache if it's fresh, and doesn't break anything if there's no network or no detectable
    // country.
    val context = LocalContext.current
    var countryChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        countryChannels = countryChannelsForHome(
            context = context,
            api = graph.liveCatalog,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    val channelsRow = remember(recentChannels, countryChannels) {
        homeChannelsRow(recentChannels, countryChannels)
    }

    fun playChannel(channel: LiveChannel) {
        // Pins the list it was "entered" with, same mechanism as LiveScreen.open -- so
        // up/down in the player goes through the same channels the row shows.
        LiveZappingSource.list = channelsRow
        onPlayLive(channel.code)
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
        heroVisible = continueWatching.firstOrNull() != null || magisFeatured(magisRows) != null,
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
        // 1. Hero: the last thing watched, or if nothing's in progress, the first Magis title
        // (once loaded).
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
            } else {
                val featured = magisFeatured(magisRows)
                if (featured != null) {
                    // The landscape (1920×1080) image on every device, not just wide/tablet ones:
                    // the hero box itself is full-width × 220 dp landscape, so the portrait
                    // (262×370) poster would need a ~4× upscale and a crop to fill it. Either one
                    // falls back to the other, so the hero never ends up empty.
                    val heroImage = featured.backdrop ?: featured.poster
                    Hero(
                        sizes = sizes,
                        backdropUrl = heroImage,
                        title = featured.title,
                        subtitle = featured.homeMeta(),
                        actionLabel = null,
                        onAction = null,
                        onClick = { openMagis(featured) },
                    )
                }
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
        // at the end the way out to the full grid (see `homeChannelsRow`). With nothing to
        // show, the row isn't drawn: no empty gap. Always-present, keyed item -- see the hero
        // comment above.
        item(key = "canales") {
            if (channelsRow.isNotEmpty()) {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionHeader("Canales en vivo", modifier = Modifier.padding(start = 16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(channelsRow, key = { it.code }) { channel ->
                            LiveChannelCard(channel = channel, width = sizes.channelWidth, onClick = { playChannel(channel) })
                        }
                        // At the end of the row, the way out to the full grid: recents are a
                        // shortcut, not the catalog.
                        item(key = "live_ver_mas") {
                            SeeMoreChannelsCard(width = sizes.channelWidth, onClick = onOpenLive)
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

        // 5. Magis rows: what Xuper actually has, by type × genre (see MagisHomeClassifier).
        val loadedRows = magisRows
        if (loadedRows == null) {
            item(key = "magis_loading") {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            loadedRows.forEach { row ->
                item(key = row.id) {
                    MagisRow(
                        row = row,
                        sizes = sizes,
                        onOpen = openMagis,
                        onSeeMore = { onBrowseMagisRow(row.id, row.title) },
                    )
                }
            }
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
 * "Canales en vivo" row's last card: opens the "En vivo" tab with the full grid. Same shape as
 * [LiveChannelCard] (140.dp, 16:9 and text below) so the row doesn't change height at the end.
 */
@Composable
private fun SeeMoreChannelsCard(width: Dp = 140.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(width).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LiveTv,
                contentDescription = null,
                tint = ArkivRed,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = "Ver más canales",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Card for a recent channel in the home's "Canales en vivo" row: logo if the cache has it (see
 * [recentChannelsForHome]); if not, the same treatment as `ChannelCard` in `LiveScreen.kt` --
 * gradient + the channel number, so it looks deliberate and not like a broken logo. If not even
 * the number is known (a just-watched channel, cache with no such `code`), it falls back further
 * still: the name's initials, to not show a "0" that means nothing.
 */
@Composable
private fun LiveChannelCard(channel: LiveChannel, width: Dp = 140.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(width).clickable(onClick = onClick)) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (channel.number > 0) channel.number.toString() else channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A Magis row: title, the row's cards, and "Ver todo" at the end. */
@Composable
private fun MagisRow(
    row: MagisHomeRow,
    sizes: HomeSizes,
    onOpen: (CatalogItem) -> Unit,
    onSeeMore: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.shown, key = { "${row.id}-${it.id}" }) { item ->
                com.arkiv.player.ui.components.PosterCard(
                    title = item.title,
                    imageUrl = item.poster,
                    modifier = Modifier.width(sizes.posterWidth),
                    onClick = { onOpen(item) },
                )
            }
            item(key = "${row.id}-ver-mas") {
                SeeMorePosterCard(width = sizes.posterWidth, onClick = onSeeMore)
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
