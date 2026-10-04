package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.Film
import app.kino.demo.data.metaLine
import app.kino.demo.ui.components.PosterCard
import app.kino.demo.ui.isLandscapeTablet
import coil.compose.AsyncImage

private val CARD_HEIGHT = 110.dp
private val CARD_RADIUS = RoundedCornerShape(10.dp)

/**
 * "Categorías" on the phone: a search box and one tile per catalog row; a tile opens its films as a
 * grid. In the demo the tiles are the rows of the bundled catalog.
 */
@Composable
fun CategoriesScreen(rows: List<CatalogRow>, contentPadding: PaddingValues, onOpenFilm: (Film) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var openRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val openRow = rows.firstOrNull { it.id == openRowId }
    val columns = if (isLandscapeTablet()) 4 else 2
    val padding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = contentPadding.calculateTopPadding() + 8.dp,
        bottom = contentPadding.calculateBottomPadding() + 16.dp,
    )

    if (openRow != null) {
        androidx.activity.compose.BackHandler { openRowId = null }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns + 1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { openRowId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                    Text(openRow.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
            }
            items(openRow.films, key = { it.id }) { film ->
                PosterCard(title = film.title, imageUrl = film.posterUrl, meta = film.metaLine(), onClick = { onOpenFilm(film) })
            }
        }
        return
    }

    val shown = rows.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Categorías", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar categoría…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Limpiar") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Destacadas") }
        items(shown, key = { it.id }) { row ->
            CategoryCard(title = row.title, imageUrl = row.films.firstOrNull()?.posterUrl, onClick = { openRowId = row.id })
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Text("Sin resultados para \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** A category: its first film's art, and its name under it. */
@Composable
private fun CategoryCard(title: String, imageUrl: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CARD_RADIUS).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CARD_HEIGHT)
                .clip(CARD_RADIUS)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 4.dp),
        )
    }
}
