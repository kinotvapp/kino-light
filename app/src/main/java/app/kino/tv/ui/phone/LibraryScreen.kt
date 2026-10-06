package app.kino.tv.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.Film
import app.kino.tv.data.allFilms
import app.kino.tv.ui.components.ContinueCard
import app.kino.tv.ui.components.KinoChip
import app.kino.tv.ui.components.PosterCard
import app.kino.tv.ui.components.SectionHeader
import app.kino.tv.ui.isLandscapeTablet

private enum class LibFilter(val label: String) { ALL("Todas"), MOVIES("Películas"), SHORTS("Cortos") }

/** A short is under 40 minutes; the catalog has no series, so the chips filter by length. */
private fun Film.isShort() = durationMin in 1 until 40

/**
 * "Biblioteca" on the phone, with example content: "Continuar viendo" with a progress bar and
 * "Mi biblioteca" as a poster grid. In the full app this is what you saved and watched.
 */
@Composable
fun LibraryScreen(rows: List<CatalogRow>, contentPadding: PaddingValues, onOpenFilm: (Film) -> Unit, onPlayFilm: (Film) -> Unit) {
    val films = remember(rows) { allFilms(rows) }
    val continueWatching = remember(films) { films.drop(2).take(2) }
    var filter by remember { mutableStateOf(LibFilter.ALL) }
    val filtered = when (filter) {
        LibFilter.ALL -> films
        LibFilter.MOVIES -> films.filter { !it.isShort() }
        LibFilter.SHORTS -> films.filter { it.isShort() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isLandscapeTablet()) 6 else 3),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Continuar viendo") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(continueWatching, key = { it.id }) { film ->
                    ContinueCard(
                        title = film.title,
                        subtitle = film.year.toString(),
                        imageUrl = film.posterUrl,
                        progress = 0.35f,
                        modifier = Modifier.width(240.dp),
                        onClick = { onPlayFilm(film) },
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Mi biblioteca") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibFilter.entries.forEach { f -> KinoChip(f.label, filter == f) { filter = f } }
            }
        }
        items(filtered, key = { it.id }) { film ->
            PosterCard(title = film.title, imageUrl = film.posterUrl, onClick = { onOpenFilm(film) })
        }
    }
}
