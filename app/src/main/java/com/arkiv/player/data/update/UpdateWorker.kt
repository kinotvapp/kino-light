package com.arkiv.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arkiv.player.ArkivApp

/** Periodic maintenance (every 3h via WorkManager, see [ArkivApp.onCreate]): OTA check, a blob-only
 *  credential refresh, and -- only for a device that has needed seeds and hasn't opted out -- a
 *  backup-pool re-download. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        return runCatching {
            graph.checkForUpdate()
            graph.refreshCredentialsIfActivated()
            graph.refreshSeedsIfNeeded()
        }.fold({ Result.success() }, { Result.retry() })
    }
}
