package com.arkiv.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The phone's REFINE step: before the source search, pick "Toda la serie", or a season and one of its chapters (an anime:
 * one of its episodes). The same choice the TV offers, tapped instead of typed. What it lists comes from [data]
 * ([rememberRefineData]); a title whose seasons cannot be loaded still has "Toda la serie".
 */
@Composable
internal fun RefineContent(card: TitleCard, data: RefineData, onContinue: (season: Int?, episode: Int?) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                Box(
                    modifier = Modifier.height(140.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                ) {
                    AsyncImage(
                        model = card.posterUrl,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.height(140.dp), contentAlignment = Alignment.CenterStart) {
                    androidx.compose.foundation.layout.Column {
                        Text(card.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        if (card.year.isNotBlank()) {
                            Text(card.year, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                        }
                    }
                }
            }
        }
        item(key = "all") {
            Button(
                onClick = { onContinue(null, null) },
                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Toda la serie") }
        }
        when (card.kind) {
            "series" -> seriesItems(data, onContinue)
            "anime" -> animeItems(data, onContinue)
            else -> Unit // A movie never reaches REFINE: pickTitle() sends it straight to RESULTS.
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.seriesItems(
    data: RefineData,
    onContinue: (season: Int?, episode: Int?) -> Unit,
) {
    if (data.seasons.isEmpty()) {
        item(key = "no-seasons") {
            RefineNote(if (!data.detailLoaded) "Cargando temporadas…" else "Sin temporadas disponibles: usa \"Toda la serie\".")
        }
        return
    }
    item(key = "seasons-label") { RefineLabel("Temporadas") }
    item(key = "seasons") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data.seasons, key = { it.seasonNumber }) { season ->
                FilterChip(
                    selected = season.seasonNumber == data.selectedSeason,
                    onClick = { data.selectSeason(season.seasonNumber) },
                    label = { Text(refineSeasonLabel(season.seasonNumber)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ArkivRed,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }
    }
    item(key = "episodes-label") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefineLabel("Capítulos")
            if (data.loadingEpisodes) {
                Spacer(Modifier.width(8.dp))
                Text("Cargando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            }
        }
    }
    items(data.currentEpisodes, key = { "ep-${it.season}-${it.episode}" }) { episode ->
        RefineRow(refineEpisodeLabel(episode)) { onContinue(data.selectedSeason, episode.episode) }
    }
    if (data.currentEpisodes.isEmpty() && !data.loadingEpisodes) {
        item(key = "no-episodes") { RefineNote("No hay capítulos para esta temporada.") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.animeItems(
    data: RefineData,
    onContinue: (season: Int?, episode: Int?) -> Unit,
) {
    if (data.animeTotal <= 0) {
        item(key = "no-episodes") {
            RefineNote(if (!data.animeLoaded) "Cargando episodios…" else "Cantidad de episodios desconocida: usa \"Toda la serie\".")
        }
        return
    }
    item(key = "episodes-label") { RefineLabel("Episodios") }
    items((1..data.animeTotal).toList(), key = { "ep-$it" }) { n ->
        RefineRow("Episodio $n") { onContinue(null, n) }
    }
}

@Composable
private fun RefineLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RefineNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary, modifier = Modifier.padding(top = 8.dp))
}

/** A tappable chapter or episode. */
@Composable
private fun RefineRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh)
            .clickable(onClick = onClick).padding(16.dp),
    )
}
