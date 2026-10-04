package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kino.demo.ui.components.KinoChip
import app.kino.demo.ui.components.SettingLabel
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.settings.DemoSettings
import app.kino.demo.ui.settings.SubtitlePreview
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoTextSecondary
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    SUBTITLES("Subtítulos"),
    APP("App"),
    CONNECT("Conectar"),
}

/** "Ajustes" on the phone, in tabs. The controls respond; their values live only while the app runs. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpenDownloads: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.SUBTITLES) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTab.entries.forEach { t -> KinoChip(t.label, t == tab) { tab = t } }
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                when (tab) {
                    SettingsTab.SUBTITLES -> SubtitlesTab()
                    SettingsTab.APP -> AppTab(onOpenDownloads)
                    SettingsTab.CONNECT -> ConnectTab()
                }
                Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 32.dp))
            }
        }
    }
}

@Composable
private fun LanguageOrderEditor(title: String, options: List<String>, order: MutableList<String>) {
    SettingLabel(title)
    Column {
        order.toList().forEachIndexed { i, lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = { DemoSettings.toggle(order, lang) }, colors = CheckboxDefaults.colors(checkedColor = KinoRed))
                Text("${i + 1}. $lang", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { DemoSettings.moveUp(order, lang) }, enabled = i > 0) {
                    Text("▲", color = if (i > 0) Color.White else KinoTextSecondary)
                }
                TextButton(onClick = { DemoSettings.moveDown(order, lang) }, enabled = i < order.lastIndex) {
                    Text("▼", color = if (i < order.lastIndex) Color.White else KinoTextSecondary)
                }
            }
        }
        options.filterNot { it in order }.forEach { lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = false, onCheckedChange = { DemoSettings.toggle(order, lang) }, colors = CheckboxDefaults.colors(checkedColor = KinoRed))
                Text(lang, color = KinoTextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SubtitlesTab() {
    val s = DemoSettings
    LanguageOrderEditor("Idioma del audio (en orden de preferencia)", s.audioLanguages, s.audioOrder)
    SettingLabel("Idiomas que entiendo")
    Text(
        "Los subtítulos se prenden solos únicamente cuando el audio queda en un idioma que no está en esta lista.",
        style = MaterialTheme.typography.bodySmall,
        color = KinoTextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    s.audioLanguages.forEach { lang ->
        val checked = lang in s.understood
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { s.toggle(s.understood, lang) }, colors = CheckboxDefaults.colors(checkedColor = KinoRed))
            Text(lang, color = if (checked) Color.White else KinoTextSecondary)
        }
    }
    LanguageOrderEditor("Idioma de los subtítulos (en orden de preferencia)", s.subtitleLanguages, s.subtitleOrder)

    SettingLabel("Cuándo mostrarlos")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KinoChip("Automático", s.subtitlesAuto) { s.subtitlesAuto = true }
        KinoChip("Desactivado", !s.subtitlesAuto) { s.subtitlesAuto = false }
    }
    Text(
        "Automático: se prenden solo si el audio quedó en un idioma que no marcaste como entendido.",
        style = MaterialTheme.typography.bodySmall,
        color = KinoTextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )

    Text("Estilo de los subtítulos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    SubtitlePreview(isTv = false, modifier = Modifier.padding(bottom = 4.dp))

    SettingLabel("Tamaño: ${s.size} %")
    StepSlider(s.sizes, s.size) { s.size = it }
    SettingLabel("Color del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        s.colors.forEach { (color, _) ->
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(color))
                    .border(
                        if (color == s.textColor) 3.dp else 1.dp,
                        if (color == s.textColor) KinoRed else Color.Gray,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { s.textColor = color },
            )
        }
    }
    SettingLabel("Grosor del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KinoChip("Normal", !s.bold) { s.bold = false }
        KinoChip("Negrita", s.bold) { s.bold = true }
    }
    SettingLabel("Opacidad del fondo: ${s.opacity} %")
    StepSlider(s.opacities, s.opacity) { s.opacity = it }
    SettingLabel("Borde del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.edges.forEach { e -> KinoChip(e, s.edge == e) { s.edge = e } }
    }
    SettingLabel("Altura sobre el borde inferior: ${s.positionLabel(s.position)}")
    StepSlider(s.positions, s.position) { s.position = it }
}

/** A slider that only lands on [values]. */
@Composable
private fun StepSlider(values: List<Int>, current: Int, onChange: (Int) -> Unit) {
    Slider(
        value = values.indexOf(current).coerceAtLeast(0).toFloat(),
        onValueChange = { index -> onChange(values[index.roundToInt().coerceIn(0, values.lastIndex)]) },
        valueRange = 0f..values.lastIndex.toFloat(),
        steps = values.size - 2,
        colors = SliderDefaults.colors(thumbColor = KinoRed, activeTrackColor = KinoRed),
    )
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(0.9f).padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
private fun AppTab(onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val s = DemoSettings
    SectionTitle("Almacenamiento")
    Text("Descargas: 1,4 GB · Caché: 86 MB · Libre: 21,3 GB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    Button(onClick = onOpenDownloads) { Text("Ver descargas") }
    Button(onClick = { fullAppOnly(context) }, modifier = Modifier.padding(top = 8.dp)) { Text("Limpiar caché") }
    Button(onClick = { fullAppOnly(context) }, modifier = Modifier.padding(top = 8.dp)) { Text("Borrar todas las descargas") }
    Text(
        "La caché son imágenes y copias temporales: la app las vuelve a crear. Las descargas son tus películas y capítulos guardados.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )

    SectionTitle("Actualizaciones")
    Text("Versión instalada: Kino Demo 1.0.0", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    Button(onClick = { fullAppOnly(context) }) { Text("Buscar actualizaciones") }

    SectionTitle("Reproductor")
    SwitchRow("Datos curiosos", "Muestra un dato curioso de la película o serie durante la reproducción.", s.funFacts) { s.funFacts = it }
    SwitchRow(
        "Saltar intro en anime",
        "Busca dónde empieza y termina el opening de cada capítulo de anime y te muestra «Saltar intro».",
        s.animeSkip,
    ) { s.animeSkip = it }
    SwitchRow("Saltar automáticamente", "Cuando un capítulo tiene la intro marcada, se la salta solo, sin que toques el botón.", s.autoSkip) { s.autoSkip = it }
    Column(Modifier.fillMaxWidth(0.9f).padding(top = 16.dp)) {
        Text("DNS seguro", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Si tu proveedor de internet bloquea canales o servicios, prueba con Google. «Ninguno» usa el DNS de tu dispositivo, sin el de la app. Se aplica de inmediato.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { s.dns = s.dnsModes[(s.dnsModes.indexOf(s.dns) + 1) % s.dnsModes.size] },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(s.dns) }
    }

    SectionTitle("Pantalla")
    SwitchRow("Forzar diseño TV", "Actívalo si tu TV box abre la versión de tablet en vez de la de TV. La app se reiniciará.", false) { fullAppOnly(context) }
}

@Composable
private fun ConnectTab() {
    val context = LocalContext.current
    var ipPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Conectar", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Vincula este celular con un KINO de tu TV en la misma red Wi-Fi para controlarlo.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Text("Estado: sin conectar", style = MaterialTheme.typography.bodyMedium)
        SectionTitle("Dispositivos encontrados")
        listOf("TV de la sala" to "192.168.1.40:8765", "TV del cuarto" to "192.168.1.52:8765").forEach { (name, address) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { fullAppOnly(context) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(address, style = MaterialTheme.typography.bodySmall)
                }
                Text("Emparejar", style = MaterialTheme.typography.labelLarge)
            }
        }
        SectionTitle("Conectar por IP")
        Text(
            "Si no aparece en la lista, ingresa su dirección y el código que muestra en pantalla.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(value = ipPort, onValueChange = { ipPort = it }, singleLine = true, label = { Text("IP:puerto") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it },
            singleLine = true,
            label = { Text("Código (si es la primera vez)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(onClick = { fullAppOnly(context) }, modifier = Modifier.padding(top = 8.dp)) { Text("Conectar por IP") }
        SectionTitle("Dispositivos emparejados")
        Text("Todavía no hay dispositivos emparejados.", style = MaterialTheme.typography.bodySmall)
    }
}
