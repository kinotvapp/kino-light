package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Magis catalog navigation: **the same shape as the home** — each section is a horizontal row
 * with its name on top.
 *
 * Used to be two columns (the section list on the left, a grid on the right). Changed because it
 * forced learning a second way of moving around inside the same app: on the home you go down
 * between rows and move one card at a time, and here you had to jump between columns to see what
 * each section had. With rows, picking a section and seeing its content are the SAME gesture.
 *
 * Reuses the home's pieces on purpose and not copies of them ([TvRowLabel], [TvLandscapeCard] and
 * —what matters most— [TvPivot] and [MinimalScrollBringIntoView]): Fire TV's focus behavior is
 * exactly what took effort to get right there, and two implementations would drift apart at the
 * first fix.
 *
 * Picking an item PLAYS it, through two different paths depending on where it came from: a normal
 * one, which saves it in the library like anything that plays (and so it gets "continue
 * watching"), and an ephemeral one for adult content, which doesn't write a single row — see
 * [com.arkiv.player.playback.MagisEphemeral]. The separation isn't cosmetic: on 2026-08-14 two 18+
 * channels leaked into the main screen, and deleting them from the device wasn't enough, because
 * at the time that table synced and they'd already traveled to the cloud (cloud sync was removed
 * entirely in this branch's pruning, so that specific risk is gone -- but an adult-content row
 * would still show up in "Continue watching" and in the library of this SAME device, which is
 * reason enough to keep not writing it).
 *
 * Series don't play yet: the portal has to be asked for the chapters (`MagisCatalog.detail`) and
 * one has to be chosen. They're listed with their badge and don't accept the click.
 *
 * The roots (Películas, Series, Infantil, Anime and —if the device has the code— 18+) are tabs up
 * top: on a TV, width is the scarce resource, and a horizontal row is the remote's natural
 * gesture for "switch big section".
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvCatalogSections(
    /** Whether this device has the code set: adds the 18+ root at the end of the list. */
    includeAdults: Boolean = false,
    /** Play a movie. Series and anything with no `ref` don't get here. */
    onPlay: (CatalogItem) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    // The adults one goes LAST and only if the device is unlocked: it can't be in the way of
    // someone browsing the normal catalog.
    val roots = remember(includeAdults) {
        buildList {
            add("peliculas" to "Películas")
            add("series" to "Series")
            add("infantil" to "Infantil")
            add("anime" to "Anime")
            if (includeAdults) add("adultos" to "18+")
        }
    }
    var rootIdx by remember { mutableStateOf(0) }
    val root = roots[rootIdx].first
    var sections by remember { mutableStateOf<List<CatalogSection>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    BackHandler(onBack = onBack)

    LaunchedEffect(root, includeAdults) {
        sections = emptyList()
        loading = true
        runCatching { graph.liveCatalog.tree(root, includeAdults) }
            .onSuccess { sections = it; error = null }
            .onFailure { error = it.message ?: "No se pudo cargar" }
        loading = false
    }

    // ROW-EDGE SNAP, ported AS-IS from the home (see its `rowsListState`'s KDoc).
    //
    // Being identical depends on a structural condition: **one list item = one focusable row**.
    // The home meets it, and with two visible slots, rounding to the nearest boundary can't fail
    // — the focused row always lands in one of the two, wherever it falls.
    //
    // Here it was tried with the SECTION as the item (two rows inside) and it doesn't work: the
    // zone showed a single slot, so rounding could push the focused row right off screen —the
    // hero would be talking about one section while the rows showed another, with no card
    // marked— and anchoring it to the focused section covered that up but fought with focus's
    // `bringIntoView` when you were on the second row. That's why the list gets flattened to
    // rows: the condition holds again and this logic gets copied without touching anything.
    val rowsState = rememberLazyListState()
    LaunchedEffect(rowsState) {
        snapshotFlow { rowsState.isScrollInProgress }.collect { inMotion ->
            if (inMotion) return@collect
            val offset = rowsState.firstVisibleItemScrollOffset
            if (offset == 0) return@collect
            val height = rowsState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val target = rowsState.firstVisibleItemIndex + if (offset > height / 2) 1 else 0
            runCatching { rowsState.animateScrollToItem(target) }
        }
    }

    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { firstTabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // SAME STRUCTURE AS THE HOME, and for the same reason: the rows zone measures an EXACT number
    // of units and the hero keeps the rest (`weight(1f)`). Without that, the LazyColumn stretches
    // to the screen's edge and the last row enters halfway — half a cover clipped at the bottom,
    // which is exactly what was showing up.
    //
    // What changes is the UNIT. On the home it's one row (label + card); here a section is TWO
    // rows of cards under one label, so the unit is the whole section and one shows per screenful.
    // Two rows of covers stay visible, same as the home, and no section shows up cut in half.
    val cardHeight = 92.dp
    val rowGap = 14.dp
    val betweenRowsGap = 8.dp
    val rowsTopPad = 6.dp
    // +12: the row carries 6 dp of clearance top and bottom so the focus zoom doesn't clip.
    val rowHeight = cardHeight + 20.dp
    // The unit is ONE ROW, same as the home — not a section. It was tried with the whole section
    // as the unit and it came out worse: moving between a section's two rows leaves the scroll
    // mid-unit, and then it clips top AND bottom. With the row as the unit, every focus move
    // always lands on an edge.
    //
    // THE LazyColumn's ITEM IS A WHOLE SECTION, so the zone's interior has to measure EXACTLY
    // that -- not twice one row. The math has to come out of how the real item is built (two
    // rows, the gap between them, and the section's closing); when it didn't match by 6 dp, those
    // 6 dp were the bottom row's edge showing up clipped.
    //
    // The home does the same thing: `region = rowUnit * 2 + rowsTopPad` with
    // `padding(top = rowsTopPad)`, so the interior stays pinned to a whole multiple of its unit.
    // Unit = one row (card + its clearance + the gap separating it from the next), and the zone
    // measures TWO. Same as the home: `region = unit * 2 + rowsTopPad` with
    // `padding(top = rowsTopPad)`, so the interior stays pinned to two whole units.
    val unitHeight = rowHeight + betweenRowsGap
    val zoneHeight = unitHeight * 2 + rowsTopPad

    // The item that has focus RIGHT NOW. It's what makes a poster grid legible from three meters
    // away: a small card's full title doesn't fit, and without this there's no way to know where
    // you're standing without entering. Same role as the home's `featured`.
    var focused by remember { mutableStateOf<CatalogItem?>(null) }
    // Which section the focused item is from. Goes in the hero and not over each row: there it
    // split a same-section row pair in two, which is exactly what needs to be read together.
    var focusedSection by remember { mutableStateOf("") }
    // Transient notice for what can't be played. Clears itself: it's momentary information, and
    // leaving it fixed on screen would confuse it with the title that IS focused.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(3500)
        notice = null
    }
    // On switching roots, whatever was focused is no longer on screen: leaving it painted would
    // show the name of a movie from another tab.
    LaunchedEffect(root) { focused = null; focusedSection = "" }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        HeroBackground(imageUrl = focused?.poster)
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        val withItems = sections.filter { it.items.isNotEmpty() }

        // --- FIXED ZONE (doesn't scroll): header + hero. Keeps the leftover space ---
        Column(Modifier.fillMaxWidth().weight(1f)) {
        // Title and tabs on THE SAME line, tabs on the right. Used to be three stacked lines
        // (title, help, tabs) and that ate into the height where the hero now goes. The help line
        // ("volvé con Atrás") went away entirely: Back is the gesture that already works on every
        // screen in the app, and explaining it only here took up space to say nothing new.
        //
        // Tabs are ALWAYS painted, even if the chosen root is loading or fails: if the loading
        // state covered them, there'd be no way to pick a different one with the remote.
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Categorías",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            LazyRow(
                // Room for the zoom and the focus border: without this the focused tab gets
                // clipped against its own row's bounds.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(roots.size) { i ->
                    TvTab(
                        label = roots[i].second,
                        selected = i == rootIdx,
                        onClick = { rootIdx = i },
                        modifier = if (i == 0) Modifier.focusRequester(firstTabFocus) else Modifier,
                    )
                }
            }
        }

        if (withItems.isEmpty()) {
            Message(if (loading) "Cargando…" else (error ?: "Sin secciones"))
        } else {
            HeroText(focused, focusedSection, notice)
        }
        }

        if (withItems.isEmpty()) return@Column

        // The list gets flattened to ROWS, not sections: a LazyColumn item has to be a focusable
        // row for the home's snap to hold as-is (see its comment above). A section's two rows end
        // up as two consecutive items, and since the zone measures exactly two, the whole section
        // shows up aligned. The name no longer goes here —it lives in the hero—, so every item
        // measures the same, which is the other half of the condition.
        val rows = remember(withItems) {
            withItems.flatMap { s ->
                rowsOf(s.items).mapIndexed { i, f -> CatalogRow("${s.id}:$i", s.name, f) }
            }
        }

        // --- ROWS (the only zone that scrolls; fixed height = exactly TWO rows, like the home) ---
        // The VERTICAL pivot is the minimal-scroll one, not the 30% one: with rows this tall, 30%
        // of the container falls mid-row and leaves half a card clipped at the top. Same problem
        // (and same fix) already solved on the home.
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
            LazyColumn(
                state = rowsState,
                modifier = Modifier.fillMaxWidth().height(zoneHeight).padding(top = rowsTopPad),
            ) {
                items(rows, key = { it.key }) { row ->
                    Column {
                        ItemsRow(
                            items = row.items,
                            cardHeight = cardHeight,
                            onPlay = onPlay,
                            onFocus = { focused = it; focusedSection = row.section },
                            onNotice = { notice = it },
                        )
                        Spacer(Modifier.height(betweenRowsGap))
                    }
                }
            }
        }
        }
    }
}

/**
 * The section's name, above its row.
 *
 * Its own and not [TvRowLabel] (the home's) on purpose: here the section's name is what
 * ORIENTS -there are 30-odd sections per root, with long names from the portal-, while on the
 * home the rows are four or five fixed, known ones. It goes bigger and with more room against the
 * row; changing the shared one to get that would have also moved the home, which never asked for it.
 */
/** Immersive background for the focused item, with the same gradients as the home. */
@Composable
private fun HeroBackground(imageUrl: String?) {
    Crossfade(targetState = imageUrl, animationSpec = tween(450), label = "fondoCatalogo") { url ->
        Box(Modifier.fillMaxSize()) {
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.TopEnd),
                )
            }
            // Black on the left so the text reads over any poster.
            //
            // The stops are NOT the home's (spread evenly). Here the image starts at 38% of the
            // width, and with an even spread the gradient was already at 85% opacity right there:
            // a visible vertical seam showed up where the poster starts. Black stays solid past
            // that edge and only opens up after. It's not visible on the home because its
            // backdrops are scaled and drifting, which blurs the edge.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to ArkivBlack,
                        0.42f to ArkivBlack,
                        0.78f to ArkivBlack.copy(alpha = 0.15f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            // And black at the bottom, to blend into the rows.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.85f), ArkivBlack)),
                ),
            )
        }
    }
}

/**
 * Name and metadata for the focused item.
 *
 * It's what makes a poster row legible from three meters away: a small card's title gets clipped
 * to two lines, and without this there's no way to know where you're standing without entering.
 *
 * The metadata is what the portal GIVES today in the listing: type and duration. There's no
 * synopsis — its `assetList` doesn't bring one — so an empty line isn't invented: if there's
 * nothing to say, nothing is painted. The year exists on the portal's side but doesn't travel in
 * the tree yet.
 *
 * Fixed height even with nothing focused: if it appeared and disappeared, the rows below would
 * jump every time focus enters or leaves a card.
 */
@Composable
private fun HeroText(item: CatalogItem?, section: String, notice: String?) {
    Column(Modifier.fillMaxWidth(0.55f).height(96.dp).padding(start = 48.dp, bottom = 12.dp)) {
        // The notice COVERS the focused item for as long as it lasts: it's the response to
        // something the person just did, so it has to be where they're already looking. It takes
        // up the same fixed-height block, so nothing below moves when it appears or goes away.
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        if (item == null) return@Column
        if (section.isNotBlank()) {
            Text(
                section,
                style = MaterialTheme.typography.labelLarge,
                color = ArkivRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = metadataFor(item)
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.titleSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * How a section splits into rows: two contiguous halves if there's plenty, a single one if not.
 *
 * The threshold exists to avoid splitting just to split: with few titles, two short rows read as
 * two different, badly labeled sections. It's set at double what fits across a TV's width (about
 * 6-7 cards), so it only splits when there's genuinely more than what's visible.
 */
private fun rowsOf(items: List<CatalogItem>): List<List<CatalogItem>> =
    if (items.size < 2) listOf(items)
    else items.chunked((items.size + 1) / 2)

/** "Película · 1 h 52 min". What isn't known isn't painted: no "0 min" or loose separators. */
private fun metadataFor(item: CatalogItem): String = buildList {
    add(if (item.isSeries) "Serie" else "Película")
    if (item.durationS > 0) {
        val h = item.durationS / 3600
        val m = (item.durationS % 3600) / 60
        add(if (h > 0) "$h h $m min" else "$m min")
    }
    if (!item.playable) add("No disponible")
}.joinToString(" · ")

/**
 * A section as a horizontal row, with the same pivot as the home's: the row runs underneath a
 * card that stays still, instead of dragging it against the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemsRow(
    items: List<CatalogItem>,
    cardHeight: androidx.compose.ui.unit.Dp,
    onPlay: (CatalogItem) -> Unit,
    onFocus: (CatalogItem) -> Unit,
    onNotice: (String) -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
        LazyRow(
            // The `vertical` is NOT cosmetic: the focused card scales to 1.08 (TvLandscapeCard)
            // and a row of the exact height clips it against its own bounds — it eats the white
            // focus border, which is the only signal of where you're standing. The extra room the
            // scale needs is ~4% of 92 dp per side; 6 dp covers it with margin.
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                // What can't be played still shows up —the catalog looks complete— and says so on
                // the card. Series need a chapter chosen, which isn't here yet; with no `ref`
                // there's nothing to resolve (the gateway serves the catalog with no refs when it
                // has no signing key, on purpose, so it doesn't send a broken one).
                val badge = when {
                    item.isSeries -> "Serie"
                    !item.playable -> "No disponible"
                    else -> null
                }
                TvLandscapeCard(
                    title = item.title,
                    imageUrl = item.poster,
                    cardHeight = cardHeight,
                    badge = badge,
                    onFocus = { onFocus(item) },
                    // What can't be played NOTIFIES instead of staying silent. The badge on the
                    // card wasn't enough: on a TV, a button that accepts the click and does
                    // nothing reads as the app having hung -- and that's exactly what happened.
                    onClick = {
                        if (badge == null) onPlay(item)
                        else if (item.isSeries) onNotice("Las series todavía no se reproducen desde acá. Buscala por nombre.")
                        else onNotice("Este título no está disponible para reproducir.")
                    },
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
    }
}

/**
 * An already-flattened catalog row: the `LazyColumn`'s item.
 *
 * Carries its section's name because the hero needs it and, flattened, the row no longer knows
 * where it came from. [key] includes the row's index within the section: two rows from the same
 * section share the portal's id and without that the `LazyColumn` would see repeated keys.
 */
private data class CatalogRow(
    val key: String,
    val section: String,
    val items: List<CatalogItem>,
)
