package com.arkiv.player.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arkiv.player.AppGraph
import java.util.concurrent.TimeUnit

/**
 * Runs [Mp4Prep] in WorkManager, so a conversion that takes minutes survives the app closing.
 *
 * Two shapes: the episodes asked for ([KEY_IDS]: a download that just finished, an older one just
 * opened, "Enviar a la TV"), chained one after another (APPEND_OR_REPLACE: a request never cuts the
 * conversion in progress), and the daily sweep while charging ([KEY_SWEEP]) over every download
 * still worth a pass. Both go through [Mp4Prep]'s own lock, so two never convert at once.
 *
 * A foreground notification with the percentage while it converts ("Preparando para la TV… NN%");
 * if the system refuses the foreground service it runs anyway, as the download worker does.
 */
class Mp4PrepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!DownloadAvailability.allowed(com.arkiv.player.DeviceType.isTelevision(applicationContext))) return Result.success()
        val graph = AppGraph.from(applicationContext)
        val prep = graph.mp4Prep
        val dir = graph.localDownloads.targetDir()
        val ids = if (inputData.getBoolean(KEY_SWEEP, false)) prep.sweepIds(dir) else inputData.getStringArray(KEY_IDS)?.toList().orEmpty()
        if (ids.isEmpty()) {
            prep.deletePending(dir)
            return Result.success()
        }
        var foreground = false
        for (id in ids.distinct()) {
            var lastShown = -1
            val state = runCatching {
                prep.prepare(id) { percent ->
                    if (percent == lastShown) return@prepare
                    lastShown = percent
                    if (!foreground) {
                        foreground = true
                        runCatching { setForegroundAsync(foregroundInfo(percent)) }
                            .onFailure { Log.w(TAG, "no foreground notification: ${it.message}") }
                    } else {
                        update(percent)
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            Log.i(TAG, "$id → $state")
            runCatching { prep.onPrepared(id, state) }
        }
        return Result.success()
    }

    private fun notification(percent: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Preparando para la TV…")
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        ensureChannel(applicationContext)
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification(percent))
        }
    }

    private fun update(percent: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification(percent))
        }
    }

    companion object {
        private const val TAG = "KinoMp4Prep"
        const val CHANNEL_ID = "arkiv_local_downloads"
        private const val NOTIF_ID = 4712
        private const val KEY_IDS = "ids"
        private const val KEY_SWEEP = "sweep"
        private const val WORK_NAME = "kino_mp4_prep"
        private const val SWEEP_NAME = "kino_mp4_prep_sweep"

        /** The same channel as the downloads ("Descargas"). */
        fun ensureChannel(context: Context) {
            if (android.os.Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW))
        }

        /** Prepares [episodeIds] after whatever is already being prepared. */
        fun enqueue(context: Context, episodeIds: List<String>) {
            if (episodeIds.isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<Mp4PrepWorker>()
                    .setInputData(workDataOf(KEY_IDS to episodeIds.toTypedArray()))
                    .build(),
            )
        }

        /** The daily pass over older downloads, only while charging and with storage not low. */
        fun scheduleSweep(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SWEEP_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Mp4PrepWorker>(1, TimeUnit.DAYS)
                    .setInputData(workDataOf(KEY_SWEEP to true))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(true)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
