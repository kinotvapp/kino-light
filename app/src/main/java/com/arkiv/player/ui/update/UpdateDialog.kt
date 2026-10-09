package com.arkiv.player.ui.update

import android.content.Intent
import android.net.Uri
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
import com.arkiv.player.data.update.ApkInstallLauncher
import com.arkiv.player.data.update.DownloadState
import com.arkiv.player.data.update.OtaRuntime
import com.arkiv.player.data.update.UpdateCheckResult
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.data.update.launchFirstAvailable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Opt-in update dialog: shown when a newer release exists. Nothing is downloaded until the person
 * taps "Descargar e instalar"; "Ahora no", back and tap-outside all dismiss it, and a running
 * download can be cancelled. The text says plainly that the APK comes from GitHub and is installed
 * outside the store the app was installed from (F-Droid's inclusion policy: explicit, opt-in
 * consent, declining no harder than accepting).
 */
@Composable
fun UpdateDialog(info: UpdateInfo, ota: OtaRuntime, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var readyFile by remember { mutableStateOf<File?>(null) }
    var launched by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(info) }
    val buttonFocus = remember { FocusRequester() }
    val installer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RESULT_OK: the system installer committed the new APK. The system kills this process as
        // part of replacing the package; the person must open the app themselves to see the new
        // version. We don't auto-launch the launcher -- doing so would skip the explicit "reopen"
        // step the dialog told them about.
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // The visible state stays as "ready, download verified" until the system kills us;
            // the dialog dies with the activity.
            return@rememberLauncherForActivityResult
        }
        val outcome = installOutcome(result.resultCode) ?: return@rememberLauncherForActivityResult
        val code = result.data?.getIntExtra(EXTRA_INSTALL_RESULT, 0) ?: 0
        val givenUp = ota.updateInstallOutcome(downloaded, outcome, installExtras(context) + ("install_result" to code.toString()), code)
        if (outcome == "failed") {
            if (givenUp) readyFile = null
            error = installFailureMessage(code, givenUp)
            launched = false
        }
    }

    LaunchedEffect(downloading) {
        if (downloading) return@LaunchedEffect
        delay(200)
        repeat(20) {
            if (runCatching { buttonFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    fun installApk(file: File) {
        if (!runCatching { file.isFile && file.length() > 0 }.getOrDefault(false)) {
            ota.updateInstallOutcome(downloaded, "file_missing", installExtras(context))
            readyFile = null
            error = DOWNLOAD_LOST
            launched = false
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrElse {
            ota.updateInstallOutcome(downloaded, "file_missing", installExtras(context) + ("error" to it.javaClass.simpleName))
            readyFile = null
            error = DOWNLOAD_LOST
            launched = false
            return
        }
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        if (!launchFirstAvailable(listOf(intent)) { installer.launch(it) }) {
            ota.updateInstallOutcome(downloaded, "no_installer", installExtras(context))
            error = "Este dispositivo no puede instalar la actualización desde la app"
            launched = false
        }
    }

    fun startDownload() {
        if (ApkInstallLauncher.requestPermissionIfNeeded(context)) return
        downloading = true
        error = null
        launched = false
        downloadJob = scope.launch {
            val fresh = (runCatching { ota.checkForUpdateNow() }.getOrNull() as? UpdateCheckResult.Available)?.info ?: info
            downloaded = fresh
            try {
                ota.downloadUpdate(fresh).collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> progress = state.progress
                        // Verified copy on disk: the person must tap "Instalar" themselves. The
                        // system installer needs them to have granted "Install unknown apps"
                        // for this app first; if not, [ApkInstallLauncher.requestPermissionIfNeeded]
                        // inside [installApk] opens the settings screen -- once they flip the
                        // toggle and come back, "Instalar" launches the install.
                        is DownloadState.Ready -> {
                            downloading = false
                            readyFile = state.file
                        }
                        is DownloadState.Failed -> {
                            downloading = false
                            error = state.error
                            launched = false
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = com.arkiv.player.data.update.ApkDownloader.USER_ERROR
            } finally {
                downloading = false
            }
        }
    }

    val decline: () -> Unit = {
        downloadJob?.cancel()
        downloading = false
        onDismiss()
    }

    Dialog(
        onDismissRequest = decline,
        properties = DialogProperties(),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Actualización disponible", style = MaterialTheme.typography.titleLarge)
                Text("Versión ${info.versionName}", style = MaterialTheme.typography.titleMedium)
                if (info.notes.isNotBlank()) {
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Si aceptas, Kino descargará la actualización desde GitHub (kinotvapp/kino-light) y la " +
                        "instalará por fuera de la tienda desde la que instalaste la app, sin pasar por sus revisiones. " +
                        "Si no aceptas, no se descarga nada.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // High-contrast warning so the post-install message isn't missed. White background
                // (the dialog is dark) + red text (high urgency). Outlined so it doesn't bleed into
                // the surrounding Material 3 dark surface.
                Surface(
                    color = androidx.compose.ui.graphics.Color.White,
                    contentColor = androidx.compose.ui.graphics.Color(0xFFD32F2F),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Tras instalar, Kino Tv se cerrará",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                        Text(
                            text = "Vas a tener que volver a abrir la app para ver la nueva versión. No la desinstales antes.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (downloading) {
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                    Text("Descarga verificada. Lista para instalar.", style = MaterialTheme.typography.bodySmall)
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    // Declining is always one tap away and as prominent as accepting.
                    OutlinedButton(onClick = decline) { Text(if (downloading) "Cancelar" else "Ahora no") }
                    when {
                        downloading -> Unit
                        readyFile != null -> {
                            Button(
                                onClick = { readyFile?.let { installApk(it) } },
                                modifier = Modifier.focusRequester(buttonFocus),
                            ) { Text("Instalar") }
                        }
                        error == com.arkiv.player.data.update.ApkDownloader.MANUAL_INSTALL -> {
                            // The release cannot be installed by this app on this device -- sideload
                            // by hand from the GitHub release page.
                            Button(
                                onClick = {
                                    val page = Intent(Intent.ACTION_VIEW, Uri.parse(releasePage(downloaded.versionName))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    launchFirstAvailable(listOf(page)) { context.startActivity(it) }
                                },
                                modifier = Modifier.focusRequester(buttonFocus),
                            ) { Text("Abrir GitHub") }
                        }
                        else -> {
                            Button(
                                onClick = { startDownload() },
                                modifier = Modifier.focusRequester(buttonFocus),
                            ) { Text(if (error != null) "Reintentar" else "Descargar e instalar") }
                        }
                    }
                }
            }
        }
    }
}

private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"

private const val DOWNLOAD_LOST = "La descarga se borró antes de instalarla. Toca Reintentar para bajarla de nuevo."

internal fun releasePage(versionName: String): String =
    "https://github.com/kinotvapp/kino-light/releases/tag/v" + versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' }

internal fun installFailureMessage(installResult: Int, givenUp: Boolean): String = when {
    givenUp -> com.arkiv.player.data.update.ApkDownloader.MANUAL_INSTALL
    installResult == com.arkiv.player.data.update.OtaJournal.INSUFFICIENT_STORAGE ->
        "No hay espacio suficiente para instalar la actualización. Libera espacio en el aparato y toca Instalar de nuevo."
    else -> "No se pudo instalar la actualización. Toca Instalar para intentarlo de nuevo."
}

internal fun installOutcome(resultCode: Int): String? = when (resultCode) {
    android.app.Activity.RESULT_OK -> null
    android.app.Activity.RESULT_FIRST_USER -> "failed"
    else -> "cancelled"
}

private fun installExtras(context: android.content.Context): Map<String, String> = mapOf(
    "method" to "intent_install_package",
    "unknown_apps" to if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        if (runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)) "allowed" else "denied"
    } else "n/a",
)