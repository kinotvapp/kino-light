package app.kino.demo.ui.remote

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.Film
import app.kino.demo.data.allFilms
import app.kino.demo.data.metaLine
import app.kino.demo.ui.formatDuration
import app.kino.demo.ui.settings.DemoCompanion
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val SKIP_MS = 10_000L

/** What the linked TV is playing, as the phone's remote sees it. In memory; "Detener" ends it for this session. */
private object NowPlayingOnTv {
    var index by mutableIntStateOf(0)
    var playing by mutableStateOf(true)
    var positionMs by mutableLongStateOf(23 * 60_000L + 14_000L)
    var stopped by mutableStateOf(false)
    var audio by mutableStateOf("Original")
    var subtitle by mutableStateOf<String?>(null)

    val audioTracks = listOf("Original", "Español latino")
    val subtitleTracks = listOf("Español", "Inglés")
}

/** The TV's short name for the "En el TV · Sala" line: "TV de la sala" → "Sala". */
internal fun shortTvName(name: String): String =
    name.removePrefix("TV de la ").removePrefix("TV del ").removePrefix("TV de ").replaceFirstChar { it.uppercase() }

/**
 * The phone's remote for the linked TV: a floating bar over the section screens while the connected TV plays
 * something, opening full screen on tap, with "¿Qué TV quieres controlar?" when more than one paired TV is around.
 */
@Composable
fun RemoteMiniPlayer(rows: List<CatalogRow>) {
    val films = remember(rows) { allFilms(rows) }
    val np = NowPlayingOnTv
    val tvName = DemoCompanion.connectedTvName()
    val visible = !np.stopped && tvName != null && films.isNotEmpty()
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val choices = DemoCompanion.pairedTvs.filter { tv -> DemoCompanion.foundTvs.any { it.first == tv.id } }
    val onPickTv: (() -> Unit)? = if (choices.size > 1) ({ picking = true }) else null

    // The TV's clock, as it would report it: one tick a second while it plays.
    LaunchedEffect(visible, np.playing) {
        while (visible && np.playing) {
            delay(1_000)
            val film = films.getOrNull(np.index) ?: break
            np.positionMs = (np.positionMs + 1_000).coerceAtMost(durationOf(film))
        }
    }
    LaunchedEffect(visible) { if (!visible) open = false }

    val film = films.getOrNull(np.index.coerceIn(0, (films.size - 1).coerceAtLeast(0)))
    AnimatedVisibility(
        visible && film != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        if (film != null && tvName != null) MiniPlayerBar(film, tvName, onOpen = { open = true }, onPickTv = onPickTv)
    }
    if (open && film != null && tvName != null) {
        MiniPlayerScreen(
            film = film,
            tvName = tvName,
            onClose = { open = false },
            onPickTv = onPickTv,
            onPrevious = { np.index = (np.index - 1 + films.size) % films.size; np.positionMs = 0 },
            onNext = { np.index = (np.index + 1) % films.size; np.positionMs = 0 },
        )
    }
    if (picking) {
        TvPickerSheet(
            choices = choices.map { it.id to it.name },
            controlled = DemoCompanion.connectedTvId,
            onPick = { DemoCompanion.connectedTvId = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

private fun durationOf(film: Film): Long = (if (film.durationMin > 0) film.durationMin else 90) * 60_000L

@Composable
private fun MiniPlayerBar(film: Film, tvName: String, onOpen: () -> Unit, onPickTv: (() -> Unit)?) {
    val np = NowPlayingOnTv
    Surface(
        color = KinoSurfaceHigh,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp).clickable(onClickLabel = "Abrir", onClick = onOpen),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { (np.positionMs.toFloat() / durationOf(film)).coerceIn(0f, 1f) },
                color = KinoRed,
                trackColor = Color.White.copy(alpha = 0.12f),
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.height(66.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    film.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(film.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "En el TV · ${shortTvName(tvName)}" + if (onPickTv != null) " ▾" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = KinoTextSecondary,
                        maxLines = 1,
                        modifier = Modifier.clickable(enabled = onPickTv != null, onClickLabel = "Elegir TV") { onPickTv?.invoke() },
                    )
                }
                IconButton(onClick = { np.playing = !np.playing }) {
                    Icon(
                        if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (np.playing) "Pausar" else "Reproducir",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** The remote opened full screen: blurred art, the poster, seek bar, transport, track pickers and "Detener". */
@Composable
private fun MiniPlayerScreen(
    film: Film,
    tvName: String,
    onClose: () -> Unit,
    onPickTv: (() -> Unit)?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val np = NowPlayingOnTv
    val duration = durationOf(film)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = KinoBlack, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Blur needs Android 12; before that the gradient alone darkens the art.
                AsyncImage(
                    film.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(32.dp) else it },
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.95f)))))
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Cerrar", tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "En el TV · ${shortTvName(tvName)}" + if (onPickTv != null) " ▾" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = KinoTextSecondary,
                            modifier = Modifier.clickable(enabled = onPickTv != null, onClickLabel = "Elegir TV") { onPickTv?.invoke() },
                        )
                    }
                    Spacer(Modifier.weight(0.4f))
                    AsyncImage(
                        film.posterUrl,
                        contentDescription = film.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(0.55f).widthIn(max = 260.dp).aspectRatio(2f / 3f)
                            .shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        film.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(film.metaLine(), style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(0.3f))

                    Slider(
                        value = np.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                        valueRange = 0f..duration.toFloat(),
                        onValueChange = { np.positionMs = it.toLong() },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = KinoRed, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatDuration(np.positionMs), style = MaterialTheme.typography.labelSmall, color = KinoTextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatDuration(duration - np.positionMs), style = MaterialTheme.typography.labelSmall, color = KinoTextSecondary)
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { np.positionMs = (np.positionMs - SKIP_MS).coerceAtLeast(0) }) {
                            Icon(Icons.Filled.Replay10, contentDescription = "Atrasar 10 segundos", tint = Color.White)
                        }
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(KinoRed).clickable { np.playing = !np.playing },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (np.playing) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(onClick = onNext) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { np.positionMs = (np.positionMs + SKIP_MS).coerceAtMost(duration) }) {
                            Icon(Icons.Filled.Forward10, contentDescription = "Adelantar 10 segundos", tint = Color.White)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TrackPicker("Audio", np.audioTracks, np.audio, offLabel = null) { np.audio = it ?: np.audio }
                        TrackPicker("Subtítulos", np.subtitleTracks, np.subtitle, offLabel = "Desactivados") { np.subtitle = it }
                    }
                    TextButton(onClick = { np.stopped = true; onClose() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Detener", color = KinoTextSecondary)
                    }
                    Spacer(Modifier.weight(0.3f))
                }
            }
        }
    }
}

@Composable
private fun TrackPicker(title: String, tracks: List<String>, current: String?, offLabel: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$title: ${current ?: offLabel ?: tracks.first()}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (offLabel != null) {
                DropdownMenuItem(text = { Text((if (current == null) "✓ " else "") + offLabel) }, onClick = { open = false; onPick(null) })
            }
            tracks.forEach { t ->
                DropdownMenuItem(text = { Text((if (t == current) "✓ " else "") + t) }, onClick = { open = false; onPick(t) })
            }
        }
    }
}

/** "¿Qué TV quieres controlar?": the paired TVs on the network by name, the controlled one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvPickerSheet(choices: List<Pair<String, String>>, controlled: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KinoSurfaceHigh) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("¿Qué TV quieres controlar?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            choices.forEach { (id, name) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
                    if (id == controlled) Icon(Icons.Filled.Check, contentDescription = "Controlando", tint = KinoRed)
                }
            }
        }
    }
}
