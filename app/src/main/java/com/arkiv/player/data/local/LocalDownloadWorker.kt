package com.arkiv.player.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arkiv.player.AppGraph
import com.arkiv.player.MainActivity
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
import com.arkiv.player.data.db.DownloadEntity
import kotlinx.coroutines.runBlocking

/**
 * Processes the on-device download queue, ONE at a time.
 *
 * Sequential and not in parallel for three concrete reasons: `TorrentEngine` is for a single
 * active stream at a time, the blog's disk can't take several simultaneous stagings (phase 2), and
 * on the Fire TV Stick bandwidth doesn't spare.
 *
 * Self-relaunching: when a row finishes it re-queues itself to pick up the next one, instead of
 * looping inside a single `doWork()` — WorkManager doesn't guarantee an indefinitely long
 * background job.
 */
class LocalDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val dao = graph.database.downloadDao()

        val rows = dao.getAll().map { QueueRow(it.episodeId, it.state, it.createdAt) }
        val next = DownloadQueuePolicy.nextToProcess(rows) ?: return Result.success()
        val entity = dao.get(next.episodeId) ?: return Result.success()

        // Real name for the notifications (they used to show the episode's raw id) and how many
        // are waiting their turn: since the queue is ONE at a time, without that data the other
        // chapters look lost. Computed HERE, before the twin gate, so every notification in this
        // pass — including "you already had it" — can say which chapter it's about.
        val episode = graph.database.itemDao().getEpisode(entity.episodeId)
        val series = episode?.let { graph.database.itemDao().getItem(it.itemId)?.title }
        chapterName = DownloadNotificationText.name(series, episode?.displayName)
        notificationTitle = DownloadNotificationText.title(series, episode?.displayName)
        queued = rows.count { it.state == LocalDownloadState.QUEUED && it.episodeId != entity.episodeId }

        // The duplicate gate runs HERE TOO, not only in `LocalDownloadManager.enqueue`. Two
        // reasons, both real:
        //  1. Rows that were ALREADY in the queue never go through `enqueue` again. The user's
        //     device had exactly that: `web:series:tt30217403::31fe74c5` completed (461 MB on
        //     disk) and `web:series:anilist171018::31fe74c5` queued, waiting its turn to download
        //     the same file again.
        //  2. Two twins queued in the same batch both go through `enqueue` with neither completed
        //     yet. Since the queue is ONE at a time, by the time the second one gets here the first
        //     has already finished and this gate catches it.
        if (adoptTwinIfAlreadyDownloaded(graph, dao, entity)) {
            reschedule()
            return Result.success()
        }

        // `setForeground` can throw: if the app is in the background with no recently visible
        // Activity, or if the system restricts starting foreground services
        // (ForegroundServiceStartNotAllowedException on API 31+, or any other notification/binder
        // exception). If that happens it must NOT sink the download: downloading the file with no
        // visible notification is preferred over not downloading it. That's why it goes with
        // runCatching instead of letting the exception propagate out of doWork().
        // Every row that starts REPLACES the previous notification (same NOTIF_ID), so moving on
        // to the queue's next chapter turns the notification into that chapter's, with its own
        // progress.
        runCatching { setForeground(foregroundInfo(notificationTitle, null, entity.episodeId)) }
            .onFailure { Log.w(TAG, "couldn't show the foreground notification: ${it.message}") }

        val strategy = graph.downloadStrategies[entity.source]
        if (strategy == null) {
            dao.updateState(entity.episodeId, LocalDownloadState.FAILED, "Fuente no soportada: ${entity.source}")
            reschedule()
            return Result.success()
        }

        dao.updateState(entity.episodeId, LocalDownloadState.DOWNLOADING, null)

        val outcome = try {
            strategy.download(
                episodeId = entity.episodeId,
                alreadyConfirmed = entity.sizeConfirmed,
                targetDir = graph.localDownloads.targetDir(),
                onProgress = { done, total -> persistProgress(dao, entity, done, total) },
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // The cancellation is NOT swallowed (it used to go inside a runCatching, which caught
            // it same as any other exception). Swallowing it had two ugly consequences: the row was
            // left `failed` with a made-up reason even though the user had only cancelled, and
            // —worse— the strategy's `finally` (torrent at the time; that engine was removed in
            // this branch's pruning) never ran inside this coroutine, leaving the resource alive
            // for the whole session. Rethrowing it lets the strategy clean up and lets WorkManager
            // mark the work as CANCELLED.
            throw ce
        } catch (t: Throwable) {
            DownloadOutcome.Failed(t.message ?: "Error inesperado", transient = DownloadRetryPolicy.isTransient(t))
        }

        when (outcome) {
            is DownloadOutcome.Done -> {
                // Checked AS LATE AS POSSIBLE, right before writing the state: WorkManager's
                // cancellation (triggered by "Quitar") arrives ASYNCHRONOUSLY, and `HttpRangeDownloader`'s
                // final `renameTo` has no suspension point after the last cancellable check — so the
                // strategy can finish writing the destination file milliseconds after
                // `LocalDownloadManager.remove` has already deleted the row and swept the directory.
                // If the row is already gone, this file is exactly what that sweep failed to catch:
                // there's no other cleanup that will pick it up later, so it gets deleted here and
                // "Download complete" is NOT notified for something the user already removed.
                if (dao.get(entity.episodeId) == null) {
                    Log.i(
                        TAG,
                        "the row for ${entity.episodeId} was removed while it finished downloading; discarding the file",
                    )
                    runCatching { outcome.file.delete() }
                    runCatching { LocalFilePaths.partOf(outcome.file).delete() }
                    runCatching { LocalFilePaths.originOf(outcome.file).delete() }
                } else {
                    dao.markCompleted(entity.episodeId, outcome.file.absolutePath)
                    notifyDone(entity.episodeId)
                }
            }
            is DownloadOutcome.NeedsConfirmation -> {
                // Same care as in `Done`, but here the only misleading part is the notification:
                // there's no downloaded file to clean up (`NeedsConfirmation` is returned before any
                // bytes come down), and Room's `UPDATE`s on a row that's already been deleted don't
                // fail or have any effect (the WHERE matches nothing). What WOULD be misleading is
                // "Confirma en Descargas para bajarla" over a row the user already removed — there's
                // nothing to confirm.
                if (dao.get(entity.episodeId) != null) {
                    dao.updateProgress(entity.episodeId, 0f, 0, outcome.fileSizeBytes)
                    dao.updateState(
                        entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION,
                        "Pesa ${FileSizeFormat.formatSize(outcome.fileSizeBytes)}",
                    )
                    notifyNeedsConfirmation(entity.episodeId, outcome.fileSizeBytes)
                }
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "failed ${entity.episodeId}: ${outcome.reason} (transient=${outcome.transient})")
                // The same check ISN'T needed here: this branch notifies nothing visible (it only
                // logs and writes state), and an `UPDATE`/`Result.retry()` on a row already deleted
                // doesn't reintroduce the row or mislead anyone — worst case, if the user re-queued
                // it in the meantime, it's the SAME row (even the same episodeId) and the error's
                // reason is legitimate information for it.
                // Network cut halfway through 4 GB: the `.part` is intact and `Range` resumes, but
                // nothing was triggering that resumption because every failure ended in `failed`.
                // Now transient failures return `Result.retry()`: WorkManager retries THIS SAME
                // request with exponential backoff and the row stays in `downloading`, so
                // `nextToProcess` picks it again (what's started wins over what's queued). The
                // queue is NOT re-scheduled here: doing so with REPLACE would kill the scheduled retry.
                if (DownloadRetryPolicy.shouldRetry(outcome.transient, runAttemptCount)) {
                    dao.setError(entity.episodeId, outcome.reason)
                    return Result.retry()
                }
                dao.updateState(entity.episodeId, LocalDownloadState.FAILED, outcome.reason)
            }
        }

        reschedule()
        return Result.success()
    }

    /**
     * If [entity]'s content is already on disk under ANOTHER item, it doesn't download it:
     * **adopts the twin's file** (marks this row `completed` with the same `filePath`) and returns
     * `true`.
     *
     * Why adopt instead of deleting the row or marking it `failed`:
     *  - Silently deleting it leaves the chapter as "not downloaded" forever and the UI's button
     *    does nothing visible: the user taps it again and again nothing happens.
     *  - `failed` reflects something that didn't happen (nothing failed) and on top of that invites
     *    "Reintentar", which would fall back in here.
     *  - `completed` pointing at the twin's file tells the truth ("you already have it"), leaves
     *    the row visible and removable in Descargas, and on top of that makes THAT chapter
     *    watchable offline from its own item: `LocalLibrary.fileFor` resolves the same file and the
     *    library paints its checkmark. Without this it stayed without a checkmark and played over
     *    the network with the file sitting right there.
     *
     * Two rows sharing `filePath` is deliberate and is accounted for in
     * `LocalDownloadManager.remove`, which doesn't delete the file if another row references it.
     *
     * If the twin's file no longer exists (the user deleted it from outside), NOTHING gets adopted
     * and the download follows its normal course: the gate is "it's already on disk", not "it was
     * on disk at some point".
     */
    private suspend fun adoptTwinIfAlreadyDownloaded(
        graph: AppGraph,
        dao: com.arkiv.player.data.db.DownloadDao,
        entity: DownloadEntity,
    ): Boolean {
        val target = EpisodeOrigin(
            entity.episodeId,
            graph.database.itemDao().getEpisode(entity.episodeId)?.torrentFileIndex,
        )
        val twinId = DuplicateDownloadPolicy.completedDuplicateOf(target, dao.completedOrigins())
            ?: return false
        val twin = dao.get(twinId) ?: return false
        val path = twin.filePath ?: twin.localUri?.removePrefix("file://") ?: return false
        if (!java.io.File(path).let { it.exists() && it.length() > 0L }) return false

        Log.i(TAG, "${entity.episodeId} is already on disk as $twinId; adopting the file instead of downloading it")
        // The size is copied from the twin: it's the size of the file this row is about to serve,
        // and without this the Downloads screen would show 0 B for something that does take up disk.
        dao.updateProgress(entity.episodeId, 1f, twin.bytesDone, twin.bytes)
        dao.markCompleted(entity.episodeId, path)
        dao.setError(entity.episodeId, DuplicateDownloadPolicy.ADOPTED_REASON)
        // If this row was halfway through (a download that no longer makes sense was resumed), its
        // `.part` is left orphaned: nobody else references it or cleans it up. Same prefix sweep as
        // `LocalDownloadManager.remove`, which by using sanitize(episodeId) only touches files from
        // THIS episode — never the twin's, which is named with the twin's episodeId.
        runCatching {
            val prefix = "${LocalFilePaths.sanitize(entity.episodeId)}."
            graph.localDownloads.targetDir().listFiles { f -> f.name.startsWith(prefix) }
                ?.forEach { f -> runCatching { f.delete() } }
        }
        notifyAlreadyDownloaded(entity.episodeId)
        return true
    }

    /**
     * Writes progress to Room, no more than once a second. Without this cadence a 4 GB download
     * would fire tens of thousands of UPDATEs (the callback arrives every 64 KB) and the UI, which
     * observes the table, would keep recomposing non-stop.
     *
     * `lastPersistMs` is a mutable instance field, not a shared `companion object`/`var`:
     * WorkManager creates a NEW `LocalDownloadWorker` instance on every run (via `WorkerFactory`,
     * one per `doWork()`), so it starts at 0 on every pass and there's no sticky state between
     * downloads or corruption from reuse — this instance's lifecycle is exactly that of a single
     * call to `doWork()`.
     *
     * Not `suspend`: `DownloadStrategy.onProgress` is `(Long, Long) -> Unit`, a synchronous
     * callback (an interface that already exists and can't be touched here), and both strategies
     * invoke it synchronously from inside their own suspended `download()` (which already runs on
     * an I/O dispatcher). Since Room's DAO is `suspend`, it's bridged with `runBlocking` —
     * acceptable because the throttle above reduces it to at most one write per second, not one
     * per 64 KB chunk.
     */
    private var lastPersistMs = 0L

    /** Name, title and queue count of the row this pass is downloading; used by the notifications. */
    private var chapterName: String? = null
    private var notificationTitle = "Bajando un capítulo"
    private var queued = 0
    private fun persistProgress(
        dao: com.arkiv.player.data.db.DownloadDao,
        entity: DownloadEntity,
        done: Long,
        total: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PROGRESS_THROTTLE_MS) return
        lastPersistMs = now
        val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        // `updateProgress` and not `updateBytes`: writing the state together with the progress made
        // the (web) staging phase unreachable — the first progress tick would return the `staging`
        // row to `downloading`. The state is written by whoever knows the phase.
        runBlocking { dao.updateProgress(entity.episodeId, progress, done, total) }
        // Same throttle: the notification stays at the initial 0% for the whole download if nobody
        // emits it again. `total <= 0` is an unknown size -> indeterminate bar.
        updateNotification(entity.episodeId, if (total > 0) progress else null)
    }

    /**
     * Re-queues to pick up the next row. Deliberately does NOT call [schedule] (which uses `KEEP`):
     * at this point the single `WORK_NAME` row is still listed in WorkManager as `RUNNING` — this
     * very run hasn't finished writing its final state until `doWork()` returns — and `KEEP` looks
     * at exactly the `ENQUEUED`/`RUNNING` states to decide whether to do nothing. The result with
     * `KEEP` here would be a silent no-op on 100% of passes: the queue would process one row per
     * external `enqueue()` and never self-relaunch, breaking this worker's whole point. `REPLACE`
     * does force the next pass's insertion even while this instance is still "alive" for one more
     * instant.
     */
    private fun reschedule() {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request(),
        )
    }

    /**
     * The notification for the download in progress. [fraction] null = how much is left isn't
     * known yet, so the bar goes indeterminate instead of lying with a bar stuck at 0%.
     *
     * `setOnlyAlertOnce` because this notification is re-emitted every second with new progress:
     * without it, every update would "alert" again.
     */
    private fun progressNotification(title: String, fraction: Float?, episodeId: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(DownloadNotificationText.subtitle(fraction, queued))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            // Stop a download without having to open the app and look for the chapter.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar descarga",
                cancelIntent(episodeId),
            )
            .build()

    /** Fires [DownloadActionsReceiver], which cancels without opening anything. */
    private fun cancelIntent(episodeId: String): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        episodeId.hashCode(),
        Intent(applicationContext, DownloadActionsReceiver::class.java).apply {
            action = DownloadActionsReceiver.ACTION_CANCEL
            putExtra(DownloadActionsReceiver.EXTRA_EPISODE_ID, episodeId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Opens the player on THAT chapter (not on whatever was playing). */
    private fun viewIntent(episodeId: String): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        episodeId.hashCode(),
        Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            putExtra(EXTRA_EPISODE_ID, episodeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Re-emits the foreground notification with the new progress. Goes through
     * `NotificationManager` and not `setForeground`: it's the SAME notification (same id) and
     * updating it doesn't go through the service, so it can't sink the download if the system
     * restricts starting foreground services.
     */
    private fun updateNotification(episodeId: String, fraction: Float?) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, progressNotification(notificationTitle, fraction, episodeId))
        }
    }

    private fun foregroundInfo(title: String, fraction: Float?, episodeId: String): ForegroundInfo {
        ensureChannel()
        val notif = progressNotification(title, fraction, episodeId)
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    /**
     * "Descarga completa" + WHICH chapter finished + a button to watch it right there. It used to
     * only say "Descarga completa": with several downloads in a row there was no way to tell which
     * was which, and watching it meant opening the app and looking up the chapter by hand.
     */
    private fun notifyDone(episodeId: String) = notify(
        episodeId.hashCode(),
        "Descarga completa",
        DownloadNotificationText.done(null, chapterName),
        viewEpisodeId = episodeId,
    )

    private fun notifyAlreadyDownloaded(episodeId: String) = notify(
        episodeId.hashCode(),
        "Ya lo tenías descargado",
        "Ese capítulo ya estaba en el dispositivo; no se bajó de nuevo",
    )

    private fun notifyNeedsConfirmation(episodeId: String, bytes: Long) = notify(
        episodeId.hashCode(),
        "Descarga pesada",
        "Pesa ${FileSizeFormat.formatSize(bytes)}. Confírmala en Descargas para bajarla.",
    )

    private fun notify(id: Int, title: String, text: String, viewEpisodeId: String? = null) {
        ensureChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
        if (viewEpisodeId != null) {
            // Tapping the notice and tapping the button do the same thing: open THAT chapter.
            val view = viewIntent(viewEpisodeId)
            builder.setContentIntent(view)
                .addAction(android.R.drawable.ic_media_play, "Ver capítulo", view)
        }
        nm.notify(id, builder.build())
    }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "ArkivLocalDl"
        private const val CHANNEL_ID = "arkiv_local_downloads"
        private const val NOTIF_ID = 4711
        private const val PROGRESS_THROTTLE_MS = 1_000L
        private const val WORK_NAME = "arkiv_local_downloads"
        private const val RETRY_BACKOFF_SECONDS = 30L

        /**
         * Explicit backoff for transient failures' `Result.retry()`. Starts at 30s and doubles:
         * 30s, 1min, 2min… Enough for a flaky WiFi to come back, and short compared to how long a
         * several-GB download takes.
         */
        private fun request() = OneTimeWorkRequestBuilder<LocalDownloadWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .build()

        /**
         * KEEP and not REPLACE: if a pass is already running, queuing another download must not
         * kill it halfway. When it finishes, it re-queues itself (see [reschedule], which does use
         * REPLACE) and picks up the next one. This `schedule` is the EXTERNAL entry point (from
         * `LocalDownloadManager`); the internal self-relaunch doesn't go through here.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request(),
            )
        }

        /**
         * CUTS whatever is downloading right now and relaunches the queue from scratch.
         *
         * It's the mechanism `LocalDownloadManager.cancel/remove` use to actually stop a download
         * in progress: `REPLACE` cancels the unique work —including the one that's RUNNING, whose
         * coroutine receives the cancellation— and queues a new pass in the same act. Doing it in
         * two steps (`cancelUniqueWork` + `schedule` with KEEP) had a race: while the cancelled work
         * is still listed as RUNNING, KEEP is a no-op and the queue stayed asleep until the next
         * `enqueue`.
         *
         * The cancelled row has to be ALREADY deleted (or out of the queue) by the time this is
         * called, or the new pass picks it up again.
         */
        fun restart(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request(),
            )
        }
    }
}
