package app.kino.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.tv.data.KinoSources
import app.kino.tv.data.KinoSession
import app.kino.tv.ui.fullAppOnly
import app.kino.tv.ui.settings.ConnectedGreen
import app.kino.tv.ui.settings.KinoCompanion
import app.kino.tv.ui.settings.KinoSettings
import app.kino.tv.ui.settings.rememberUpdateCheck
import app.kino.tv.ui.settings.SubtitlePreview
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.kino.tv.ui.theme.KinoTextSecondary

/** Ajustes' tabs on the TV. */
enum class TvSettingsTab(val label: String) {
    SUBTITLES("Subtítulos"),
    APP("App"),
    PLUGINS("Plugins"),
    CONNECT("Conectar"),
}

/** "Ajustes" on the TV: the title and its tab row on one line, the selected tab's rows below. */
@Composable
fun TvSettingsScreen(initialTab: TvSettingsTab = TvSettingsTab.SUBTITLES) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val landing = rememberLandingFocus()

    Column(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ajustes", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.width(20.dp))
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(TvSettingsTab.entries.toList()) { t ->
                    TvTab(
                        label = t.label,
                        selected = t == tab,
                        onClick = { tab = t },
                        modifier = if (t == tab) Modifier.landingFocus(landing) else Modifier,
                    )
                }
            }
        }
        if (tab == TvSettingsTab.PLUGINS) {
            TvPluginsContent(modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    TvSettingsTab.SUBTITLES -> TvSubtitlesTab()
                    TvSettingsTab.APP -> TvAppTab()
                    TvSettingsTab.CONNECT -> TvConnectTab()
                    TvSettingsTab.PLUGINS -> Unit
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
}

/** A row whose value left/right step through, and OK cycles. */
@Composable
private fun TvStepOption(label: String, onStep: (delta: Int, wrap: Boolean) -> Unit) {
    TvActionOption(
        "$label   ◀ ▶",
        modifier = Modifier.onPreviewKeyEvent { e ->
            val delta = when (e.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> +1
                else -> return@onPreviewKeyEvent false
            }
            if (e.type == KeyEventType.KeyDown) onStep(delta, false)
            true
        },
    ) { onStep(+1, true) }
}

@Composable
private fun ColumnScope.TvSubtitlesTab() {
    val s = KinoSettings
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvSectionTitle("Idioma del audio")
            s.audioOrder.toList().forEachIndexed { i, lang ->
                TvActionOption("${i + 1}. $lang   ▲") { s.moveUp(s.audioOrder, lang) }
            }
            TvSectionTitle("Idioma de los subtítulos")
            s.subtitleOrder.toList().forEachIndexed { i, lang ->
                TvActionOption("${i + 1}. $lang   ▲") { s.moveUp(s.subtitleOrder, lang) }
            }
            TvActionOption(if (s.subtitlesAuto) "Cuándo mostrarlos: Automático" else "Cuándo mostrarlos: Desactivado") { s.subtitlesAuto = !s.subtitlesAuto }
            TvSectionTitle("Estilo de los subtítulos")
            TvStepOption("Tamaño: ${s.size} %") { d, w -> s.size = s.step(s.sizes, s.size, d, w) }
            TvStepOption("Color: ${s.colors.first { it.first == s.textColor }.second}") { d, _ ->
                val i = s.colors.indexOfFirst { it.first == s.textColor }
                s.textColor = s.colors[(i + d + s.colors.size) % s.colors.size].first
            }
            TvActionOption(if (s.bold) "Grosor: Negrita" else "Grosor: Normal") { s.bold = !s.bold }
            TvStepOption("Opacidad del fondo: ${s.opacity} %") { d, w -> s.opacity = s.step(s.opacities, s.opacity, d, w) }
            TvStepOption("Borde: ${s.edge}") { d, _ -> s.edge = s.edges[(s.edges.indexOf(s.edge) + d + s.edges.size) % s.edges.size] }
            TvStepOption("Altura: ${s.positionLabel(s.position)}") { d, w -> s.position = s.step(s.positions, s.position, d, w) }
        }
        Box(Modifier.weight(0.6f)) { SubtitlePreview(isTv = true) }
    }
}

@Composable
private fun TvAppTab() {
    val context = LocalContext.current
    val s = KinoSettings
    val update = rememberUpdateCheck()
    var showNotices by rememberSaveable { mutableStateOf(false) }
    if (showNotices) TvOssNoticesDialog(onDismiss = { showNotices = false })
    TvSectionTitle("Actualizaciones")
    Note("Versión instalada: Kino 0.9.45")
    TvActionOption(if (update.checking) "Buscando…" else "Buscar actualizaciones") { update.run() }
    TvActionOption("Licencias de software libre") { showNotices = true }
    Note("Las bibliotecas de terceros que usa Kino.")
    TvSectionTitle("Almacenamiento")
    Note("Descargas: 1,4 GB · Caché: 86 MB · Libre: 21,3 GB")
    TvActionOption("Limpiar caché") { fullAppOnly(context) }
    TvActionOption("Borrar todas las descargas") { fullAppOnly(context) }
    Note("La caché son imágenes y copias temporales: la app las vuelve a crear.")
    TvSectionTitle("Reproductor")
    TvActionOption(if (s.funFacts) "Datos curiosos: activados" else "Datos curiosos: desactivados") { s.funFacts = !s.funFacts }
    TvActionOption(if (s.animeSkip) "Saltar intro en anime: activado" else "Saltar intro en anime: desactivado") { s.animeSkip = !s.animeSkip }
    TvActionOption(if (s.autoSkip) "Saltar la intro automáticamente: sí" else "Saltar la intro automáticamente: no") { s.autoSkip = !s.autoSkip }
    TvSectionTitle("Pantalla")
    TvActionOption("Forzar diseño TV") { fullAppOnly(context) }
    TvActionOption(if (s.effects) "Efectos visuales: activados" else "Efectos visuales: desactivados") { s.effects = !s.effects }
    Note("Animaciones y zoom en las tarjetas. Desactívalo si tu TV va lenta.")
    TvActionOption("DNS seguro: ${s.dns}") { s.dns = s.dnsModes[(s.dnsModes.indexOf(s.dns) + 1) % s.dnsModes.size] }
    Note("Si tu proveedor de internet bloquea canales o servicios, prueba con Google. «Ninguno» usa el DNS de tu dispositivo, sin el de la app.")
    TvActionOption(if (s.navSounds) "Sonidos de navegación: activados" else "Sonidos de navegación: desactivados") { s.navSounds = !s.navSounds }
}

@Composable
private fun TvConnectTab() {
    val c = KinoCompanion
    val scope = rememberCoroutineScope()
    TvSectionTitle("Conectar")
    Note("Abre Kino en tu celular, ve a Ajustes ▸ Conectar y elige esta TV. Si te pide un código, es este:")
    Text("482 913", style = MaterialTheme.typography.displaySmall, color = Color.White)
    Note("Nombre de esta TV: TV de la sala")
    Text("Conectado: Celular principal", style = MaterialTheme.typography.bodyMedium, color = ConnectedGreen)
    TvSectionTitle("Dispositivos emparejados")
    if (c.pairedPhones.isEmpty()) {
        Note("Todavía no hay dispositivos emparejados.")
        return
    }
    c.pairedPhones.toList().forEach { phone ->
        Column(
            Modifier.fillMaxWidth(0.6f).background(KinoSurface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(phone.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Note(phone.statusLine())
            TvCompactAction(if (phone.selected) "Sincronizar con este dispositivo: sí" else "Sincronizar con este dispositivo: no") {
                phone.selected = !phone.selected
            }
            TvCompactAction("Olvidar") { c.forget(c.pairedPhones, phone) }
        }
    }
    TvActionOption(if (c.syncing) "Sincronizando…" else "Sincronizar ahora") {
        if (c.syncing || c.pairedPhones.none { it.selected }) return@TvActionOption
        c.syncing = true
        scope.launch {
            try {
                delay(1_500)
            } finally {
                c.finishSync(c.pairedPhones)
            }
        }
    }
    Note("Con todos los dispositivos elegidos que estén en tu red.")
}
