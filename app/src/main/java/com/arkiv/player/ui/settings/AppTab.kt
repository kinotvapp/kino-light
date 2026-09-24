package com.arkiv.player.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.arkiv.player.BuildConfig
import com.arkiv.player.data.credentials.SeedResult
import com.arkiv.player.data.local.FileSizeFormat
import com.arkiv.player.data.local.StorageUsage
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.update.UpdateDialog

/** What belongs to the app and not to the content: updates and access to offline downloads. */
@Composable
internal fun AppTab(onOpenDownloads: () -> Unit = {}) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var seeding by remember { mutableStateOf(false) }
    var seedMessage by remember { mutableStateOf<String?>(null) }
    val funFactsEnabled by graph.settings.funFactsEnabled.collectAsState()
    val seedAutoRefreshEnabled by graph.settings.seedAutoRefreshEnabled.collectAsState()
    val forceTvDesign by graph.settings.forceTvDesign.collectAsState()

    // Storage: what Kino takes up, re-measured after every action. See AppStorage.
    var usageTick by remember { mutableIntStateOf(0) }
    val usage by produceState<StorageUsage?>(initialValue = null, usageTick) {
        value = runCatching { graph.appStorage.usage() }.getOrNull()
    }
    var storageBusy by remember { mutableStateOf(false) }
    var confirmDeleteDownloads by remember { mutableStateOf(false) }

    // The country we recognize for this phone -- the same free, no-permission, no-network signal
    // (SIM -> time zone -> locale) the live-channels row uses. Shown next to the version so support
    // can see which region the app resolved the device to. Computed once; the signals don't change.
    val detectedCountry = remember {
        com.arkiv.player.ui.live.deviceCountry(context)?.let { iso ->
            val name = java.util.Locale("", iso).getDisplayCountry(java.util.Locale("es"))
            if (name.isNotBlank() && !name.equals(iso, ignoreCase = true)) "$name ($iso)" else iso
        }
    }

    // Manual check: independent of MainActivity's global dialog, so it works even if the user
    // already dismissed that one this session.
    fun checkForUpdatesNow() {
        checking = true
        scope.launch {
            // Manual check bypasses the staggered deferral: show a newer version right away.
            val info = graph.checkForUpdateNow()
            checking = false
            if (info != null) {
                manualUpdate = info
            } else {
                Toast.makeText(context, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    manualUpdate?.let { info ->
        UpdateDialog(info = info, graph = graph, onDismiss = { manualUpdate = null })
    }

    // The device type is read once per process (MainActivity picks the root, the graph wires
    // itself), so applying the override means restarting the app cleanly: launch our own restart
    // task, then kill the process so it comes back up fresh and re-reads DeviceType.
    fun applyForceTv(v: Boolean) {
        // Turning it ON means auto-detection got this device wrong: report its raw signals (only on
        // the ON edge, so correctly-detected devices add ZERO noise) to tune the detection with real
        // field data. See DeviceType.debugSignals / crash.DeviceProfile.
        if (v) {
            com.arkiv.player.crash.Crash.report(
                com.arkiv.player.crash.DeviceProfile(com.arkiv.player.DeviceType.debugSignals(context)),
                "device-misdetected-tv",
            )
        }
        graph.settings.setForceTvDesign(v)
        Toast.makeText(context, "Aplicando… la app se reiniciará", Toast.LENGTH_SHORT).show()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val component = launch?.component
        if (component != null) {
            context.startActivity(Intent.makeRestartActivityTask(component))
            Runtime.getRuntime().exit(0)
        }
    }

    // Manual re-seed: the backup-session pool is otherwise fetched only once, at activation. A
    // device activated before the pool existed (or whose one download failed) is stuck with an
    // empty local pool forever -- this is its self-recovery path. See SeedRefresher's KDoc.
    fun sembrarSemillas() {
        seeding = true
        seedMessage = null
        scope.launch {
            val result = graph.seedRefresher.reseed()
            seeding = false
            seedMessage = when (result) {
                is SeedResult.Ok -> "${result.count} semillas cargadas"
                SeedResult.Failed -> "Sin conexión, reintenta"
            }
        }
    }

    // Frees the regenerable files only (covers, Chromecast copies...): nothing the person saved.
    fun limpiarCache() {
        storageBusy = true
        scope.launch {
            val freed = runCatching { graph.appStorage.clearCache() }.getOrDefault(0L)
            storageBusy = false
            usageTick++
            Toast.makeText(context, "Se liberaron ${FileSizeFormat.formatSize(freed)}", Toast.LENGTH_SHORT).show()
        }
    }

    // Destructive: only reached through the confirmation dialog below.
    fun borrarDescargas() {
        storageBusy = true
        scope.launch {
            runCatching { graph.appStorage.deleteAllDownloads() }
            storageBusy = false
            usageTick++
            Toast.makeText(context, "Descargas borradas", Toast.LENGTH_SHORT).show()
        }
    }

    if (confirmDeleteDownloads) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownloads = false },
            title = { Text("¿Borrar todas las descargas?") },
            text = {
                Text(
                    "Se eliminarán ${FileSizeFormat.formatSize(usage?.downloadsBytes ?: 0L)} de películas y " +
                        "capítulos guardados en este dispositivo. No se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteDownloads = false; borrarDescargas() }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDownloads = false }) { Text("Cancelar") }
            },
        )
    }

    Text(
        "Almacenamiento",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    usage?.let {
        Text(
            "Descargas: ${FileSizeFormat.formatSize(it.downloadsBytes)} · " +
                "Caché: ${FileSizeFormat.formatSize(it.cacheBytes)} · " +
                "Libre: ${FileSizeFormat.formatSize(it.freeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Button(onClick = onOpenDownloads) {
        Text("Ver descargas")
    }
    Button(
        onClick = ::limpiarCache,
        enabled = !storageBusy && (usage?.cacheBytes ?: 0L) > 0L,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Limpiar caché")
    }
    Button(
        onClick = { confirmDeleteDownloads = true },
        enabled = !storageBusy && (usage?.downloadsBytes ?: 0L) > 0L,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Borrar todas las descargas")
    }
    Text(
        "La caché son imágenes y copias temporales: la app las vuelve a crear. Las descargas son tus " +
            "películas y capítulos guardados.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )

    Text(
        "Actualizaciones",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Text(
        "Versión instalada: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "País detectado: ${detectedCountry ?: "desconocido"}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Button(onClick = ::checkForUpdatesNow, enabled = !checking) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            Text("Buscando…", modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Buscar actualizaciones")
        }
    }

    Text(
        "Sesiones de respaldo",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Text(
        "Recarga las sesiones de respaldo si el contenido no reproduce",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Button(onClick = ::sembrarSemillas, enabled = !seeding) {
        if (seeding) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            Text("…", modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Sembrar semillas")
        }
    }
    seedMessage?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(0.9f).padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Actualizar semillas automáticamente", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Solo si el contenido dejó de reproducir en algún momento; si nunca pasó, no descarga nada.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = seedAutoRefreshEnabled,
            onCheckedChange = { graph.settings.setSeedAutoRefreshEnabled(it) },
        )
    }

    Text(
        "Reproductor",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(0.9f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Datos curiosos", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Muestra un dato curioso de la película o serie durante la reproducción.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = funFactsEnabled,
            onCheckedChange = { graph.settings.setFunFactsEnabled(it) },
        )
    }

    Text(
        "Pantalla",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(0.9f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Forzar diseño TV", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Actívalo si tu TV box abre la versión de tablet en vez de la de TV. La app se reiniciará.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = forceTvDesign,
            onCheckedChange = { applyForceTv(it) },
        )
    }

    // At the end and unannounced: locked, it shows no more than a row asking for a code.
    AdultsSection(graph.settings)
}
