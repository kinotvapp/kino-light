package app.kino.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.Film
import app.kino.tv.data.allFilms
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoTextSecondary

private enum class LibrarySection(val label: String) { SAVED("Mi lista"), WATCHED("Ya visto"), DOWNLOADS("Descargas") }

/**
 * "Mi biblioteca" on the TV, with example content: section tabs (Mi lista, Ya visto, Descargas),
 * "Continuar viendo" with progress, and the section's cards.
 */
@Composable
fun TvLibraryScreen(rows: List<CatalogRow>, onOpenFilm: (Film) -> Unit) {
    val films = remember(rows) { allFilms(rows) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.SAVED) }
    val landing = rememberLandingFocus()
    val shown = when (section) {
        LibrarySection.SAVED -> films.take(8)
        LibrarySection.WATCHED -> films.drop(8).take(4)
        LibrarySection.DOWNLOADS -> films.takeLast(3)
    }
    Column(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mi biblioteca", style = MaterialTheme.typography.titleLarge, color = Color.White)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(LibrarySection.entries.toList()) { s ->
                    TvTab(s.label, s == section, onClick = { section = s }, modifier = if (s == LibrarySection.SAVED) Modifier.landingFocus(landing) else Modifier)
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (section == LibrarySection.SAVED) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Continuar viendo", style = MaterialTheme.typography.titleSmall, color = KinoTextSecondary)
                }
                items(films.drop(2).take(2), key = { "continue-${it.id}" }) { film -> LibraryCard(film, progress = 0.35f, status = null) { onOpenFilm(film) } }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Guardado", style = MaterialTheme.typography.titleSmall, color = KinoTextSecondary)
                }
            }
            items(shown, key = { "${section.name}-${it.id}" }) { film ->
                val status = when {
                    section == LibrarySection.DOWNLOADS && film == shown.last() -> "Bajando 42%"
                    section == LibrarySection.DOWNLOADS -> "Listo"
                    section == LibrarySection.WATCHED -> "Visto"
                    else -> null
                }
                LibraryCard(film, progress = if (section == LibrarySection.WATCHED) 1f else 0f, status = status) { onOpenFilm(film) }
            }
        }
    }
}

@Composable
private fun LibraryCard(film: Film, progress: Float, status: String?, onClick: () -> Unit) {
    Column {
        Box {
            TvLandscapeCard(title = film.title, imageUrl = film.posterUrl, cardHeight = 112.dp, onClick = onClick)
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(KinoRed))
                }
            }
        }
        Text(film.title, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = KinoTextSecondary)
    }
}
