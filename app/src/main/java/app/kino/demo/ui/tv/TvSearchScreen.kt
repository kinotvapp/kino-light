package app.kino.demo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.Film
import app.kino.demo.data.searchFilms
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary

/** A key of the on-screen keyboard: a character, space or backspace. */
private sealed interface Key {
    data class Char(val c: kotlin.Char) : Key
    data object Space : Key
    data object Backspace : Key
}

/** A-Z and 0-9 in six columns, then space and backspace. */
private val KEYBOARD_ROWS: List<List<Key>> = buildList {
    (('A'..'Z') + ('0'..'9')).map { Key.Char(it) }.chunked(6).forEach { add(it) }
    add(listOf(Key.Space, Key.Backspace))
}

private fun Key.label() = when (this) {
    is Key.Char -> c.toString()
    Key.Space -> "Espacio"
    Key.Backspace -> "Borrar"
}

private fun Key.span() = when (this) {
    is Key.Char -> 1
    Key.Space -> 4
    Key.Backspace -> 2
}

private fun apply(text: String, key: Key) = when (key) {
    is Key.Char -> text + key.c
    Key.Space -> "$text "
    Key.Backspace -> text.dropLast(1)
}

/**
 * The remote's alphabetic keyboard: dark keys, the focused one red. Key size follows the smaller of
 * what the width and the height allow, so the whole grid always fits.
 */
@Composable
private fun TvKeyboard(text: String, onTextChange: (String) -> Unit, landing: LandingFocus, modifier: Modifier = Modifier) {
    val gap = 8.dp
    BoxWithConstraints(modifier) {
        val byWidth = (maxWidth - gap * 5) / 6
        val byHeight = (maxHeight - gap * (KEYBOARD_ROWS.size - 1)) / KEYBOARD_ROWS.size
        val keySize = minOf(byWidth, byHeight)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            KEYBOARD_ROWS.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { c, key ->
                        val span = key.span()
                        Surface(
                            onClick = { onTextChange(apply(text, key)) },
                            modifier = Modifier
                                .width(keySize * span + gap * (span - 1))
                                .height(keySize)
                                .then(if (r == 0 && c == 0) Modifier.landingFocus(landing) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = KinoSurfaceHigh,
                                contentColor = Color.White,
                                focusedContainerColor = KinoRed,
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(key.label(), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Buscar" on the TV: the keyboard on the left, the matching films on the right as you type. */
@Composable
fun TvSearchScreen(rows: List<CatalogRow>, onOpenFilm: (Film) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val results = remember(rows, text) { searchFilms(rows, text) }
    val landing = rememberLandingFocus()

    Box(Modifier.fillMaxSize().background(KinoBlack)) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxHeight().width(380.dp).padding(24.dp)) {
                Text(
                    text.ifBlank { "Buscar…" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (text.isBlank()) KinoTextSecondary else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                TvKeyboard(text = text, onTextChange = { text = it }, landing = landing, modifier = Modifier.weight(1f))
            }
            Column(Modifier.fillMaxSize().padding(top = 24.dp, end = 24.dp)) {
                Text(
                    if (text.isBlank()) "Películas" else "Resultados",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (results.isEmpty()) {
                    Text("Sin resultados", style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, end = 8.dp, start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(results, key = { it.id }) { film ->
                        Column {
                            TvLandscapeCard(title = film.title, imageUrl = film.posterUrl, cardHeight = 96.dp, onClick = { onOpenFilm(film) })
                            Text(
                                film.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = KinoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
