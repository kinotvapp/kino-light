package com.arkiv.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack

private val CARD_HEIGHT = 110.dp
private val CARD_RADIUS = RoundedCornerShape(10.dp)

@Composable
fun CategoriesScreen(
    contentPadding: PaddingValues,
    onBrowseRow: (rowId: String, title: String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: CategoriesViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriesViewModel(graph.magisHomeCatalog, graph.homeReloads) } },
    )
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val displayRows = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    val fixed = displayRows.filter { it.id.startsWith("magis_new_") || it.id.startsWith("magis_top_") }
    val movieGenres = displayRows.filter { it.id.startsWith("magis_g_peliculas_") }
    val seriesGenres = displayRows.filter { it.id.startsWith("magis_g_series_") }
    val animeGenres = displayRows.filter { it.id.startsWith("magis_g_anime_") }
    val kidsGenres = displayRows.filter { it.id.startsWith("magis_g_infantil_") }

    if (loading && rows.size <= 8) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(2, isLandscapeTablet())),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title + search — full span
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    "Categorías",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar categoría…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
        }

        if (fixed.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Destacadas") }
            items(fixed, key = { it.id }) { spec ->
                CategoryCard(
                    title = spec.title,
                    imageUrl = spec.previewUrl,
                    onClick = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }

        if (movieGenres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Géneros · Películas") }
            items(movieGenres, key = { it.id }) { spec ->
                CategoryCard(
                    title = spec.title.removeSuffix(" · Películas"),
                    imageUrl = spec.previewUrl,
                    onClick = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }

        if (seriesGenres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Géneros · Series") }
            items(seriesGenres, key = { it.id }) { spec ->
                CategoryCard(
                    title = spec.title.removeSuffix(" · Series"),
                    imageUrl = spec.previewUrl,
                    onClick = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }

        if (animeGenres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Géneros · Anime") }
            items(animeGenres, key = { it.id }) { spec ->
                CategoryCard(
                    title = spec.title.removeSuffix(" · Anime"),
                    imageUrl = spec.previewUrl,
                    onClick = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }

        if (kidsGenres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Géneros · Infantil") }
            items(kidsGenres, key = { it.id }) { spec ->
                CategoryCard(
                    title = spec.title.removeSuffix(" · Infantil"),
                    imageUrl = spec.previewUrl,
                    onClick = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }

        if (displayRows.isEmpty()) {
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

@Composable
private fun CategoryCard(title: String, imageUrl: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(CARD_RADIUS)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Dark gradient so the text is always legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.85f)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
