package app.kino.tv.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kino.tv.data.Film
import app.kino.tv.data.metaLine
import app.kino.tv.ui.isLandscapeTablet
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/**
 * A film's information page on the phone: backdrop and title, the main button, and the synopsis.
 * Full screen, no top bar. One `LazyColumn` so the backdrop scrolls away.
 */
@Composable
fun TitleScreen(film: Film, onBack: () -> Unit, onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize().background(KinoBlack)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Capped on a landscape tablet, where a full-width line is unreadable.
            val maxWidth = if (isLandscapeTablet()) 720.dp else Dp.Unspecified
            LazyColumn(Modifier.widthIn(max = maxWidth).fillMaxSize()) {
                item(key = "hero") { Hero(film) }
                item(key = "actions") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        film.metaLine().takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                        }
                        Text(
                            "Dominio público · Internet Archive",
                            style = MaterialTheme.typography.labelMedium,
                            color = KinoTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(onClick = onPlay)
                        Synopsis(film.synopsis)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(32.dp)) }
            }
        }
        BackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun Hero(film: Film) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(
            model = film.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, KinoBlack))))
        Text(
            film.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onBack,
        modifier = modifier
            .statusBarsPadding()
            .padding(8.dp)
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
    }
}

@Composable
private fun PrimaryButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Reproducir", fontWeight = FontWeight.SemiBold)
    }
}

/** Three lines and "Más" to expand; nothing at all when there is no synopsis. */
@Composable
private fun Synopsis(text: String) {
    if (text.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > 140) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Menos" else "Más", color = Color.White)
            }
        }
    }
}
