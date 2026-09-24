package com.arkiv.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.home.homeMeta
import com.arkiv.player.ui.home.magisFeatured
import com.arkiv.player.ui.live.deviceCountry
import com.arkiv.player.ui.heroFallback
import com.arkiv.player.ui.heroSubtitle
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.countryChannelsForHome
import com.arkiv.player.ui.live.recentChannelsForHome
import com.arkiv.player.ui.live.homeChannelsRow
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the background hero shows. [meta] is the data line highlighted in white below the title:
 * the chapter label ("T1 · E5  ·  La conspiración  ·  te faltan 12 min") on "Continuar viendo", a
 * recommendation's reason ("porque terminaste Dragon Ball") on "Para ti" (see
 * [recommendationFeatured]), or the type/genres/score line on a Magis discovery card (see
 * [magisCardFeatured]) -- every portal item has a description, so [subtitle] is never blank there.
 *
 * `internal` (not `private`) so [recommendationFeatured] and [magisCardFeatured] can be tested
 * without Compose, see `TvHomeScreenForYouTest`.
 */
internal data class Featured(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val meta: String = "",
)

/**
 * How much the hero's background gets enlarged so it can drift without a border showing. 12%
 * leaves 6% of slack on each side, i.e. about 140 px of travel at 1080p.
 *
 * At 1.06 the movement existed —measured: 227 pixels of difference between two captures— but
 * wasn't perceptible: the image's right edge is the screen's edge and the left one sits under a
 * gradient, so there's no reference to notice a small shift against.
 */
private const val HERO_SCALE = 1.12f

/** How long the drift takes to cross from one end to the other. Deliberately kept slow: it has to
 *  feel like the image is breathing, not like an animation asking for attention. */
private const val HERO_DRIFT_MS = 14_000

/**
 * If there are current recommendations, the "Para ti" row gets drawn; if not, neither the title
 * nor a gap -- same criterion as "Canales en vivo" right next to it. A separate function (instead
 * of a loose `.isNotEmpty()` in the composable) so the rule can be tested without spinning up
 * Compose.
 */
internal fun showForYouRow(recommendations: List<RecommendationEntity>): Boolean = recommendations.isNotEmpty()

/**
 * What the hero shows on focusing a "Para ti" card: the "why" the gateway brings goes in
 * [Featured.meta] -- the same spot where "Continuar viendo" puts "te faltan 12 min" -- because
 * it's the datum that explains the recommendation, not a synopsis. Top-level and not local to
 * [TvHomeScreen] (unlike `continueFeatured`/`libraryFeatured`, which do read the composable's
 * state) so it can be tested without Compose: it's pure, only depends on [RecommendationEntity]'s
 * fields.
 */
internal fun recommendationFeatured(rec: RecommendationEntity): Featured = Featured(
    title = rec.titulo,
    subtitle = if (rec.tipo == "movie") "Película" else "Serie",
    imageUrl = rec.posterUrl.ifBlank { null },
    meta = rec.porque,
)

/**
 * What the hero shows on focusing a Magis discovery card: the portal's own synopsis as [subtitle]
 * (fallback `""`, not [homeMeta] -- a title with no description would otherwise show that line
 * twice, once as subtitle and once as meta) and the type/genres/score line ([homeMeta]) as
 * [Featured.meta], same slot "Para ti" uses for its reason. Top-level for the same reason as
 * [recommendationFeatured]: pure, testable without Compose.
 */
internal fun magisCardFeatured(item: CatalogItem) = Featured(
    title = item.title,
    subtitle = heroSubtitle(item.title, item.description, fallback = ""),
    imageUrl = item.backdrop ?: item.poster,
    meta = item.homeMeta(),
)

/**
 * TV's pivot, exactly as Compose does it, but written here because theirs is `internal`.
 *
 * Leaves the focused item at 30% of the container's length and makes the content run underneath,
 * instead of dragging the card against the edge. It's what's wanted HORIZONTALLY —the row moves,
 * the focused card stays still— and what's NOT wanted vertically, where the zone measures exactly
 * two rows and that 30% falls mid-row (see [MinimalScrollBringIntoView]).
 *
 * Values taken from foundation 1.7.6's `PivotBringIntoViewSpec` so the TV feels the same as
 * before: 0.3 fraction and a 125 ms tween. Checked against what was measured on the Fire TV: in a
 * 1920 px row the focused card ends up at x=576, i.e. 0.3 × 1920.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val TvPivot = object : BringIntoViewSpec {
    override val scrollAnimationSpec = tween<Float>(
        durationMillis = 125,
        easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f),
    )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val initialTarget = 0.3f * containerSize
        val leftover = containerSize - initialTarget
        // If what's left over can't fit it whole, it gets pinned to the end instead of left clipped.
        val target = if (size <= containerSize && leftover < size) containerSize - size else initialTarget
        return offset - target
    }
}

/**
 * Brings into view with the MINIMAL scroll: if what's focused is already fully visible, nothing moves.
 *
 * It's Compose's default behavior on phones, but NOT on TV. On a leanback device
 * (`android.software.leanback`, i.e. the Fire TV) `LocalBringIntoViewSpec` starts out set to
 * `PivotBringIntoViewSpec`, which is a different thing: not "make it visible" but "always keep it
 * at 30% of the visible height" (`parentFraction = 0.3f`). With the rows zone measuring exactly
 * two rows, that 30% falls mid-row —158.4 px out of 528— and doesn't line up with any boundary.
 *
 * The result, measured on the Fire TV: moving from one card to the next one over, the pivot asked
 * to move up 106.4 px to reposition the card at its 30%, and the snap below sent it back to the
 * boundary. A bounce up and down on EVERY card change, even though the movement was horizontal and
 * there was absolutely nothing that needed bringing into view.
 *
 * With the minimal scroll, moving horizontally leaves the vertical scroll still (verified: three
 * presses in a row, zero list movement) and going down still snaps to the row.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val MinimalScrollBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val topEdge = offset
        val bottomEdge = offset + size
        return when {
            // Already fits whole, or is bigger than the viewport: nothing to correct.
            topEdge >= 0f && bottomEdge <= containerSize -> 0f
            topEdge < 0f && bottomEdge > containerSize -> 0f
            // Overflows on one side: moves just enough on that side.
            abs(topEdge) < abs(bottomEdge - containerSize) -> topEdge
            else -> bottomEdge - containerSize
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    /** Plays a live channel directly (channel code), without going through "En vivo". */
    onPlayLive: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLive: () -> Unit,
    /** Caracol's section: its catalog and its live channels. */
    onOpenCaracol: () -> Unit,
    /** Navigate the Magis catalog by sections (series and, with the code set, 18+). */
    onOpenCategorias: () -> Unit,
    /** Listing of every home row as per-category shortcuts. */
    onOpenCategoriasHome: () -> Unit,
    /** A Magis card was picked: a movie plays, a series opens its chapters (see ArkivTvRoot.openMagis). */
    onOpenMagis: (com.arkiv.player.data.gateway.CatalogItem) -> Unit,
    /** "Ver todo" of a Magis row. */
    onBrowseMagisRow: (rowId: String, title: String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.settings, graph.magisHomeCatalog, graph.hasInternet, graph.homeReloads) } },
    )
    // A Magis root that failed on the way in (e.g. a cold start before the network is up) gets
    // another chance each time this screen comes back to the front; see HomeViewModel.magisRows.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val hasInternet by graph.hasInternet.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val magisRows by vm.magisRows.collectAsStateWithLifecycle()
    val seedsExhausted by graph.seedsExhausted.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Caracol Streaming (Ditu) only carries Colombian content, and its nav button led plenty of
    // people outside Colombia into a catalog with nothing for them. `deviceCountry` is the same
    // free, no-permission, no-network signal `countryChannelsForHome` already uses for the live
    // channels row -- SIM, then time zone, then locale.
    val isColombia = remember { deviceCountry(context) == "CO" }

    // "Para ti" recommendations: read straight from Room, same as the recent live channels below
    // -- a read-only row that doesn't need its own ViewModel. Until Task 5 these arrived through
    // cloud sync (CloudSyncManager, removed in that pruning along with the rest of pairing/sync),
    // which left the `recomendaciones` table empty in practice for a while. Since sub-project 4 it
    // gets repopulated again by a completely different, on-device path: `ForYouGenerator`
    // (`data/recomendaciones`) asks Kilo directly and writes here through
    // `AppGraph.forYouGenerator`, triggered from `repo.onEpisodeFinished` whenever something
    // finishes playing -- no server of its own involved.
    val recommendationDao = remember { graph.database.recommendationDao() }
    val aggregator = remember { graph.recommendationAggregator }
    val recommendations by recommendationDao.observeActive().collectAsStateWithLifecycle(initialValue = emptyList())

    // Recent live channels -- same criterion as the phone's home (see its KDoc in HomeScreen.kt):
    // read directly from Room, without spinning up LiveViewModel (which talks to the gateway) just
    // for this row. Capped at 10: quick access, not the full history.
    val liveRecentDao = remember { graph.database.liveRecentDao() }
    val liveCacheDao = remember { graph.database.liveChannelCacheDao() }
    val liveRawRecents by liveRecentDao.flowRecent(10).collectAsStateWithLifecycle(initialValue = emptyList())
    var liveCacheByCode by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(liveRawRecents) {
        if (liveRawRecents.isNotEmpty()) {
            liveCacheByCode = liveCacheDao.byCodes(liveRawRecents.map { it.code }).associateBy { it.code }
        }
    }
    val recentChannels = remember(liveRawRecents, liveCacheByCode) {
        recentChannelsForHome(liveRawRecents, liveCacheByCode)
    }

    // Device's country channels, same as the phone's home (see countryChannelsForHome): so the
    // row serves something from the very first open, with nothing watched yet. It matters more
    // here than on the phone -- this TV may have no SIM, which is why detection looks at the time
    // zone before the language.
    var countryChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        countryChannels = countryChannelsForHome(
            context = context,
            api = graph.liveCatalog,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
    }
    val channelsRow = remember(recentChannels, countryChannels) {
        homeChannelsRow(recentChannels, countryChannels)
    }

    // The row GROWS after being painted: recents come from Room (instant) and the country's may
    // come from the network. With a fresh cache (24h, see FRESHNESS_MS) they arrive fast enough
    // that it's not noticeable; with an expired cache they arrive late and the row rearranges
    // under the user, leaving the scroll shifted onto the first new one. That's why the symptom is
    // intermittent.
    //
    // On the country's arriving, it goes back to the start, which is where the channels that WERE
    // actually watched are. The focus guard is what avoids swapping one bug for another: if at
    // that moment you're navigating the row, moving the scroll would yank you off the card you're on.
    val channelsRowState = rememberLazyListState()
    var channelsRowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(countryChannels) {
        if (countryChannels.isNotEmpty() && !channelsRowFocused) {
            runCatching { channelsRowState.scrollToItem(0) }
        }
    }

    fun playChannel(channel: LiveChannel) {
        // Same mechanism as TvLiveGuideScreen.verCanal: sets the list it was "entered" with so
        // up/down in the player goes through the same channels the row shows.
        LiveZappingSource.list = channelsRow
        onPlayLive(channel.code)
    }

    // Stable backdrop (for the card) and a random one (for the hero) for an item, falling back to the thumb.
    fun backdropsOf(itemId: String): List<String> = artwork[itemId]?.backdrops ?: emptyList()
    fun cardArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).firstOrNull() ?: fallback
    fun heroArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).randomOrNull() ?: fallback

    // The hero's subtitle is the title's synopsis; when the item doesn't have one saved (Magis and
    // anime items often don't -- see the comment further down -- and so did the legacy items added
    // via the now-removed web/magnet sources) it falls back to the usual data. Never repeats the
    // title, which is already shown big above.
    fun continueFeatured(row: ContinueRow): Featured {
        // The TMDB still wins if `episode_still` has it; if not, the item's cover. The
        // archive.org thumb that used to go in between was removed in this branch's pruning.
        val thumb = row.stillUrl
            ?: row.itemThumbnailUrl
        // The focused chapter's data, which is what changes on moving between cards (the synopsis
        // above is the SERIES' and doesn't change). The rule for what's shown and what's omitted
        // lives in ChapterLabel, shared with both details.
        val meta = com.arkiv.player.ui.ChapterLabel.heroLine(
            isMovie = row.isMovie,
            season = row.season,
            episode = row.episode,
            orderIndex = row.orderIndex,
            itemId = row.itemId,
            section = row.section,
            name = row.episodeTitle,
            positionMs = row.positionMs,
            durationMs = row.durationMs,
        )
        // If the chapter line already has something, the synopsis fallback turns off: without
        // this, items with no description (web, anime, Magis) repeated the same datum twice in a
        // row, because their `displayName` already carries the number and the name inside.
        val fallback = if (meta.isNotBlank()) "" else heroFallback(row.itemTitle, row.episodeTitle ?: row.displayName)
        // The captured frame beats everything else (including `heroArt`'s backdrop), same as on
        // the phone Home's hero: it's the real scene from where you were, not the cover.
        return Featured(
            row.itemTitle,
            heroSubtitle(row.itemTitle, row.itemDescription, fallback),
            ThumbnailChoice.choose(row.framePath, heroArt(row.itemId, thumb)),
            meta = meta,
        )
    }

    fun libraryFeatured(row: LibraryRow) = Featured(
        row.title,
        heroSubtitle(
            row.title,
            row.description,
            libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount),
        ),
        heroArt(row.identifier, row.thumbnailUrl),
    )

    val scope = rememberCoroutineScope()

    val navSound = rememberNavSound()
    var featured by remember { mutableStateOf<Featured?>(null) }
    // Initial featured item: the first "continue watching", then the first library item, then
    // the first Magis title (see MagisHomeClassifier) -- the only sensible fallback left now
    // that Home has no TMDB row at all (`buildRowSpecs` now only feeds the Categories screens,
    // row browse and search's category matching, see HomeRows.kt). Without this, a fresh
    // focus reached a card by hand: with no "Continuar viendo", initial focus goes to the top bar
    // on purpose (see `barFocus` below), which never triggers a card's `onFocus` -- the other way
    // `featured` gets set.
    LaunchedEffect(library, continueWatching, artwork, magisRows) {
        if (featured == null) {
            featured = continueWatching.firstOrNull()?.let { continueFeatured(it) }
                ?: library.firstOrNull()?.let { libraryFeatured(it) }
                ?: magisFeatured(magisRows)?.let { item -> magisCardFeatured(item) }
        }
    }

    // The first card gets focus on opening, so the hero/background reflect something right away.
    // The key is that card's IDENTITY, not "is there data yet?": "continue watching" and the
    // library arrive through different flows, and if the library arrived first, focus got stuck on
    // its row; when "Continuar viendo" showed up above afterward, that row moved down and the
    // LazyColumn ended up shifted, covering exactly what needs to be seen first. With identity as
    // the key, the effect repeats when the first card changes and focus (and scroll) go back up.
    val firstCardFocus = remember { FocusRequester() }
    // Explicit state for the rows zone: without it there was no way to guarantee it starts at the
    // top. It's the piece that was missing — if the list ended up shifted, the LazyColumn did NOT
    // compose the "Continuar viendo" row, the focusRequester never attached, and focus could never
    // land there (the scroll wasn't a consequence of lost focus: it was its cause).
    val rowsListState = rememberLazyListState()

    /**
     * Snaps the scroll to the row boundary when it stops.
     *
     * The zone measures EXACTLY two rows and all of them are the same size, so aligned, two whole
     * ones fit. The problem is that focus brings the CARD into view, not the row: on scrolling
     * down, the scroll stops at the exact point where that card fits, which falls mid-row and
     * leaves half a row up top, one whole in the middle, and half at the bottom — three rows
     * peeking where only two fit.
     *
     * Rounds to the NEAREST boundary. It doesn't matter which one it lands on: since focus
     * guarantees its card is visible, the focused row is always one of the two that stay whole.
     */
    LaunchedEffect(rowsListState) {
        snapshotFlow { rowsListState.isScrollInProgress }.collect { inMotion ->
            if (inMotion) return@collect
            val offset = rowsListState.firstVisibleItemScrollOffset
            if (offset == 0) return@collect
            val height = rowsListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val target = rowsListState.firstVisibleItemIndex + if (offset > height / 2) 1 else 0
            runCatching { rowsListState.animateScrollToItem(target) }
        }
    }

    // The "Canales en vivo" row itself is conditionally included in this rows list (see
    // `if (channelsRow.isNotEmpty())` below) -- it doesn't exist there at all until this arrives.
    // Same root cause as `countryChannels`' own fix for `channelsRowState` above, one level up: if
    // it appears late (country channels can come from the network) after the rows region already
    // settled below where it now sits, the newly-inserted row ends up above what's currently
    // visible instead of on screen. Snap the whole rows region back to the top the first time it
    // appears -- once only, so it doesn't keep fighting someone who's been browsing discovery rows
    // since.
    var channelsRowAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(channelsRow.isNotEmpty()) {
        if (channelsRow.isNotEmpty() && !channelsRowAppeared) {
            channelsRowAppeared = true
            runCatching { rowsListState.scrollToItem(0) }
        }
    }

    // Initial focus. Used to land on the library's first card; those rows don't exist anymore, and
    // leaving focus loose is exactly the bug that cost the long comment below: Android handed it to
    // whatever got composed next —the discovery rows—, and bringing those into view scrolled the
    // home all the way to "En cartelera".
    //
    // With no "Continuar viendo", focus goes to the TOP BAR, the only deterministic zone: it
    // doesn't live inside the LazyColumn, so it's always composed and focusing it can't scroll
    // anything. And it leaves the user one click from their library, which is what they'll want if
    // nothing's been started.
    val barFocus = remember { FocusRequester() }
    val firstFocusKey = continueWatching.firstOrNull()?.episodeId
    LaunchedEffect(firstFocusKey) {
        delay(200)
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            if (firstFocusKey != null) {
                // Go back to the top BEFORE requesting focus: if the list is shifted, the first
                // row isn't even composed and the requester doesn't exist, so retrying alone isn't
                // enough.
                runCatching { rowsListState.scrollToItem(0) }
                landed = runCatching { firstCardFocus.requestFocus() }.isSuccess
            } else {
                landed = runCatching { barFocus.requestFocus() }.isSuccess
            }
            if (!landed) delay(50)
        }
    }

    // Fixed-size cards and rows: the rows zone measures EXACTLY 2 rows (label + landscape card),
    // and the hero above —immovable— takes up the rest with weight(1f).
    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad

    // Hero background drift: 0 = all the way to the left of the slack, 1 = all the way to the
    // right. Goes back and forth so there's no jump on restarting, and slow enough that it reads
    // as the image "breathing", not as an animation. See the AsyncImage's graphicsLayer.
    val heroDrift by rememberInfiniteTransition(label = "heroDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = HERO_DRIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDriftX",
    )

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        if (!hasInternet) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFFB00020))
                    .padding(horizontal = 48.dp, vertical = 8.dp)
                    .zIndex(10f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Sin conexión — revisa tu red",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Fixed immersive background: focused item's backdrop + gradients.
        Crossfade(targetState = featured?.imageUrl, animationSpec = tween(450), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd)
                        // Slow background drift: the image gets enlarged a bit and wanders WITHIN
                        // that slack, so a border never shows. The travel goes exactly up to the
                        // margin the scale gives -- that's why the math comes from `size`, not a
                        // fixed dp number that would overshoot on another screen.
                        .graphicsLayer {
                            val margin = size.width * (HERO_SCALE - 1f) / 2f
                            scaleX = HERO_SCALE
                            scaleY = HERO_SCALE
                            translationX = (heroDrift * 2f - 1f) * margin
                        },
                )
                // Horizontal gradient: black on the left to read the text.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                // Vertical gradient: black at the bottom to blend into the rows.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            // --- FIXED HERO (doesn't scroll; stays immovable up top, takes up the leftover space) ---
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp)) {
                // Top bar.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "KINO",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ArkivRed,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    TvNavButton(icon = Icons.Default.Search, label = "Buscar", onClick = onOpenSearch)
                    TvNavButton(icon = Icons.Default.Refresh, label = "Recargar", onClick = { graph.reloadHomeCatalog() })
                    TvNavButton(
                        icon = Icons.Default.GridView,
                        label = "Categorías",
                        onClick = onOpenCategoriasHome,
                    )
                    TvNavButton(
                        icon = Icons.Default.PlayCircle,
                        label = "Xuper",
                        onClick = onOpenCategorias,
                    )
                    TvNavButton(
                        icon = Icons.Default.VideoLibrary,
                        label = "Mi biblioteca",
                        onClick = onOpenLibrary,
                        modifier = Modifier.focusRequester(barFocus),
                    )
                    TvNavButton(icon = Icons.Default.LiveTv, label = "En vivo", onClick = onOpenLive)
                    if (isColombia) {
                        TvNavButton(icon = Icons.Default.Tv, label = "Caracol", onClick = onOpenCaracol)
                    }
                    // No "Torrent" button: this branch has no torrents, and ArkivTvRoot registers
                    // no "torrent" route.
                    TvNavButton(icon = Icons.Default.Settings, label = "Ajustes", onClick = onOpenSettings)
                }

                Spacer(Modifier.weight(1f))

                // Focused item's title/description, bottom left.
                featured?.let { f ->
                    Text(
                        f.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                    if (f.meta.isNotBlank()) {
                        Text(
                            f.meta,
                            style = MaterialTheme.typography.titleSmall,
                            // White and not ArkivRed: over the hero's backdrop —which can be dark,
                            // saturated or red— the brand red gets lost, and this line is exactly
                            // the one that says where you were.
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
                    if (f.subtitle.isNotBlank()) {
                        Text(
                            f.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
                }
            }

            // --- ROWS (the only zone that scrolls; fixed height = exactly 2 rows) ---
            // LazyColumn instead of Column+verticalScroll: a Column would compose every Magis row's
            // LazyRow of cards at once on opening the home. LazyColumn only composes what's visible.
            // (Task 3's ~40 TMDB discovery rows needed this even more, each with its own network
            // fetch on entering the screen -- that per-row lazy load is gone along with those rows;
            // the Magis rows below arrive all at once from a single cached fetch, see `magisRows`.)
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowsRegionHeight)
                        .padding(top = rowsTopPad),
                ) {
                    if (seedsExhausted) {
                        item(key = "seeds_exhausted") {
                            Text(
                                "Por ahora no hay sesiones disponibles para tu zona. Estamos publicando " +
                                    "nuevas; volvé a intentar en un rato o usa \"Sembrar semillas\" en Ajustes → App.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArkivTextSecondary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue_watching") {
                            TvRowLabel("Continuar viendo", labelHeight)
                            // The TV pivot (focused card at 30%) is kept HERE, horizontally: it's
                            // what makes the row run under a card that stays still instead of
                            // dragging it against the edge. What got in the way was the VERTICAL
                            // pivot (see MinimalScrollBringIntoView).
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(continueWatching, key = { it.episodeId }) { row ->
                                        val progress = if (row.durationMs > 0) row.positionMs.toFloat() / row.durationMs else 0f
                                        // The usual fallback, for when there's neither a still nor
                                        // a backdrop. The archive.org thumb that used to go before
                                        // it was removed in this branch's pruning.
                                        val thumb = row.itemThumbnailUrl
                                        val isFirst = row.episodeId == continueWatching.first().episodeId
                                        TvWideCard(
                                            title = row.itemTitle,
                                            // The captured frame wins first (it's the chapter's
                                            // real scene). HERE, and only here, the CHAPTER's still
                                            // beats the series' backdrop: this row shows a chapter,
                                            // not the series. Everywhere else in the home (and in
                                            // the background hero) the backdrop still wins, since
                                            // it's the title's image. Without this inversion the
                                            // still never showed up: `cardArt` tries
                                            // `backdropsOf(itemId)` first, and every item has a
                                            // backdrop —Magis's from the portal, the rest from
                                            // TMDB—, so the still only ever came in as a fallback
                                            // for something that was never missing.
                                            imageUrl = ThumbnailChoice.choose(row.framePath, row.stillUrl, cardArt(row.itemId, thumb)),
                                            progress = progress,
                                            cardHeight = cardHeight,
                                            modifier = if (isFirst) Modifier.focusRequester(firstCardFocus) else Modifier,
                                            onFocus = { navSound(); featured = continueFeatured(row) },
                                            onClick = { onPlayEpisode(row.episodeId) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    // Recommendations ("Para ti"): goes HERE, AFTER "Continuar viendo" and not
                    // before, and it's not cosmetic. This row arrives async -- generated on-device
                    // by `ForYouGenerator` with Kilo, which can take a while (see its KDoc) -- and
                    // can show up LATE, with the home already drawn and focus already placed (see
                    // the `firstFocusKey` LaunchedEffect above). "Continuar viendo" is that initial
                    // focus's anchor; putting "Para ti" BELOW it means that if it shows up all at
                    // once, it doesn't push down what's above or steal anyone's focus -- it's
                    // exactly the bug already fought here (see the long comment about
                    // `firstFocusKey`/`rowsListState` a few lines up).
                    if (showForYouRow(recommendations)) {
                        item(key = "para_ti") {
                            TvRowLabel("Para ti", labelHeight)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(recommendations, key = { it.id }) { rec ->
                                        TvLandscapeCard(
                                            title = rec.titulo,
                                            imageUrl = rec.posterUrl.ifBlank { null },
                                            cardHeight = cardHeight,
                                            onFocus = { navSound(); featured = recommendationFeatured(rec) },
                                            onClick = {
                                                // Saves to the library and opens the DETAIL (same
                                                // `onOpenItem` "Continuar viendo" uses), by explicit
                                                // request and not playing directly like
                                                // TvCatalogSections does: if the recommendation is
                                                // a series, the person has to be able to choose the
                                                // chapter.
                                                //
                                                // What gets saved is decided by
                                                // [RecommendationAggregator]: a series enters as a
                                                // WHOLE SEASON with all its chapters, not as the
                                                // loose ref that used to leave it with just one and
                                                // in the Movies row. And the source (Magis or
                                                // Caracol) comes from the `ref`, not the row's id: a
                                                // Caracol recommendation saved as Magis would play
                                                // wrong.
                                                //
                                                // The key navigated with is whatever the aggregator
                                                // returns. `null` means there's nothing to navigate
                                                // to, but not always that nothing got saved: in the
                                                // edge case where not even the chosen chapter could
                                                // save on its own (see
                                                // RecommendationAggregator.add), the series may
                                                // still have ended up in the library, just without
                                                // that chapter ready to play.
                                                scope.launch {
                                                    aggregator.add(rec)?.let(onOpenItem)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    // Live channels -- direct access without going through "En vivo": last watched
                    // on the left, then the country's channels without repeating the ones already
                    // watched, and at the end the exit to the full grid (see `homeChannelsRow`).
                    // With nothing to show, the row isn't drawn: no empty gap in the middle of the
                    // home.
                    if (channelsRow.isNotEmpty()) {
                        item(key = "live_recientes") {
                            TvRowLabel("Canales en vivo", labelHeight)
                            // The TV pivot (focused card at 30%) is kept HERE, horizontally: it's
                            // what makes the row run under a card that stays still instead of
                            // dragging it against the edge. What got in the way was the VERTICAL
                            // pivot (see MinimalScrollBringIntoView).
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    state = channelsRowState,
                                    modifier = Modifier.onFocusChanged { channelsRowFocused = it.hasFocus },
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(channelsRow, key = { it.code }) { channel ->
                                        TvLiveChannelCard(
                                            channel = channel,
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = Featured(channel.name, "Canal en vivo", channel.logo)
                                            },
                                            onClick = { playChannel(channel) },
                                        )
                                    }
                                    // At the row's end, the exit to the full grid: recents are a
                                    // shortcut, not the catalog.
                                    item(key = "live_ver_mas") {
                                        TvSeeMoreChannelsCard(
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = Featured("Ver más canales", "Canal en vivo", null)
                                            },
                                            onClick = onOpenLive,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    // Magis rows: what Xuper actually has, by type × genre (see
                    // MagisHomeClassifier). They arrive all at once from a cached fetch, so there's
                    // no per-row loading placeholder.
                    items(magisRows.orEmpty(), key = { it.id }) { row ->
                        Column {
                            TvRowLabel(row.title, labelHeight)
                            // The TV pivot (focused card at 30%) is kept HERE, horizontally: it's
                            // what makes the row run under a card that stays still instead of
                            // dragging it against the edge. See MinimalScrollBringIntoView.
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(row.shown, key = { "${row.id}-${it.id}" }) { item ->
                                        // The portal's 1920×1080 landscape art; the portrait icon only if missing.
                                        val art = item.backdrop ?: item.poster
                                        TvLandscapeCard(
                                            title = item.title,
                                            imageUrl = art,
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = magisCardFeatured(item)
                                            },
                                            onClick = { onOpenMagis(item) },
                                        )
                                    }
                                    item(key = "${row.id}-ver-mas") {
                                        TvSeeMoreRowCard(
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = Featured(row.title, "Ver más de ${row.title}", null)
                                            },
                                            onClick = { onBrowseMagisRow(row.id, row.title) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    item(key = "rows_bottom_pad") { Spacer(Modifier.height(rowGap)) }
                } // end of the rows' scrollable zone
            }
        }
    }
}

/**
 * Card (16:9, same template as [TvLandscapeCard]/[TvWideCard]) for a recent channel on the
 * "Canales en vivo" row. No overlaid title -- the name shows up top, in the hero, on focus (same
 * criterion as the TV's other rows).
 *
 * Logo if the cache has it (see [recentChannelsForHome]); if not, the same treatment as
 * `ChannelCard` in `LiveScreen.kt` (phone): gradient + the channel number, deliberate instead of a
 * broken logo. If not even the number is known yet (a just-seen channel, no cache entry for that
 * `code`), it falls back to the name's initials -- a "0" wouldn't mean anything here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLiveChannelCard(
    channel: LiveChannel,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(ArkivSurfaceHigh),
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * Last card on the "Canales en vivo" row: opens the "En vivo" section with the full grid. Same
 * template as [TvLiveChannelCard] (row height, 16:9, same focus and border) so the row doesn't
 * change height or rhythm on reaching the end.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeeMoreChannelsCard(
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "Ver más canales",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Last card on every Magis row: opens the Magis "Ver todo" (`TvMagisRowBrowseScreen`, via
 * `onBrowseMagisRow`) with that row's full grid. Same template as [TvLandscapeCard] (16:9, row
 * height) so the row doesn't change rhythm on reaching the end.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSeeMoreRowCard(
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "Ver más",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Fixed-height row label, so 2 rows fit exactly in the scrollable zone. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvRowLabel(text: String, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).padding(start = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = ArkivTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Prime Video-style top bar button: collapsed it shows only the icon; focusing it with the D-pad
 * expands it to also show the text.
 *
 * The transition is done by the text itself with AnimatedVisibility, not the container with
 * animateContentSize: that one clips content to the bounds while animating, so the text came out
 * cut off and the pill looked flat on the right side throughout the transition.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        // 50 with no `.dp` is the PERCENTAGE overload: 50% = a full pill, the max rounding
        // possible for this height. If it looks flat, the problem is clipping, not the radius.
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        // With no focus it carries no background: the icon floats loose over the backdrop. Red
        // only shows up on focus, and it's what marks where you're standing in the bar.
        // contentColor is explicit in all three states because tv-material3's default computes it
        // to contrast against the container, and it was picking a dark tone that left the text
        // black next to a white icon.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (isFocused) 16.dp else 12.dp,
                end = if (isFocused) 16.dp else 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No tint of its own: inherits the Surface's contentColor, same as the Text. Having
            // two color sources was exactly what left the icon white and the text black.
            Icon(icon, contentDescription = if (isFocused) null else label)
            AnimatedVisibility(
                visible = isFocused,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                // The inner Row keeps the spacer and the text together: if the Spacer were
                // outside, collapsing would leave an 8.dp gap next to the icon.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}
