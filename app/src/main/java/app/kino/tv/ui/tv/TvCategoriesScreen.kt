package app.kino.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.Film
import app.kino.tv.ui.theme.KinoBlack

/** "Categorías" on the TV: one titled row of cards per category. */
@Composable
fun TvCategoriesScreen(rows: List<CatalogRow>, onOpenFilm: (Film) -> Unit) {
    val landing = rememberLandingFocus()
    Column(Modifier.fillMaxSize().background(KinoBlack).padding(vertical = 28.dp)) {
        Text("Categorías", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(rows, key = { it.id }) { row ->
                TvRowLabel(row.title, 26.dp)
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(row.films, key = { "${row.id}-${it.id}" }) { film ->
                        TvLandscapeCard(
                            title = film.title,
                            imageUrl = film.posterUrl,
                            cardHeight = 110.dp,
                            modifier = if (row == rows.first() && film == row.films.first()) Modifier.landingFocus(landing) else Modifier,
                            onClick = { onOpenFilm(film) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
