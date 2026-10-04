package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.Film
import app.kino.demo.data.searchFilms
import app.kino.demo.data.metaLine
import app.kino.demo.ui.components.PosterCard
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary

/** "Buscar" on the phone. In the demo it filters the bundled catalog on the device, as you type. */
@Composable
fun SearchScreen(rows: List<CatalogRow>, onBack: () -> Unit, onOpenFilm: (Film) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val results = remember(rows, text) { searchFilms(rows, text) }
    Column(Modifier.fillMaxSize().background(KinoBlack).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Text("Buscar", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Buscar…") },
                    singleLine = true,
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton(onClick = { text = "" }) { Icon(Icons.Default.Close, contentDescription = "Limpiar") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            if (results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Sin resultados", color = KinoTextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(results, key = { it.id }) { film ->
                PosterCard(title = film.title, imageUrl = film.posterUrl, meta = film.metaLine(), onClick = { onOpenFilm(film) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "En la app completa la búsqueda también pregunta a tus plugins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KinoTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
