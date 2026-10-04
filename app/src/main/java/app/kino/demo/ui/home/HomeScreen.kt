package app.kino.demo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.Film
import app.kino.demo.data.metaLine
import app.kino.demo.ui.components.PosterCard
import app.kino.demo.ui.isLandscapeTablet
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/** Home sizes based on the screen's shape. */
private data class HomeSizes(val heroHeight: Dp, val posterWidth: Dp)

@Composable
private fun homeSizes(): HomeSizes =
    if (isLandscapeTablet()) HomeSizes(heroHeight = 420.dp, posterWidth = 180.dp)
    else HomeSizes(heroHeight = 220.dp, posterWidth = 120.dp)

/** The phone's Home: a featured hero, then one row of posters per catalog row. */
@Composable
fun HomeScreen(
    rows: List<CatalogRow>,
    contentPadding: PaddingValues,
    onOpenFilm: (Film) -> Unit,
    onPlayFilm: (Film) -> Unit,
) {
    val sizes = homeSizes()
    val hero = rows.firstOrNull()?.films?.firstOrNull()
    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (hero != null) {
            item(key = "hero") {
                Hero(
                    sizes = sizes,
                    backdropUrl = hero.posterUrl,
                    title = hero.title,
                    subtitle = hero.metaLine(),
                    actionLabel = "Reproducir",
                    onAction = { onPlayFilm(hero) },
                    onClick = { onOpenFilm(hero) },
                )
            }
        }
        items(rows, key = { it.id }) { row ->
            FilmRow(row = row, posterWidth = sizes.posterWidth, onOpen = onOpenFilm)
        }
        item(key = "demo-note") {
            Text(
                DEMO_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = KinoTextSecondary,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp),
            )
        }
    }
}

/** The short footer line that says what this app is. */
internal const val DEMO_NOTE =
    "Esto es una demo con películas de dominio público del Internet Archive. " +
        "Kino completo se descarga en archive.org/details/kino-app."

/** Full-width feature: backdrop, bottom gradient, title/subtitle and an optional action. */
@Composable
private fun Hero(
    sizes: HomeSizes,
    backdropUrl: String?,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(sizes.heroHeight)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, KinoBlack))),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = KinoTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = KinoRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

/** A Home row: its title and a horizontal line of poster cards. */
@Composable
private fun FilmRow(row: CatalogRow, posterWidth: Dp, onOpen: (Film) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.films, key = { "${row.id}-${it.id}" }) { film ->
                PosterCard(
                    title = film.title,
                    imageUrl = film.posterUrl,
                    modifier = Modifier.width(posterWidth),
                    onClick = { onOpen(film) },
                )
            }
        }
    }
}
