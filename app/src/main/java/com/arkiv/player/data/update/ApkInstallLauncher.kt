package com.arkiv.player.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded, verified APK to the system installer: the CloudStream complement
 * ([com.arkiv.player.data.cloudstream.CloudStreamBridgeUpdate]) through [launch]; Kino's own update
 * ([com.arkiv.player.ui.update.UpdateDialog]) asks [requestPermissionIfNeeded] and launches its installer itself, for the
 * installer's result. Kino never installs anything itself: the person confirms on the system's screen, and Android
 * verifies the APK there.
 */
object ApkInstallLauncher {

    /**
     * When Kino may not ask to install packages yet, opens the screen that grants it and answers true (the person comes
     * back and tries again). canRequestPackageInstalls() (the per-app "install unknown apps" permission) is API 26+; on
     * Android 7 (API 24/25) unknown-sources is one global setting and the installer itself prompts, so nothing to gate.
     * Many TVs (SMART_TV, in the crash reports) have no "install unknown apps" screen at all: it tries that one, then the
     * general security settings, and when the device has neither answers false; the installer says if it refuses.
     */
    fun requestPermissionIfNeeded(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            return false
        }
        val unknownSources = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val security = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchFirstAvailable(listOf(unknownSources, security)) { context.startActivity(it) }
    }

    /**
     * Opens the system installer on [file] (a file under the cache dir, shared through the `fileprovider`), or first the
     * permission screen ([requestPermissionIfNeeded]). True when one of those system screens opened; false when the device
     * has none (a TV box can lack the installer screen too), so the caller says so instead of crashing.
     */
    fun launch(context: Context, file: File): Boolean {
        if (requestPermissionIfNeeded(context)) return true
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchFirstAvailable(listOf(intent)) { context.startActivity(it) }
    }
}

/**
 * Opens the first of [candidates] the device has a screen for. A device without one throws [ActivityNotFoundException];
 * that is swallowed here (the next candidate is tried) and any other failure is not. False when none could be opened.
 */
internal fun <T> launchFirstAvailable(candidates: List<T>, start: (T) -> Unit): Boolean {
    for (candidate in candidates) {
        try {
            start(candidate)
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            // try the next one
        }
    }
    return false
}