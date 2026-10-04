package app.kino.demo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.data.Film
import app.kino.demo.data.metaLine
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/** A film's information page on the TV: full-bleed backdrop, the texts on the left and "Reproducir". */
@Composable
fun TvTitleScreen(film: Film, onPlay: () -> Unit) {
    val landing = rememberLandingFocus()

    Box(Modifier.fillMaxSize().background(KinoBlack)) {
        AsyncImage(
            model = film.posterUrl,
            contentDescription = film.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(KinoBlack, KinoBlack, KinoBlack.copy(alpha = 0.15f), Color.Transparent)),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, KinoBlack.copy(alpha = 0.4f), KinoBlack)),
            ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("Película  ·  Internet Archive", style = MaterialTheme.typography.labelLarge, color = KinoTextSecondary)
            Text(
                film.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = film.metaLine()
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            if (film.synopsis.isNotBlank()) {
                Text(
                    film.synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
                )
            }
            Text(
                "Dominio público",
                style = MaterialTheme.typography.bodySmall,
                color = KinoTextSecondary,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            )
            Button(
                onClick = onPlay,
                colors = kinoTvButtonColors(),
                border = kinoTvButtonBorder(),
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp).landingFocus(landing),
            ) {
                Text("▶  Reproducir")
            }
        }
    }
}
