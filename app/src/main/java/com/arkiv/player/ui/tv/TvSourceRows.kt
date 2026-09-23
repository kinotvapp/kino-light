package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.search.SourceTab
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The TV search's results, as one horizontal row per source.
 *
 * It used to be a single vertical list: with 536 torrents and 20 from magis in the same column,
 * magis got buried with no way to reach it with the remote. With one row per source they're all
 * visible at a glance, and the D-pad does the natural thing -- right goes through one source,
 * down jumps to the next. It's also the language the TV Home already uses.
 */

/** Height of Magis and Caracol's covers. */
private val CARD_HEIGHT = 220.dp

/** The Home rows' ([TvHomeScreen]) side margin, so the two screens line up. */
private val MARGIN = 48.dp

/**
 * A labeled row with ONE source's results.
 *
 * Magis and Caracol bring their own image, so both go with a cover ([TvPosterCard]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun LazyListScope.tvSourceRow(
    source: SourceTab,
    items: List<PlaySource>,
    enabled: Boolean,
    loading: Boolean,
    /** Only the FIRST row receives it: the initial focus goes to its first card, not each row's. */
    firstCard: FocusRequester?,
    onPlay: (PlaySource) -> Unit,
) {
    item(key = "fila-${source.name}") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(start = MARGIN, top = 20.dp, bottom = 8.dp),
        ) {
            Text(source.label, style = MaterialTheme.typography.titleSmall, color = ArkivTextPrimary)
            Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            if (loading) {
                Text("buscando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            }
        }
    }
    item(key = "row-${source.name}") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = MARGIN),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { sourceKey(it) }) { s ->
                // Focus starts on the first row's first card. `===` and not `==`: two results
                // can be equal by value and we don't want to request focus twice.
                val mod = if (firstCard != null && s === items.first()) {
                    Modifier.focusRequester(firstCard)
                } else {
                    Modifier
                }
                // Both sources here bring a cover, so both go as a poster.
                val (title, poster) = when (s) {
                    is PlaySource.Magis -> s.result.title to s.result.extra["poster"]
                    is PlaySource.Ditu -> s.result.title to s.result.extra["poster"]
                }
                TvPosterCard(
                    title = title,
                    posterUrl = poster,
                    cardHeight = CARD_HEIGHT,
                    modifier = mod,
                ) { onPlay(s) }
            }
        }
    }
}
