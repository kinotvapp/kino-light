package com.arkiv.player.ui.tv.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.library.LibraryFilter
import com.arkiv.player.data.library.LibrarySection
import com.arkiv.player.data.library.LibraryWatched
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.tv.arkivTvButtonBorder
import com.arkiv.player.ui.tv.arkivTvButtonColors
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.TvPosterCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CARD_HEIGHT = 200.dp

/**
 * Clearance against the screen's edges, shared by every section.
 *
 * It's not a style choice: a TV clips the image's edge (overscan) and how much it clips depends
 * on the device, so anything within ~5% of the edge may not be visible. With the menu's old
 * 24 dp, the text sat right against the edge. These values keep content inside the safe zone and
 * read better from a distance as a side effect.
 */
internal val SAFE_H = 44.dp
internal val SAFE_V = 44.dp

/**
 * TV's "My library": what's saved, what's already watched, and downloads to the device.
 *
 * Exists because the home wasn't enough: its rows zone measures exactly two rows, so with
 * something in "Continue watching" the Movies row was born off-screen with no reasonable way to
 * reach what's saved. Here, owned content has its own place and doesn't compete with ~40
 * discovery rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLibraryScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val vm: TvLibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { TvLibraryViewModel(graph.repository) } },
    )
    val groups by vm.groups.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf(LibrarySection.ALL_SAVED) }
    var menuFor by remember { mutableStateOf<LibraryGroup?>(null) }

    BackHandler(enabled = menuFor == null) { onBack() }

    // Focus starts on the menu. Same retry pattern as the home: at 150 ms the row may not be
    // composed yet and `requestFocus()` throws "FocusRequester is not initialized"; without
    // retrying, focus lands nowhere and Android hands it to whatever gets composed next.
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { menuFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    // Movie: plays directly. Series: opens the detail, which is where a chapter gets picked.
    // Navigates with the GROUP'S KEY (`tv:46260`), not the main source's identifier:
    // `DetailViewModel.observeGroupMembers` resolves it to every acquisition and builds the selector.
    fun open(group: LibraryGroup) {
        if (group.primary.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(group.primary.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(group.key)
            }
        } else {
            onOpenItem(group.key)
        }
    }

    Row(Modifier.fillMaxSize().background(ArkivBlack)) {
        // --- Side menu ---
        Column(
            modifier = Modifier.width(260.dp).fillMaxHeight()
                .background(ArkivSurface)
                .padding(vertical = SAFE_V),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "KINO",
                style = MaterialTheme.typography.titleLarge,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = SAFE_H, bottom = 28.dp),
            )
            LibrarySection.entries.forEachIndexed { i, s ->
                TvMenuItem(
                    label = s.label,
                    selected = s == section,
                    modifier = if (i == 0) Modifier.focusRequester(menuFocus) else Modifier,
                    // The section changes with FOCUS, not with a click: it's what's expected in
                    // a TV menu (going down the menu shows each section as you go), and it avoids
                    // the extra step of "focus, confirm, only then see".
                    onFocus = { section = s },
                )
            }
        }

        // --- Content ---
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                LibrarySection.DOWNLOADS -> TvDownloadsSection(onPlayEpisode = onPlayEpisode)
                LibrarySection.WATCHED -> TvPosterGrid(
                    title = "Ya visto",
                    count = watched.size,
                    groups = watched.map { it.group },
                    subtitleFor = { g ->
                        watched.firstOrNull { it.group.key == g.key }
                            ?.let { LibraryWatched.watchedLabel(it.episodesWatched) }
                    },
                    empty = "Todavía no terminaste nada.\nLo que veas hasta el final va a aparecer acá.",
                    onClick = ::open,
                    onLongClick = { menuFor = it },
                )
                else -> {
                    // `groups(...)` returns null only for WATCHED/DOWNLOADS, already handled
                    // above: here it's never null.
                    val filtered = LibraryFilter.groups(section, groups).orEmpty()
                    TvPosterGrid(
                        title = section.label,
                        count = filtered.size,
                        groups = filtered,
                        subtitleFor = { g -> seriesSubtitle(g) },
                        empty = "Todavía no guardaste nada acá.\nBusca algo y dale Guardar.",
                        onClick = ::open,
                        onLongClick = { menuFor = it },
                    )
                }
            }
        }
    }

    menuFor?.let { group ->
        TvLibraryItemDialog(
            group = group,
            onOpenDetail = { onOpenItem(group.key); menuFor = null },
            onSetCategory = { isMovie -> vm.setCategory(group.primary.identifier, isMovie); menuFor = null },
            onRemove = { vm.removeGroup(group); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

/** A side menu entry. Painted as selected when its section is the active one. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMenuItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = ArkivRed,
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) ArkivTextPrimary else ArkivTextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SAFE_H, top = 14.dp, end = 20.dp, bottom = 14.dp),
        )
    }
}

/**
 * "24 ep." or "24 ep.  ·  +3 nuevos" if there are new chapters since the last time the detail was
 * opened. Null for movies, which have no chapters.
 *
 * [TvPosterCard]'s `subtitle` is reused instead of a badge over the cover (like the "+N" in
 * [com.arkiv.player.ui.tv.TvLandscapeCard] on the home) because this grid is the only remaining
 * consumer of `nuevos` after the home's Series row got removed (commit f1a9dbbd): adding a second
 * place to paint a badge —with its own spot on the cover and its own color strip— is more
 * surface for a single screen, when the subtitle already exists and has plenty of room.
 */
private fun seriesSubtitle(group: LibraryGroup): String? {
    if (group.primary.isMovie) return null
    val base = "${group.episodeCount} ep."
    return if (group.newEpisodes > 0) "$base  ·  +${group.newEpisodes} nuevos" else base
}

/**
 * Cover grid. `Adaptive` and not a fixed column count: with the 220 dp menu, a 1080p Fire TV fits
 * ~4 poster columns, and a wider screen fits more on its own.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPosterGrid(
    title: String,
    count: Int,
    groups: List<LibraryGroup>,
    subtitleFor: (LibraryGroup) -> String?,
    empty: String,
    onClick: (LibraryGroup) -> Unit,
    onLongClick: (LibraryGroup) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V)) {
        Text(
            if (groups.isEmpty()) title else "$title  ·  $count",
            style = MaterialTheme.typography.headlineSmall,
            color = ArkivTextPrimary,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        if (groups.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(groups, key = { it.key }) { group ->
                TvPosterCard(
                    title = group.primary.title,
                    posterUrl = group.primary.thumbnailUrl,
                    cardHeight = CARD_HEIGHT,
                    subtitle = subtitleFor(group),
                    onLongClick = { onLongClick(group) },
                    onClick = { onClick(group) },
                )
            }
        }
    }
}

/**
 * A card's long-press menu. It's the `TvCategoryDialog` that lived in `TvHomeScreen` plus "Quitar
 * de mi biblioteca", which didn't exist on the TV before: until now, an accidental save could
 * only be undone from the phone.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLibraryItemDialog(
    group: LibraryGroup,
    onOpenDetail: () -> Unit,
    onSetCategory: (Boolean?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val row = group.primary
    var confirmRemove by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Same retry pattern as the side menu: a single attempt with a swallowed `runCatching` caused
    // the historical bug where, if the dialog wasn't composed yet, `requestFocus()` threw
    // "FocusRequester is not initialized" and focus ended up with no owner.
    LaunchedEffect(confirmRemove) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { focus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (confirmRemove) {
                Text(
                    "Se quita de tu biblioteca en todos tus aparatos. Si tenías capítulos descargados en este aparato y quieres liberar espacio, bórralos desde Descargas ANTES de confirmar: una vez que la quitas de acá, esos archivos quedan en el aparato pero ya no vas a poder borrarlos desde la app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onRemove, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Sí, quitar de mi biblioteca", maxLines = 1)
                }
                // Focus lands here and NOT on the button above: with the remote it's normal for a
                // double OK to reach the UI a frame after what the user sees, and if focus started
                // on the destructive button that double OK would fire it before anyone can read
                // the warning.
                Button(onClick = { confirmRemove = false }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Cancelar", maxLines = 1)
                }
            } else {
                Text(
                    if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onOpenDetail, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Ver detalle / descargar", maxLines = 1)
                }
                if (row.isMovie) {
                    Button(onClick = { onSetCategory(false) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como serie", maxLines = 1)
                    }
                } else {
                    Button(onClick = { onSetCategory(true) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como película", maxLines = 1)
                    }
                }
                if (row.categoryOverride != null) {
                    Button(onClick = { onSetCategory(null) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Detección automática", maxLines = 1)
                    }
                }
                Button(onClick = { confirmRemove = true }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Quitar de mi biblioteca", maxLines = 1)
                }
                Button(onClick = onDismiss, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Volver", maxLines = 1)
                }
            }
        }
    }
}
