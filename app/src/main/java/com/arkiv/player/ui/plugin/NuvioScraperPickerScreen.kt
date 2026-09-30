package com.arkiv.player.ui.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioScraperEntry
import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Side gutter of the picker's own header, filters and grid. */
private val PICKER_GUTTER = 16.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val PICKER_FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * The full-screen Nuvio scraper picker on the phone: replaces the old dialog (Task: "the product owner
 * wants to see the scrapers properly"). Header (repo name, how many scrapers, the GPL-3.0 line), the type
 * filter ([NuvioTypeFilter]), a language filter built from what the scrapers declare (only shown when there
 * is one to build), a search box by name, then one card per scraper in a two-column grid
 * ([NuvioScraperCard]). Picking "Agregar" runs [onPick] (the SAME conversion and consent sheet as before);
 * the caller ([PluginsContent]) keeps this screen up while that sheet is open, so after installing or
 * cancelling the person is back here with the filters and scroll exactly as they left them, the card they
 * used now reading "Instalado" ([NuvioCardAction.INSTALLED]). Back calls [onBack] -- leaves to the plugins
 * screen, nothing previewed.
 *
 * NO remote logos here (a scraper's manifest `logo` is a third-party URL -- imgur and the like -- outside
 * Kino's fixed hosts): every card draws [PluginCardSurface]'s icon-less initial instead, the same piece the
 * Recomendados/Instalados cards use for a plugin with no icon.
 */
@Composable
internal fun NuvioScraperPickerScreen(
    picker: NuvioPickerState,
    installed: List<InstalledPlugin>,
    busy: Boolean,
    message: String?,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // Keyed on the repo like the language filter: another repo opens on a clean search, not the last one's.
    var typeFilter by rememberSaveable(picker.repoInput) { mutableStateOf(NuvioTypeFilter.ALL) }
    var languageFilter by rememberSaveable(picker.repoInput) { mutableStateOf(defaultNuvioLanguageFilter(picker.scrapers)) }
    var query by rememberSaveable(picker.repoInput) { mutableStateOf("") }

    val installedIds = remember(picker.repoInput, installed) { nuvioInstalledScraperIds(picker.repoInput, installed) }
    val languages = remember(picker.scrapers) { nuvioLanguageBuckets(picker.scrapers) }
    val shown = remember(picker.scrapers, typeFilter, languageFilter, query) {
        filterNuvioScrapers(picker.scrapers, typeFilter, languageFilter, query)
    }
    val gridState = rememberLazyGridState()

    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PICKER_GUTTER, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(nuvioPickerRepoName(picker.repoInput), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(nuvioPickerHeaderLine(picker.scrapers.size), style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 4.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(PHONE_CATALOG_COLUMNS),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = gridState,
            contentPadding = PaddingValues(start = PICKER_GUTTER, end = PICKER_GUTTER, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "type-filter", span = PICKER_FULL_WIDTH) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(NuvioTypeFilter.entries) { f ->
                        NuvioFilterChip(label = nuvioTypeFilterLabel(f), selected = f == typeFilter, onClick = { typeFilter = f })
                    }
                }
            }
            if (languages.isNotEmpty()) {
                item(key = "language-filter", span = PICKER_FULL_WIDTH) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { NuvioFilterChip(label = "Todas", selected = languageFilter == null, onClick = { languageFilter = null }) }
                        items(languages) { bucket ->
                            NuvioFilterChip(label = nuvioLanguageLabel(bucket), selected = languageFilter == bucket, onClick = { languageFilter = bucket })
                        }
                    }
                }
            }
            item(key = "search", span = PICKER_FULL_WIDTH) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar por nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (shown.isEmpty()) {
                item(key = "no-match", span = PICKER_FULL_WIDTH) {
                    Text("No hay scrapers que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                }
            }
            itemsIndexed(shown, key = { _, s -> s.id }) { _, scraper ->
                NuvioScraperCard(
                    scraper = scraper,
                    action = nuvioCardActionOf(scraper, installedIds),
                    enabled = !busy,
                    onAdd = { onPick(scraper.id) },
                )
            }
        }
    }
}

/** One filter chip, styled like [com.arkiv.player.ui.live.LiveScreen]'s own (the brand red when selected). */
@Composable
private fun NuvioFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = ArkivSurface,
            labelColor = ArkivTextSecondary,
            selectedContainerColor = ArkivRed,
            selectedLabelColor = Color.White,
        ),
    )
}

/**
 * One scraper as the picker's grid shows it, on the same [PluginCardSurface] as the Recomendados/Instalados
 * cards, always with its icon-less initial (no remote logos, see
 * [NuvioScraperPickerScreen]'s KDoc), its name, description, type chips ([nuvioScraperTypeChips]), its
 * language/version/author line ([nuvioScraperMetaLine]), and one button
 * ([NuvioCardAction]): "Agregar" (calls [onAdd]), a disabled-looking "Instalado", or a disabled-looking
 * "No disponible" for a scraper the manifest disabled (dimmed the whole card too, so it reads as inert at a
 * glance, not just on its button).
 */
@Composable
private fun NuvioScraperCard(scraper: NuvioScraperEntry, action: NuvioCardAction, enabled: Boolean, onAdd: () -> Unit) {
    val dimmed = action == NuvioCardAction.UNAVAILABLE
    Box(Modifier.fillMaxWidth().then(if (dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier)) {
        PluginCardSurface(name = scraper.name, iconFile = null, tileColorArgb = PluginColors.DEFAULT, pill = null) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(scraper.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                scraper.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val chips = nuvioScraperTypeChips(scraper)
                if (chips.isNotEmpty()) Text(chips.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                nuvioScraperMetaLine(scraper)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onAdd,
                    enabled = enabled && action == NuvioCardAction.ADD,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArkivRed.copy(alpha = 0.30f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.08f),
                        disabledContentColor = ArkivTextSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(nuvioCardActionLabel(action)) }
            }
        }
    }
}

private const val DIMMED_ALPHA = 0.5f
