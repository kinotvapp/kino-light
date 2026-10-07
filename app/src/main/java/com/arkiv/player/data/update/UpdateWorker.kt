package com.arkiv.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic maintenance (every 3h via WorkManager, scheduled by [com.arkiv.player.MainActivity]):
 * runs [OtaRuntime.checkForUpdate] end-to-end. The CloudStream bridge stub always answers false; this
 * worker calls it for parity with the real app, so a future swap to kino-app's `CloudStreamBridgeUpdate`
 * doesn't change the call site. An OTA check that reached no source retries with WorkManager's backoff
 * (10 min, doubling).
 *
 * The [OtaRuntime] is rebuilt per worker run -- it owns only lazy prefs, and the cacheDir from the
 * application context -- so no cross-process state to coordinate.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ota = OtaRuntime(applicationContext)
        val outcome = runCatching { ota.checkForUpdate("worker") }
        runCatching { com.arkiv.player.data.cloudstream.CloudStreamBridgeUpdate.needsUpdate() }
            .onFailure { android.util.Log.w("KinoOta", "bridge check failed: ${it.javaClass.simpleName}") }
        return if (shouldRetry(outcome)) Result.retry() else Result.success()
    }

    companion object {
        fun shouldRetry(outcome: kotlin.Result<UpdateCheckResult?>): Boolean =
            outcome.getOrNull().let { it == null || it is UpdateCheckResult.Failed }
    }
}