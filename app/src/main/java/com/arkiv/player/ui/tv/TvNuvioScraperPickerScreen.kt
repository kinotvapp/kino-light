package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioScraperEntry
import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.ui.player.WAIT_BETWEEN_FOCUS_ATTEMPTS_MS
import com.arkiv.player.ui.player.retryFocus
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.NuvioCardAction
import com.arkiv.player.ui.plugin.NuvioPickerState
import com.arkiv.player.ui.plugin.NuvioTypeFilter
import com.arkiv.player.ui.plugin.cardInitial
import com.arkiv.player.ui.plugin.defaultNuvioLanguageFilter
import com.arkiv.player.ui.plugin.filterNuvioScrapers
import com.arkiv.player.ui.plugin.nuvioCardActionLabel
import com.arkiv.player.ui.plugin.nuvioCardActionOf
import com.arkiv.player.ui.plugin.nuvioInstalledScraperIds
import com.arkiv.player.ui.plugin.nuvioLanguageBuckets
import com.arkiv.player.ui.plugin.nuvioLanguageLabel
import com.arkiv.player.ui.plugin.nuvioPickerRepoName
import com.arkiv.player.ui.plugin.nuvioScraperTypeAndLanguageLine
import com.arkiv.player.ui.plugin.nuvioScraperVersionAuthorLine
import com.arkiv.player.ui.plugin.nuvioTypeFilterLabel
import com.arkiv.player.ui.plugin.onTileColor
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** List column's share of the width; the detail panel takes the rest. */
private const val LIST_WIDTH_FRACTION = 0.4f

/**
 * The full-screen Nuvio scraper picker on the TV: a list+detail layout (the product owner's call after the
 * first grid version read too cramped, with cards cut off and no room for names). Drawn as its own screen --
 * the SAME single outer margin every other TV content screen uses ([TvHomeScreen]'s 48/28 dp), never nested
 * inside the Plugins screen's own padded container (that double inset was the earlier bug): the caller
 * ([TvPluginsRoute], [TvSettingsScreen]) shows this INSTEAD of its normal padded chrome while the picker is open.
 *
 * Left: a compact, independently scrolling list, one line per scraper (small icon-less avatar, name, a
 * check mark once installed, dimmed when the manifest disabled it). Right: the detail of whichever row has
 * FOCUS (no OK needed to see it) -- name, its type/language line, description, version/author, and the one
 * action word: "Agregar" (focusable and red only when addable), "Instalado" or "No disponible" otherwise
 * (still a focus stop, never a destructiv[e] default: this app never gives a picker's default focus a
 * destructive action).
 *
 * D-pad: initial focus is the first row. Right from a row (or OK on it) reaches the detail's action;
 * Left, or Back while it has focus, returns to that same row -- Back does not leave the picker until focus
 * is back on the list (or nowhere in particular). Up from the first row reaches the type filter. The
 * language filter is a chip that opens a small dialog to pick a bucket; the search is [TvPluginSearch],
 * the same collapsed-button-that-expands piece the Recomendados tab already uses. [consentOpen] tracks the
 * consent sheet (and any Configurar right after a fresh install) so focus returns to the row that was
 * picked once it closes, keeping several installs from one repo just a Right+OK apart.
 */
@Composable
internal fun TvNuvioScraperPickerScreen(
    picker: NuvioPickerState,
    installed: List<InstalledPlugin>,
    busy: Boolean,
    message: String?,
    consentOpen: Boolean,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var typeFilter by rememberSaveable { mutableStateOf(NuvioTypeFilter.ALL) }
    var languageFilter by rememberSaveable(picker.repoInput) { mutableStateOf(defaultNuvioLanguageFilter(picker.scrapers)) }
    var query by rememberSaveable { mutableStateOf("") }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var lastPickedId by rememberSaveable { mutableStateOf<String?>(null) }

    val installedIds = remember(picker.repoInput, installed) { nuvioInstalledScraperIds(picker.repoInput, installed) }
    val languages = remember(picker.scrapers) { nuvioLanguageBuckets(picker.scrapers) }
    val shown = remember(picker.scrapers, typeFilter, languageFilter, query) {
        filterNuvioScrapers(picker.scrapers, typeFilter, languageFilter, query)
    }
    val shownIds = shown.map { it.id }

    var focusedId by rememberSaveable { mutableStateOf(shown.firstOrNull()?.id) }
    LaunchedEffect(shownIds) { if (focusedId !in shownIds) focusedId = shown.firstOrNull()?.id }
    val focusedScraper = shown.firstOrNull { it.id == focusedId }
    val focusedAction = focusedScraper?.let { nuvioCardActionOf(it, installedIds) }

    val rowFocus = remember(shownIds) { shown.associate { it.id to FocusRequester() } }
    val firstChipFocus = remember { FocusRequester() }
    val detailActionFocus = remember { FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    var initialPlaced by remember { mutableStateOf(false) }

    fun returnToList() {
        rowFocus[focusedId]?.let { runCatching { it.requestFocus() } }
    }
    // Left/Back from the detail's action returns to the row it belongs to; Back only leaves the picker
    // ([onBack], above) once focus is back on the list (or somewhere this doesn't intercept).
    BackHandler(enabled = detailFocused) { returnToList() }

    suspend fun focusRow(id: String?) {
        val target = id?.let { rowFocus[it] } ?: return
        retryFocus(isAlreadyFocused = { false }, wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) }, request = { runCatching { target.requestFocus() } })
    }

    LaunchedEffect(Unit) {
        focusRow(focusedId)
        initialPlaced = true
    }
    var wasConsentOpen by remember { mutableStateOf(false) }
    LaunchedEffect(consentOpen) {
        if (wasConsentOpen && !consentOpen && initialPlaced) {
            delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
            val target = lastPickedId?.takeIf { it in shownIds } ?: focusedId
            focusedId = target
            focusRow(target)
        }
        wasConsentOpen = consentOpen
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${nuvioPickerRepoName(picker.repoInput)} · ${picker.scrapers.size} fuentes",
                    style = MaterialTheme.typography.headlineSmall, color = Color.White,
                )
            }
            Text(
                "Código original de Nuvio con licencia GPL-3.0",
                style = MaterialTheme.typography.labelMedium, color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = ArkivRed)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp)) }

            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // TvTab's content fills whatever width it is offered (see its own KDoc/usage elsewhere):
                // inside a LazyRow each chip is measured to its own content, but a plain Row would hand the
                // FIRST one the whole remaining width and leave nothing for the rest -- the same reason
                // every other TvTab row in this app (Ajustes' own tabs, the Plugins header) is a LazyRow.
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(NuvioTypeFilter.entries.size) { index ->
                        val f = NuvioTypeFilter.entries[index]
                        TvTab(
                            label = nuvioTypeFilterLabel(f),
                            selected = f == typeFilter,
                            onClick = { typeFilter = f },
                            modifier = if (index == 0) Modifier.focusRequester(firstChipFocus) else Modifier,
                        )
                    }
                    if (languages.isNotEmpty()) {
                        item {
                            TvTab(
                                label = (languageFilter?.let(::nuvioLanguageLabel) ?: "Todas") + " ▾",
                                selected = languageFilter != null,
                                onClick = { languageMenuOpen = true },
                            )
                        }
                    }
                }
                TvPluginSearch(query = query, onQueryChange = { query = it }, upFocus = null, downTarget = focusedId?.let { rowFocus[it] })
            }

            Spacer(Modifier.height(16.dp))

            if (shown.isEmpty()) {
                Text("No hay scrapers que coincidan.", style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxHeight().weight(LIST_WIDTH_FRACTION),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(shown, key = { _, s -> s.id }) { index, scraper ->
                            val action = nuvioCardActionOf(scraper, installedIds)
                            NuvioListRow(
                                scraper = scraper,
                                action = action,
                                selected = scraper.id == focusedId,
                                modifier = Modifier
                                    .focusRequester(rowFocus.getValue(scraper.id))
                                    .onFocusChanged { if (it.hasFocus) focusedId = scraper.id }
                                    .then(if (index == 0) Modifier.focusProperties { up = firstChipFocus } else Modifier)
                                    .focusProperties { right = detailActionFocus },
                                onClick = { runCatching { detailActionFocus.requestFocus() } },
                            )
                        }
                    }
                    Column(Modifier.fillMaxHeight().weight(1f - LIST_WIDTH_FRACTION).padding(start = 28.dp)) {
                        NuvioDetailPanel(
                            scraper = focusedScraper,
                            action = focusedAction,
                            actionModifier = Modifier
                                .focusRequester(detailActionFocus)
                                .onFocusChanged { detailFocused = it.hasFocus }
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                                        returnToList()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            onAdd = {
                                focusedScraper?.let {
                                    lastPickedId = it.id
                                    onPick(it.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (languageMenuOpen) {
        NuvioLanguageMenu(
            options = languages,
            onSelect = { languageFilter = it; languageMenuOpen = false },
            onDismiss = { languageMenuOpen = false },
        )
    }
}

/** One row of the list column: a small icon-less avatar, the name, a check once installed. Dimmed when [action] is [NuvioCardAction.UNAVAILABLE]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioListRow(scraper: NuvioScraperEntry, action: NuvioCardAction, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ArkivSurfaceHigh else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp).alpha(if (action == NuvioCardAction.UNAVAILABLE) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val tile = PluginColors.DEFAULT
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(tile)), contentAlignment = Alignment.Center) {
                Text(cardInitial(scraper.name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(onTileColor(tile)))
            }
            Text(scraper.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (action == NuvioCardAction.INSTALLED) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}

/** Wraps [androidx.compose.material3.Icon] so this file doesn't need two Icon imports (tv/compose material3 share the name). */
@Composable
private fun Icon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?, tint: Color) {
    androidx.compose.material3.Icon(imageVector, contentDescription, tint = tint, modifier = Modifier.size(18.dp))
}

/**
 * The right panel: whichever row of the list has focus, or nothing when the filtered list is empty
 * (the caller already shows "No hay scrapers..." instead of the whole list+detail row then, so this is
 * only reached with a real [scraper]). The action area is ALWAYS one focusable stop
 * ([TvCompactAction] stays focusable while disabled, see its own KDoc) so Right from any row has one
 * predictable target: "Agregar" only when [action] is [NuvioCardAction.ADD], a quiet
 * "Instalado"/"No disponible" otherwise -- never a destructive action as this picker's default focus.
 */
@Composable
private fun NuvioDetailPanel(scraper: NuvioScraperEntry?, action: NuvioCardAction?, actionModifier: Modifier, onAdd: () -> Unit) {
    if (scraper == null || action == null) return
    Column(Modifier.fillMaxSize()) {
        Text(scraper.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        nuvioScraperTypeAndLanguageLine(scraper)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        scraper.description?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.padding(top = 16.dp))
        }
        nuvioScraperVersionAuthorLine(scraper)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        TvCompactAction(
            label = nuvioCardActionLabel(action),
            icon = if (action == NuvioCardAction.INSTALLED) Icons.Filled.Check else null,
            enabled = action == NuvioCardAction.ADD,
            modifier = actionModifier,
            onClick = onAdd,
        )
    }
}

/** The language filter's "small picker" ([TvNuvioScraperPickerScreen]'s KDoc): "Todas" plus every bucket present, a plain [Dialog] so Back and outside taps dismiss it like any other. */
@Composable
private fun NuvioLanguageMenu(options: List<String>, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    FocusWhenReady(firstFocus)
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), color = ArkivSurface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Idioma", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                TvActionOption(label = "Todas", modifier = Modifier.focusRequester(firstFocus), onClick = { onSelect(null) })
                options.forEach { bucket -> TvActionOption(label = nuvioLanguageLabel(bucket), onClick = { onSelect(bucket) }) }
            }
        }
    }
}
