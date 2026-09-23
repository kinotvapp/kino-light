package com.arkiv.player.ui.offline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests POST_NOTIFICATIONS **at the moment it's actually needed** (right when a device
 * download is triggered), not when the app opens: same contextual criterion as the camera
 * permission in `QrScannerScreen` -- the user understands what they're being asked for.
 *
 * The permission is declared in the manifest, but since Android 13 (API 33) it also has to be
 * requested at runtime; without this, [com.arkiv.player.data.local.LocalDownloadWorker]'s
 * "download complete" notification was silently dropped. Below API 33 the permission doesn't
 * exist and notifications work without asking for anything.
 *
 * Returns a lambda to call when starting the download. Doesn't block anything: if the user says
 * no, the download starts anyway and progress can still be seen on the Descargas screen.
 */
@Composable
fun rememberPostNotificationsRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
