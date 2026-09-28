package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.gridLinesWithStatus

/** The section title and the card pill of plugins found through the `kino-plugin` topic. */
internal const val COMMUNITY_TITLE = "De la comunidad"

/** The section's header action and its one line (ruling R16: a neutral line, never an error). */
data class CommunityHeader(val actionLabel: String, val actionEnabled: Boolean, val line: String?)

fun communityHeader(state: CommunityUiState): CommunityHeader = CommunityHeader(
    actionLabel = if (state.refreshing) "Buscando…" else "Actualizar",
    actionEnabled = !state.refreshing,
    line = when {
        state.loading -> "Buscando plugins de la comunidad…"
        state.rows.isEmpty() -> "Por ahora no hay plugins de la comunidad para mostrar."
        else -> null
    },
)

/** A community card's grid key: never equal to a catalog card's `card-<id>`, even for the same id. */
internal fun communityCardKey(row: CatalogRow): String = "community-${row.entry.repo.lowercase()}"

/**
 * "De la comunidad" as items of the phone's card grid, after the catalog cards: a full-width header with
 * "Actualizar", its line while it has one, and one [PluginCard] per row.
 */
internal fun LazyGridScope.communityItems(
    community: CommunityUiState,
    art: Map<String, CatalogArt>,
    busy: Boolean,
    columns: Int,
    onRefresh: () -> Unit,
    onAction: (CatalogRow) -> Unit,
) {
    val header = communityHeader(community)
    item(key = "community-header", span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(COMMUNITY_TITLE, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = header.actionEnabled) {
                Text(header.actionLabel, color = if (header.actionEnabled) ArkivRed else ArkivTextSecondary)
            }
        }
    }
    header.line?.let { line ->
        item(key = "community-line", span = { GridItemSpan(maxLineSpan) }) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
    }
    val statusLines = gridLinesWithStatus(community.rows, columns)
    itemsIndexed(community.rows, key = { _, row -> communityCardKey(row) }) { index, row ->
        PluginCard(
            row = row,
            art = art[row.entry.repo],
            reserveStatusLine = statusLines.getOrElse(index) { false },
            enabled = !busy,
            onAction = { onAction(row) },
        )
    }
}
