package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.allFilms
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/** "Descargas" on the phone, with example rows (two ready, one in progress): nothing is downloaded for real. */
@Composable
fun DownloadsScreen(rows: List<CatalogRow>, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val examples = remember(rows) { allFilms(rows).takeLast(3).zip(listOf(1f, 1f, 0.42f)) }
    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text("Descargas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { fullAppOnly(context) }) { Text("Cancelar todos") }
                TextButton(onClick = { fullAppOnly(context) }) { Text("Quitar todos") }
            }
        }
        items(examples, key = { it.first.id }) { (film, progress) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KinoSurfaceHigh),
                ) {
                    AsyncImage(model = film.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(film.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val done = progress >= 1f
                    Text(
                        if (done) "Listo · ${film.durationMin * 6} MB" else "Bajando ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = KinoTextSecondary,
                    )
                    if (!done) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress },
                            color = KinoRed,
                            trackColor = KinoSurfaceHigh,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    Row {
                        if (done) {
                            TextButton(onClick = { fullAppOnly(context) }) { Text("Enviar a la TV") }
                        } else {
                            TextButton(onClick = { fullAppOnly(context) }) { Text("Cancelar") }
                        }
                        TextButton(onClick = { fullAppOnly(context) }) { Text("Quitar") }
                    }
                }
                IconButton(onClick = { fullAppOnly(context) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = KinoRed)
                }
            }
        }
    }
}
