package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginHomeRow
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.home.TvHomeLanding
import com.arkiv.player.ui.home.emptyStateNeedsRefocus
import com.arkiv.player.ui.home.homeEmptyCopy
import com.arkiv.player.ui.home.homeShowsEmptyState
import com.arkiv.player.ui.home.homeShowsLoading
import com.arkiv.player.ui.home.HOME_LOADING_LINE
import com.arkiv.player.ui.home.pluginHeroPick
import com.arkiv.player.ui.home.tvHomeDefaultLanding
import com.arkiv.player.ui.home.tvHomeLandingHeld
import com.arkiv.player.ui.live.deviceCountry
import com.arkiv.player.ui.EffectsAutoTune
import com.arkiv.player.ui.KinoWordmark
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.backdropFadeSpec
import com.arkiv.player.ui.TV_CARD_FOCUS_SCALE
import com.arkiv.player.ui.cardFocusScale
import com.arkiv.player.ui.heroFallback
import com.arkiv.player.ui.heroSubtitle
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.ui.rememberHeroDrift
import com.arkiv.player.ui.rememberReducedEffects
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.countryChannelsForHome
import com.arkiv.player.ui.player.WAIT_BETWEEN_FOCUS_ATTEMPTS_MS
import com.arkiv.player.ui.player.retryFocus
import com.arkiv.player.ui.live.recentChannelsForHome
import com.arkiv.player.ui.live.channelCircleForRow
import com.arkiv.player.ui.live.homeLiveRow
import com.arkiv.player.ui.live.providerBadge
import com.arkiv.player.ui.live.ProviderBadge
import com.arkiv.player.data.live.LiveProviderTab
import com.arkiv.player.ui.live.liveCacheForRecents
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.catalog.isLiveChannel
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the background hero shows. [meta] is the data line highlighted in white below the title:
 * the chapter label ("T1 · E5  ·  La conspiración  ·  te faltan 12 min") on "Continuar viendo", a
 * recommendation's reason ("porque terminaste Dragon Ball") on "Para ti" (see
 * [recommendationFeatured]), or the item's own synopsis on a plugin row's card (see
 * [pluginCardFeatured]).
 *
 * `internal` (not `private`) so [recommendationFeatured] and [pluginCardFeatured] can be tested
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
 * Whether the TV Home must hand focus back to its top bar: the Xuper live gate just closed
 * ([wasOn] → not [isOn]), which can remove the focused "En vivo"/"Xuper" button or channel card, and
 * nothing on the screen holds focus any more ([screenHasFocus]) -- Compose clears focus instead of
 * moving it, which strands the D-pad.
 */
internal fun liveGateNeedsRefocus(wasOn: Boolean, isOn: Boolean, screenHasFocus: Boolean): Boolean =
    wasOn && !isOn && !screenHasFocus

/** Which live surfaces the TV Home draws: the "Xuper" and "En vivo" nav buttons, and the "Canales en vivo" row. */
internal data class HomeLiveSurfaces(val xuperButton: Boolean, val liveButton: Boolean, val channelsRow: Boolean)

/**
 * [liveGateNeedsRefocus] over every live surface: any of them vanishing (the Xuper gate closing, the
 * module emptying, the row losing its last channel to a provider that went) with nothing on the
 * screen holding focus hands it back to the top bar.
 */
internal fun homeLiveNeedsRefocus(was: HomeLiveSurfaces, now: HomeLiveSurfaces, screenHasFocus: Boolean): Boolean =
    liveGateNeedsRefocus(was.xuperButton, now.xuperButton, screenHasFocus) ||
        liveGateNeedsRefocus(was.liveButton, now.liveButton, screenHasFocus) ||
        liveGateNeedsRefocus(was.channelsRow, now.channelsRow, screenHasFocus)

/** The key a Home card is restored by (`returnKey`): one format for the card and the row lookup. */
internal fun pluginCardKey(pluginId: String, rowId: String, itemId: String?) = "plugin-$pluginId-$rowId-$itemId"

/**
 * Whether any plugin row holds the card [cardKey]: false while its row has not arrived (a second
 * plugin's rows come later than the first's) and for good when the card no longer exists.
 */
internal fun homeCardsHold(cardKey: String, pluginCards: List<List<String>>): Boolean =
    pluginCards.any { cardKey in it }

/**
 * The row to scroll the list to so the card's row [rowIndex] is on screen: the one ABOVE it. The rows
 * region shows two rows, and putting the card's row first left the previous row's label clipped when
 * the list ended there; with the previous row first the card sits in the second slot, which is the
 * layout D-pad navigation gives.
 */
/** The rows list's key for the empty state ("Aún no tienes fuentes de contenido"). */
private const val EMPTY_SOURCES_KEY = "empty_sources"

internal fun homeRowScrollTarget(rowIndex: Int): Int = (rowIndex - 1).coerceAtLeast(0)

/** What the remote's Back does on the TV Home. */
internal enum class TvHomeBack {
    /** Scroll the rows back to the top and put focus on the default landing, staying on Home. */
    SCROLL_TO_TOP,

    /** Today's behaviour, handled by the nav host: press Back twice to leave the app. */
    EXIT_FLOW,
}

/**
 * Back on the TV Home: someone deep in the rows goes back to the top first, and only from there does
 * Back start the exit flow. "Deep" is a scrolled rows list, or focus on a row below the first one.
 * Focus already on the default landing (`tvHomeDefaultLanding`'s target) counts as the top even if
 * the list is scrolled (the empty state's button can sit below other rows), and the Back right after
 * a scroll to the top never scrolls again, so a landing that fails can't trap the person on Home.
 */
internal fun tvHomeBackAction(
    listAtTop: Boolean,
    focusInRows: Boolean,
    focusedRowIsFirst: Boolean,
    focusOnLanding: Boolean,
    justScrolledToTop: Boolean,
): TvHomeBack = when {
    justScrolledToTop || focusOnLanding -> TvHomeBack.EXIT_FLOW
    !listAtTop -> TvHomeBack.SCROLL_TO_TOP
    focusInRows && !focusedRowIsFirst -> TvHomeBack.SCROLL_TO_TOP
    else -> TvHomeBack.EXIT_FLOW
}

/**
 * Scrolls a lazy list until the item with [key] is composed, so a requester inside it exists. Only the
 * visible window is known, not the index of a key, so it walks the [total] items from the top. False when
 * no item carries [key].
 */
internal suspend fun revealListKey(
    key: Any,
    total: Int,
    visibleKeys: () -> List<Any>,
    scrollTo: suspend (Int) -> Unit,
): Boolean {
    if (key in visibleKeys()) return true
    for (i in 0 until total) {
        scrollTo(i)
        if (key in visibleKeys()) return true
    }
    return false
}

/**
 * Index, in the Home rows list, of the row that holds the card [cardKey] (one list of card keys per
 * plugin row), or null when no row holds it or the list is not laid out yet. The list closes with
 * the plugin rows, then [trailingItems] pad items, and what comes before varies (continue watching,
 * channels...), so the index counts back from [totalItems]. Pure so it can be tested without
 * Compose.
 */
internal fun homeRowIndexOf(
    cardKey: String,
    pluginCards: List<List<String>>,
    totalItems: Int,
    trailingItems: Int = 1,
): Int? {
    val pluginBase = totalItems - trailingItems - pluginCards.size
    if (pluginBase < 0) return null
    return pluginCards.indexOfFirst { cardKey in it }.takeIf { it >= 0 }?.let { pluginBase + it }
}

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
 * What the hero shows on focusing a plugin row's card -- and, with nothing in "Continuar viendo" or
 * the library, what it starts on (the first item of the first plugin row, see `pluginHeroPick`):
 * the plugin's name as [Featured.subtitle] and the item's synopsis as [Featured.meta]. Landscape
 * art first, the poster only if missing. Top-level for the same reason as [recommendationFeatured]:
 * pure, testable without Compose.
 */
internal fun pluginCardFeatured(row: PluginHomeRow, item: GatewayResult) = Featured(
    title = item.title,
    // A live channel says so, the way the native row's cards do ("Canal en vivo"), then whose it is.
    subtitle = if (item.isLiveChannel()) "Canal en vivo · ${row.pluginName}" else row.pluginName,
    imageUrl = item.extra["backdrop"].orEmpty().ifBlank { item.extra["poster"].orEmpty() }.ifBlank { null },
    meta = item.extra["overview"].orEmpty(),
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
    /** A plugin card was picked: the route of its info page (see `titleRoute`). */
    onOpenTitleRoute: (String) -> Unit,
    /** "Ver más" of a plugin row that carries a `ref` (the plugin declares `browse`). */
    onBrowsePluginRow: (com.arkiv.player.ui.plugin.PluginMoreTarget) -> Unit = {},
    /** "Agregar plugin" of the empty state: opens "Elige tus fuentes". */
    onOpenSourcePicker: () -> Unit = {},
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.settings, graph.homeReloads, graph.pluginHomeRows, graph.pluginsChanged) } },
    )
    val hasInternet by graph.hasInternet.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val pluginRows by vm.pluginRows.collectAsStateWithLifecycle()
    val pluginRowsSettled by vm.pluginRowsSettled.collectAsStateWithLifecycle()
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
    var liveCacheByLiveCode by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(liveRawRecents) {
        if (liveRawRecents.isNotEmpty()) {
            liveCacheByLiveCode = liveCacheForRecents(liveRawRecents, liveCacheDao)
        }
    }
    val recentChannels = remember(liveRawRecents, liveCacheByLiveCode) {
        recentChannelsForHome(liveRawRecents, liveCacheByLiveCode)
    }

    // Device's country channels, same as the phone's home (see countryChannelsForHome): so the
    // row serves something from the very first open, with nothing watched yet. It matters more
    // here than on the phone -- this TV may have no SIM, which is why detection looks at the time
    // zone before the language.
    //
    // The country part and the "Xuper" nav button follow the Xuper plugin (AppGraph.xuperLive):
    // off, the country's channels aren't even asked for; back on, they're fetched again. The row
    // itself and the "En vivo" button follow the whole live module (Xuper or any plugin with
    // channels, `liveOn`). Recents stay in Room.
    val xuperLive by graph.xuperLive.collectAsStateWithLifecycle()
    var countryChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(xuperLive) {
        countryChannels = if (!xuperLive) emptyList() else countryChannelsForHome(
            context = context,
            api = graph.liveCatalog,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
    }
    // The module's providers right now: a switched-off plugin's recents leave the row (they are kept, not deleted).
    val liveOn by graph.liveModule.available.collectAsStateWithLifecycle()
    val installedPlugins by graph.pluginAdmin.plugins.collectAsStateWithLifecycle()
    val homeEmpty = homeShowsEmptyState(installedPlugins, pluginRows.size, liveOn)
    val emptyCopy = homeEmptyCopy(installedPlugins, isTv = true)
    val homeEmptyNow by rememberUpdatedState(homeEmpty)
    val emptySourcesFocus = remember { FocusRequester() }
    // Real focus on the "Agregar plugin" button: the default landing only counts it once this is true.
    var emptySourcesFocused by remember { mutableStateOf(false) }
    val liveTabs by graph.liveModule.tabs.collectAsStateWithLifecycle()
    // Same rule as the phone (homeLiveRow): drawn only with at least one channel to list, never as
    // "Ver más canales" alone; empty module, no row.
    val channelsRow = remember(recentChannels, countryChannels, liveOn, liveTabs) {
        homeLiveRow(liveOn, recentChannels, countryChannels, available = liveTabs.map { it.id }.toSet()).orEmpty()
    }
    // Plugins still answering and nothing else to show (the library feeds the hero): a centered spinner
    // instead of a black Home. It holds no focus; the default landing stays on the top bar.
    val homeLoading = homeShowsLoading(
        installedPlugins,
        pluginRows.size,
        pluginRowsSettled,
        hasOtherContent = continueWatching.isNotEmpty() || channelsRow.isNotEmpty() ||
            showForYouRow(recommendations) || library.isNotEmpty(),
    )

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
    // And whenever the row's first card changes (the row appears, a new recent lands first): a
    // LazyRow keeps its key-anchored first visible item and can open scrolled to its end (measured
    // on the phone). Same focus guard.
    LaunchedEffect(channelsRow.firstOrNull()?.liveCode) {
        if (channelsRow.isNotEmpty() && !channelsRowFocused) {
            runCatching { channelsRowState.scrollToItem(0) }
        }
    }

    fun playChannel(channel: LiveChannel) {
        // Same mechanism as TvLiveGuideScreen.verCanal: sets the list it was "entered" with so
        // up/down in the player goes through the same channels the row shows.
        LiveZappingSource.list = channelsRow
        onPlayLive(channel.liveCode)
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
    // the first item of the first plugin Home row (see pluginHeroPick) -- the only sensible
    // fallback left now
    // that Home has no TMDB row at all (`buildRowSpecs` now only feeds the Categories screens,
    // row browse and search's category matching, see HomeRows.kt). Without this, a fresh
    // focus reached a card by hand: with no "Continuar viendo", initial focus goes to the top bar
    // on purpose (see `barFocus` below), which never triggers a card's `onFocus` -- the other way
    // `featured` gets set.
    LaunchedEffect(library, continueWatching, artwork, pluginRows) {
        if (featured == null) {
            featured = continueWatching.firstOrNull()?.let { continueFeatured(it) }
                ?: library.firstOrNull()?.let { libraryFeatured(it) }
                ?: pluginHeroPick(pluginRows)?.let { pick -> pluginCardFeatured(pick.row, pick.item) }
        }
    }

    // A plugin card (Xuper's among them) opens its info page; the page plays or lists chapters.
    val openPluginItem = com.arkiv.player.ui.titleinfo.rememberTitleOpener(onOpenRoute = onOpenTitleRoute, onPlay = onPlayEpisode)

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

    // The plugin card whose info page was opened from here (see `pluginCardKey`), so Back lands on
    // it again instead of on the top bar. Saveable: it has to outlive this composition, which
    // leaves when the page opens. Read once per visit into `cardToRestore` and cleared right away,
    // so it only applies to the Back that follows the tap and never to a later visit.
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    val cardToRestore = remember { returnKey }
    LaunchedEffect(Unit) { returnKey = null }
    val returnFocus = remember { FocusRequester() }

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
        // Not when coming back to a card: that scroll would take it out of view.
        if (channelsRow.isNotEmpty() && !channelsRowAppeared && cardToRestore == null) {
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
    // A live surface can take away the node that holds focus while this screen is showing: the
    // "Xuper" button (the Xuper gate closes, e.g. its plugin gets marked damaged), the "En vivo"
    // button (the live module empties) or the channels row (its last channel's provider went).
    // Compose then clears focus instead of moving it, and the D-pad is stranded. A per-node latch
    // can't catch it (the removed node reports "unfocused" before any effect runs), so this watches
    // the whole screen: right after a surface vanishes, if nothing here holds focus, "Mi biblioteca"
    // takes it ([homeLiveNeedsRefocus]). Retried through `retryFocus` against the button's OWN
    // focus state: `FocusRequester.requestFocus()` never reports failure (see its KDoc).
    var screenHasFocus by remember { mutableStateOf(false) }
    var libraryFocused by remember { mutableStateOf(false) }
    val liveSurfaces = HomeLiveSurfaces(xuperButton = xuperLive, liveButton = liveOn, channelsRow = channelsRow.isNotEmpty())
    var lastLiveSurfaces by remember { mutableStateOf(liveSurfaces) }
    LaunchedEffect(liveSurfaces) {
        val was = lastLiveSurfaces
        lastLiveSurfaces = liveSurfaces
        // One frame for the removed node to clear focus before asking who holds it.
        delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
        if (!homeLiveNeedsRefocus(was, liveSurfaces, screenHasFocus)) return@LaunchedEffect
        retryFocus(
            isAlreadyFocused = { libraryFocused },
            wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
            request = { barFocus.requestFocus() },
        )
    }

    // The empty state's button can leave while it holds focus (a source became usable): the top bar takes it.
    var lastHomeEmpty by remember { mutableStateOf(homeEmpty) }
    LaunchedEffect(homeEmpty) {
        val was = lastHomeEmpty
        lastHomeEmpty = homeEmpty
        delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
        if (emptyStateNeedsRefocus(was, homeEmpty, screenHasFocus)) {
            retryFocus(isAlreadyFocused = { libraryFocused }, wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) }, request = { barFocus.requestFocus() })
        }
    }
    val firstFocusKey = continueWatching.firstOrNull()?.episodeId
    // Real focus on the first "Continuar viendo" card (the FIRST_CARD landing), for Back's rule below.
    var firstCardFocused by remember { mutableStateOf(false) }

    /**
     * Puts focus on the default landing (`tvHomeDefaultLanding`): the first "Continuar viendo" card, the
     * empty state's button, or the top bar. Used on opening and by Back's scroll to the top.
     */
    suspend fun landOnDefault() {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            val landing = tvHomeDefaultLanding(homeEmptyNow, hasContinueCard = firstFocusKey != null)
            val target = when (landing) {
                TvHomeLanding.FIRST_CARD -> {
                    // Go back to the top BEFORE requesting focus: if the list is shifted, the first
                    // row isn't even composed and the requester doesn't exist, so retrying alone isn't
                    // enough.
                    runCatching { rowsListState.scrollToItem(0) }
                    firstCardFocus
                }
                TvHomeLanding.ADD_SOURCES -> {
                    // Same reason as the first card: "Continuar viendo", "Para ti" or the live recents can
                    // sit above the empty state and keep it out of the composed window, so bring it in first.
                    revealListKey(
                        EMPTY_SOURCES_KEY,
                        rowsListState.layoutInfo.totalItemsCount,
                        visibleKeys = { rowsListState.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollTo = { runCatching { rowsListState.scrollToItem(it) } },
                    )
                    emptySourcesFocus
                }
                TvHomeLanding.TOP_BAR -> barFocus
            }
            val requested = runCatching { target.requestFocus() }.isSuccess
            landed = tvHomeLandingHeld(landing, requested, emptySourcesFocused)
            if (!landed) delay(50)
        }
    }

    // True once focus is back on the card `cardToRestore` names: from then on the default landing
    // below must not take it away (a late "Continuar viendo" changes `firstFocusKey`).
    var cardRestored by remember { mutableStateOf(false) }
    LaunchedEffect(firstFocusKey) {
        delay(200)
        if (cardToRestore != null && !cardRestored) {
            val pluginCards = {
                pluginRows.map { r -> r.items.map { pluginCardKey(r.pluginId, r.id, it.extra["pluginItemId"]) } }
            }
            // A plugin's rows arrive as each plugin answers (Xuper's from its own cache), so wait
            // for the card's OWN row, not for any plugin's.
            withTimeoutOrNull(3_000) {
                snapshotFlow { homeCardsHold(cardToRestore, pluginCards()) }.first { it }
            }
            // A card no row holds (it left the catalog, or its plugin never answered) cannot be
            // restored: fall through to the default landing instead of searching for it.
            if (homeCardsHold(cardToRestore, pluginCards())) {
                repeat(40) {
                    if (cardRestored) return@repeat
                    // Aim at the card's row: a card that is not composed cannot take focus, and one whose
                    // row is only prefetched takes it without being seen. The row's index counts back from
                    // the end of the list; while the list lags behind the rows it is null or stale, and
                    // the next try (after the delay) gets it.
                    homeRowIndexOf(cardToRestore, pluginCards(), rowsListState.layoutInfo.totalItemsCount)
                        ?.let { runCatching { rowsListState.scrollToItem(homeRowScrollTarget(it)) } }
                    if (runCatching { returnFocus.requestFocus() }.isSuccess) cardRestored = true else delay(60)
                }
            }
        }
        if (cardRestored) return@LaunchedEffect
        landOnDefault()
    }

    // Fixed-size cards and rows: the rows zone measures EXACTLY 2 rows (label + landscape card),
    // and the hero above —immovable— takes up the rest with weight(1f). The live row's circles (circle +
    // name) are sized to this same cardHeight (see tvChannelCircle), so every row is one rowUnit.
    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad

    // Decorative motion off? The person's choice, or this device measured too slow for it (see
    // EffectsPolicy). It drives the hero drift and the backdrop crossfade below; the Home is also where
    // a slow device gets judged, because it's the screen with the most going on.
    val reducedEffects = rememberReducedEffects()
    EffectsAutoTune(reducedEffects)

    // Back deep in the rows goes back to the top first ([tvHomeBackAction]); from the top, the nav
    // host's double-Back-to-exit runs as before. This handler is registered after the nav host's, so
    // it wins while enabled; dialogs (the OTA update) have their own window and keep Back first.
    var rowsHaveFocus by remember { mutableStateOf(false) }
    // The rows list item (by key) holding focus, null while focus is outside the rows.
    var focusedRowKey by remember { mutableStateOf<Any?>(null) }
    var justScrolledToTop by remember { mutableStateOf(false) }
    fun Modifier.tracksRowFocus(key: Any): Modifier = onFocusChanged {
        if (it.hasFocus && focusedRowKey != key) {
            focusedRowKey = key
            justScrolledToTop = false
        }
    }
    val hasContinueNow by rememberUpdatedState(firstFocusKey != null)
    val backAction by remember {
        derivedStateOf {
            val landingFocused = when (tvHomeDefaultLanding(homeEmptyNow, hasContinueNow)) {
                TvHomeLanding.FIRST_CARD -> firstCardFocused
                TvHomeLanding.ADD_SOURCES -> emptySourcesFocused
                TvHomeLanding.TOP_BAR -> libraryFocused
            }
            tvHomeBackAction(
                listAtTop = rowsListState.firstVisibleItemIndex == 0 && rowsListState.firstVisibleItemScrollOffset == 0,
                focusInRows = rowsHaveFocus,
                focusedRowIsFirst = focusedRowKey != null &&
                    focusedRowKey == rowsListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
                focusOnLanding = landingFocused,
                justScrolledToTop = justScrolledToTop,
            )
        }
    }
    BackHandler(enabled = backAction == TvHomeBack.SCROLL_TO_TOP) {
        justScrolledToTop = true
        scope.launch {
            if (reducedEffects) runCatching { rowsListState.scrollToItem(0) }
            else runCatching { rowsListState.animateScrollToItem(0) }
            // The hero goes back to what Home opens on; landing on a card updates it again anyway.
            featured = continueWatching.firstOrNull()?.let { continueFeatured(it) }
                ?: library.firstOrNull()?.let { libraryFeatured(it) }
                ?: pluginHeroPick(pluginRows)?.let { pick -> pluginCardFeatured(pick.row, pick.item) }
                ?: featured
            landOnDefault()
            // Landing moves focus between rows, which clears the flag: set it again, so the next Back
            // (until the person moves to another row) reaches the exit flow even if the landing failed.
            justScrolledToTop = true
        }
    }

    // Hero background drift: 0 = all the way to the left of the slack, 1 = all the way to the
    // right. Goes back and forth so there's no jump on restarting, and slow enough that it reads
    // as the image "breathing", not as an animation. See the AsyncImage's graphicsLayer.
    val heroDrift by rememberHeroDrift(reducedEffects, HERO_DRIFT_MS)

    Box(Modifier.fillMaxSize().background(ArkivBlack).onFocusChanged { screenHasFocus = it.hasFocus }) {
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
        Crossfade(targetState = featured?.imageUrl, animationSpec = backdropFadeSpec(reducedEffects), label = "bg") { url ->
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
                    KinoWordmark(height = 34.dp, modifier = Modifier.padding(end = 16.dp))
                    TvNavButton(icon = Icons.Default.Search, label = "Buscar", onClick = onOpenSearch)
                    TvNavButton(icon = Icons.Default.Refresh, label = "Recargar", onClick = { graph.reloadHomeCatalog() })
                    TvNavButton(
                        icon = Icons.Default.GridView,
                        label = "Categorías",
                        onClick = onOpenCategoriasHome,
                    )
                    // The native Xuper catalog tree ("categorias" route) and the live guide only
                    // while the Xuper plugin is on, like the rest of its native surfaces.
                    if (xuperLive) {
                        TvNavButton(
                            icon = Icons.Default.PlayCircle,
                            label = "Xuper",
                            onClick = onOpenCategorias,
                        )
                    }
                    TvNavButton(
                        icon = Icons.Default.VideoLibrary,
                        label = "Mi biblioteca",
                        onClick = onOpenLibrary,
                        modifier = Modifier.focusRequester(barFocus).onFocusChanged { libraryFocused = it.isFocused },
                    )
                    // The live guide follows the whole module: Xuper or any plugin with channels.
                    if (liveOn) {
                        TvNavButton(
                            icon = Icons.Default.LiveTv,
                            label = "En vivo",
                            onClick = onOpenLive,
                        )
                    }
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
            // LazyColumn instead of Column+verticalScroll: a Column would compose every plugin row's
            // LazyRow of cards at once on opening the home. LazyColumn only composes what's visible.
            // (Task 3's ~40 TMDB discovery rows needed this even more, each with its own network
            // fetch on entering the screen -- that per-row lazy load is gone along with those rows;
            // the plugin rows below arrive all at once per plugin, see `PluginHomeRows`.)
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowsRegionHeight)
                        .padding(top = rowsTopPad)
                        .onFocusChanged {
                            rowsHaveFocus = it.hasFocus
                            if (!it.hasFocus) focusedRowKey = null
                        },
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
                                    modifier = Modifier.tracksRowFocus("continue_watching"),
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
                                            modifier = if (isFirst) Modifier.focusRequester(firstCardFocus).onFocusChanged { firstCardFocused = it.isFocused } else Modifier,
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
                                    modifier = Modifier.tracksRowFocus("para_ti"),
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
                                    modifier = Modifier.onFocusChanged { channelsRowFocused = it.hasFocus }.tracksRowFocus("live_recientes"),
                                    // The first CIRCLE, not its wider item, lines up with the label.
                                    contentPadding = PaddingValues(
                                        horizontal = 48.dp - tvChannelCircle(cardHeight).let { (it.itemWidthDp - it.diameterDp) / 2f }.dp,
                                    ),
                                    // Tighter than the poster rows: each circle's item is already wider
                                    // than the circle (room for its name), so 8 dp reads as the same gap.
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(channelsRow, key = { it.liveCode }) { channel ->
                                        TvLiveChannelCard(
                                            channel = channel,
                                            badge = providerBadge(channel, liveTabs),
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

                    if (homeEmpty) {
                        item(key = EMPTY_SOURCES_KEY) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp).tracksRowFocus(EMPTY_SOURCES_KEY),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(emptyCopy.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text(emptyCopy.line, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                                TvCompactAction(
                                    label = emptyCopy.action,
                                    icon = Icons.Default.Add,
                                    modifier = Modifier
                                        .focusRequester(emptySourcesFocus)
                                        .onFocusChanged { emptySourcesFocused = it.hasFocus },
                                    onClick = onOpenSourcePicker,
                                )
                            }
                        }
                    }

                    // Plugin rows (Xuper's among them). The plugin's name rides on each card as its badge.
                    items(pluginRows, key = { "plugin-${it.pluginId}-${it.id}" }) { row ->
                        Column(Modifier.tracksRowFocus("plugin-${row.pluginId}-${row.id}")) {
                            TvRowLabel(row.title, labelHeight)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(row.items, key = { "${row.pluginId}-${row.id}-${it.extra["pluginItemId"]}" }) { item ->
                                        val art = item.extra["backdrop"].orEmpty().ifBlank { item.extra["poster"].orEmpty() }.ifBlank { null }
                                        val cardKey = pluginCardKey(row.pluginId, row.id, item.extra["pluginItemId"])
                                        // A live channel wears "EN VIVO" in red instead of the plugin's
                                        // name (one badge slot); the hero still names the plugin on focus.
                                        val live = com.arkiv.player.ui.catalog.liveBadge(item)
                                        TvLandscapeCard(
                                            title = item.title,
                                            imageUrl = art,
                                            cardHeight = cardHeight,
                                            modifier = if (cardKey == cardToRestore) Modifier.focusRequester(returnFocus) else Modifier,
                                            badge = live ?: row.pluginName,
                                            badgeColor = if (live != null) ArkivRed else androidx.compose.ui.graphics.Color(row.color),
                                            onFocus = {
                                                navSound()
                                                featured = pluginCardFeatured(row, item)
                                            },
                                            onClick = {
                                                returnKey = cardKey
                                                openPluginItem(item)
                                            },
                                        )
                                    }
                                    val moreRef = row.ref
                                    if (moreRef != null) {
                                        item(key = "${row.pluginId}-${row.id}-ver-mas") {
                                            TvSeeMoreRowCard(
                                                cardHeight = cardHeight,
                                                onFocus = {
                                                    navSound()
                                                    featured = Featured(row.title, "Ver más de ${row.title}", null)
                                                },
                                                onClick = { onBrowsePluginRow(com.arkiv.player.ui.plugin.PluginMoreTarget.Browse(row.pluginId, row.title, moreRef)) },
                                            )
                                        }
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

        // Plugins still answering (homeShowsLoading): centered over the whole screen, below the top bar's
        // line of sight, and not focusable, so D-pad focus stays on the top bar.
        if (homeLoading) {
            Column(
                Modifier.align(Alignment.Center).padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = ArkivRed, modifier = Modifier.size(40.dp))
                Text(HOME_LOADING_LINE, style = MaterialTheme.typography.titleMedium, color = ArkivTextSecondary)
            }
        }
    }
}

/** Height of the channel name under each circle on the "Canales en vivo" row. */
private val TV_CHANNEL_NAME_HEIGHT = 18.dp

/** The focus ring's width on a channel circle, the same 3 dp white every TV card wears. */
private val TV_CHANNEL_RING = 3.dp

/**
 * The circle sizes for a live row [cardHeight] tall: circle + gap + name fill exactly the poster rows'
 * card height, so the rows zone keeps showing exactly two rows (see `rowUnit`). Sized for the full
 * focus zoom even when it's reduced, so the row doesn't change layout with that setting.
 */
private fun tvChannelCircle(cardHeight: Dp) = channelCircleForRow(
    rowHeightDp = cardHeight.value,
    nameHeightDp = TV_CHANNEL_NAME_HEIGHT.value,
    focusScale = TV_CARD_FOCUS_SCALE,
    ringDp = TV_CHANNEL_RING.value,
)

/**
 * A channel on the "Canales en vivo" row: a round tile with the logo centered and fitted with room to
 * spare (wide logos never touch the edge), and the name below. The hero also names it on focus.
 *
 * Logo if the cache has it (see [recentChannelsForHome]); if not, the same treatment as
 * `ChannelCard` in `LiveScreen.kt` (phone): gradient + the channel number, deliberate instead of a
 * broken logo. If not even the number is known yet (a just-seen channel, no cache entry for that
 * `code`), it falls back to the name's initials -- a "0" wouldn't mean anything here.
 *
 * The circle is the single focusable; the provider badge sits on its top-right, outside the logo's
 * area, and doesn't zoom with it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLiveChannelCard(
    channel: LiveChannel,
    /** The channel's provider, only with more than one ([providerBadge]). */
    badge: LiveProviderTab?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val spec = tvChannelCircle(cardHeight)
    TvChannelCircleItem(
        spec = spec,
        label = channel.name,
        badge = badge,
        modifier = modifier,
        onFocus = onFocus,
        onClick = onClick,
    ) {
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
 * Last item on the "Canales en vivo" row: opens the "En vivo" section with the full grid. The same
 * circle as [TvLiveChannelCard] (same size, focus ring and zoom) so the row keeps its rhythm at the end.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeeMoreChannelsCard(
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    TvChannelCircleItem(
        spec = tvChannelCircle(cardHeight),
        label = "Ver más",
        badge = null,
        modifier = modifier,
        onFocus = onFocus,
        onClick = onClick,
    ) {
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
 * The shared frame of the live row's circles: the round focusable (white ring + the cards' focus zoom),
 * the optional provider badge on its top-right and the one-line name below, all exactly
 * `spec.diameter + spec.nameGap + TV_CHANNEL_NAME_HEIGHT` tall.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChannelCircleItem(
    spec: com.arkiv.player.ui.live.ChannelCircleSpec,
    label: String,
    badge: LiveProviderTab?,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.width(spec.itemWidthDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(spec.diameterDp.dp)) {
            Card(
                onClick = onClick,
                modifier = modifier
                    .align(Alignment.Center)
                    .size(spec.diameterDp.dp)
                    .onFocusChanged { if (it.isFocused) onFocus() },
                shape = CardDefaults.shape(CircleShape),
                scale = cardFocusScale(LocalReducedEffects.current),
                colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        androidx.compose.foundation.BorderStroke(TV_CHANNEL_RING, Color.White),
                        shape = CircleShape,
                    ),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(ArkivSurfaceHigh)) { content() }
            }
            badge?.let { ProviderBadge(it, Modifier.align(Alignment.TopEnd).zIndex(1f)) }
        }
        Spacer(Modifier.height(spec.nameGapDp.dp))
        Box(Modifier.fillMaxWidth().height(TV_CHANNEL_NAME_HEIGHT), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Last card on a plugin row that declares `browse`: opens that row's "Ver más" (via
 * `onBrowsePluginRow`). Same template as [TvLandscapeCard] (16:9, row height) so the row doesn't
 * change rhythm on reaching the end.
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
        scale = cardFocusScale(LocalReducedEffects.current),
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
