package com.arkiv.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arkiv.player.ArkivApp

/** Periodic maintenance (every 3h via WorkManager, see [ArkivApp.onCreate]): OTA check, a blob-only
 *  credential refresh, -- only for a device that has needed seeds and hasn't opted out -- a
 *  backup-pool re-download, and the installed plugins' update check (each at most once per 24 h;
 *  an update with new hosts is only marked pending, it waits for the person's approval). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        val result = runCatching {
            graph.checkForUpdate()
            graph.refreshCredentialsIfActivated()
            graph.refreshSeedsIfNeeded()
        }
        // Plugins on their own: a GitHub hiccup for one plugin must not make the APK, credentials
        // and seeds steps retry. Each plugin is checked at most once per 24 h (see
        // PluginInstaller.checkDueUpdates), so running this every 3 h costs nothing extra.
        runCatching { graph.checkPluginUpdates() }
            .onFailure { android.util.Log.w("KinoPlugin", "plugin update check failed", it) }
        return result.fold({ Result.success() }, { Result.retry() })
    }
}
