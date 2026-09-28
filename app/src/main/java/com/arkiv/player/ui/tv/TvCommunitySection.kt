package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.plugin.COMMUNITY_TITLE
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CommunityUiState
import com.arkiv.player.ui.plugin.communityCardKey
import com.arkiv.player.ui.plugin.communityHeader
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * "De la comunidad" as items of a TV card grid, after the catalog cards. "Actualizar" is a
 * [TvCompactAction]: while a search runs it reads "Buscando…" and ignores OK but stays focusable, so
 * focus is never thrown out from under the person. Nothing lies to the right of the header or of a
 * line's last card ([cardHasNothingToTheRight]).
 *
 * [focus], when given, routes Up/Down through "Actualizar" (a stop keyed [COMMUNITY_HEADER_KEY]) and the
 * cards (keyed [communityCardKey]) explicitly ([TvGridFocus.stop], with [leaveDown] below the last line);
 * [compact] draws compact cards ([TvPluginCard]).
 */
/** Grid item key of the "De la comunidad" header, which holds its one focusable, "Actualizar". */
internal const val COMMUNITY_HEADER_KEY = "community-header"

/** The D-pad blocks of "De la comunidad" ([GridBlock]): "Actualizar" on a line of its own, then the cards. */
internal fun communityFocusBlocks(community: CommunityUiState): List<GridBlock> =
    listOf(GridBlock.Action(COMMUNITY_HEADER_KEY), GridBlock.Cards(community.rows.map(::communityCardKey)))

internal fun LazyGridScope.tvCommunityItems(
    community: CommunityUiState,
    art: Map<String, CatalogArt>,
    columns: Int,
    onRefresh: () -> Unit,
    onAction: (CatalogRow) -> Unit,
    focus: TvGridFocus? = null,
    leaveDown: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val header = communityHeader(community)
    item(key = COMMUNITY_HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp).noFocusToTheRight(),
        ) {
            Text(COMMUNITY_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
            TvCompactAction(
                label = header.actionLabel,
                enabled = header.actionEnabled,
                modifier = focus?.stop(COMMUNITY_HEADER_KEY, leaveDown = leaveDown) ?: Modifier,
                onClick = onRefresh,
            )
        }
    }
    header.line?.let { line ->
        item(key = "community-line", span = { GridItemSpan(maxLineSpan) }) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
    }
    val statusLines = gridLinesWithStatus(community.rows, columns)
    itemsIndexed(community.rows, key = { _, row -> communityCardKey(row) }) { index, row ->
        TvPluginCard(
            row = row,
            art = art[row.entry.repo],
            modifier = (focus?.stop(communityCardKey(row), leaveDown = leaveDown) ?: Modifier)
                .then(if (cardHasNothingToTheRight(index, community.rows.lastIndex, columns)) Modifier.noFocusToTheRight() else Modifier),
            reserveStatusLine = statusLines.getOrElse(index) { false },
            compact = compact,
            onClick = { onAction(row) },
        )
    }
}
