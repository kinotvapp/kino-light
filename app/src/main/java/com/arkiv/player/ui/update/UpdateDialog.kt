package com.arkiv.player.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.arkiv.player.AppGraph
import com.arkiv.player.data.update.DownloadState
import com.arkiv.player.data.update.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Modal OTA update dialog: shows version/notes, triggers the APK download (via
 * [AppGraph.apkDownloader]) and launches the install once it's done. Not dismissible while
 * downloading (neither back nor a tap outside), to avoid leaving the download half-done.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, graph: AppGraph, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    // Set once the APK is downloaded AND its sha256 verified: the download step is done, so the
    // dialog shows an explicit "Instalar" button instead of launching the installer on its own.
    var readyFile by remember { mutableStateOf<File?>(null) }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(downloading) {
        if (downloading) return@LaunchedEffect
        delay(200)
        repeat(20) {
            if (runCatching { buttonFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startDownload() {
        // canRequestPackageInstalls() (the per-app "install unknown apps" permission) is API 26+.
        // On Android 7 (API 24/25) unknown-sources is a single global setting instead, and the
        // install intent below simply prompts the user if it is off -- so there is nothing to gate.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        downloading = true
        error = null
        downloadJob = scope.launch {
            graph.apkDownloader.download(info.url, info.sha256).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> progress = state.progress
                    // Verified (sha256 matched, if the manifest carried one): hand it to the person
                    // via an "Instalar" button rather than launching the installer unprompted.
                    is DownloadState.Ready -> { downloading = false; readyFile = state.file }
                    is DownloadState.Failed -> { downloading = false; error = state.error }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nueva versión ${info.versionName}", style = MaterialTheme.typography.titleLarge)
                if (info.notes.isNotBlank()) {
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }

                if (downloading) {
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.End),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                if (readyFile != null) {
                    Text(
                        "Descarga verificada. Lista para instalar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                error?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (downloading) {
                        TextButton(onClick = {
                            downloadJob?.cancel()
                            downloading = false
                        }) { Text("Cancelar") }
                    } else if (readyFile != null) {
                        TextButton(onClick = onDismiss) { Text("Después") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { readyFile?.let { installApk(it) } },
                            modifier = Modifier.focusRequester(buttonFocus),
                        ) { Text("Instalar") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Cerrar") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { startDownload() },
                            modifier = Modifier.focusRequester(buttonFocus),
                        ) { Text(if (error != null) "Reintentar" else "Actualizar ahora") }
                    }
                }
            }
        }
    }
}
