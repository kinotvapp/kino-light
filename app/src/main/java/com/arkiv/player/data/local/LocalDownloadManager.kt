package com.arkiv.player.data.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.DownloadEntity
import com.arkiv.player.data.db.DownloadRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** What happened on enqueue. The UI only needs to tell "it got queued" apart from "you already had it". */
enum class EnqueueOutcome {
    QUEUED,

    /** There was already a row in progress (queued, downloading, …) for THIS episode. */
    ALREADY_QUEUED,

    /** That content is already on the device: this episode, or its twin under another item. */
    ALREADY_DOWNLOADED,
}

/**
 * Facade for on-device downloads: the only thing the UI touches. Queues in Room and wakes the
 * worker; doesn't download anything on its own.
 */
class LocalDownloadManager(
    context: Context,
    db: ArkivDatabase,
    /** Injected so it can be tested without WorkManager; in production it's `LocalDownloadWorker::schedule`. */
    private val wakeWorker: (Context) -> Unit,
    /**
     * Cuts the pass currently running and relaunches the queue. In production it's
     * `LocalDownloadWorker::restart`. It's the only thing that can actually stop an in-progress
     * download: the facade has no way to talk to the strategy running inside the worker.
     */
    private val restartWorker: (Context) -> Unit,
    /**
     * The strategies, so they can be asked to clean up their own mess when a download gets removed
     * (see [DownloadStrategy.clearLeftovers]). Goes as a lambda and not as a map to break the cycle
     * with `AppGraph`: the strategies need the repository, which gets built after this.
     */
    private val strategies: () -> Map<String, DownloadStrategy> = { emptyMap() },
) {
    private val appContext = context.applicationContext
    private val downloadDao = db.downloadDao()
    private val itemDao = db.itemDao()

    /** `Android/data/<pkg>/files/Movies`. Falls back to filesDir if no external storage is mounted. */
    fun targetDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(appContext.filesDir, "Movies"))
            .apply { mkdirs() }

    fun observeRows(): Flow<List<DownloadRow>> = downloadDao.observeDownloadRows()

    /** Measures the real disk and delegates the decision to [FreeSpacePolicy], which is the testable part. */
    fun hasFreeSpaceFor(bytes: Long): Boolean =
        FreeSpacePolicy.fits(StatFs(targetDir().absolutePath).availableBytes, bytes)

    /**
     * Bytes available on the disk where downloads live. Same measurement [hasFreeSpaceFor] uses,
     * exposed so it can be SHOWN: the TV library needs it so a full disk stops being a surprise.
     * Blocking (touches the filesystem), so it's called off the main thread or inside a
     * `produceState`.
     */
    fun freeSpaceBytes(): Long = StatFs(targetDir().absolutePath).availableBytes

    /**
     * Queues an episode. Idempotent: if there's already a row that hasn't failed, it does nothing —
     * so tapping the button twice doesn't duplicate the download.
     *
     * And one step further: it also doesn't queue if THAT SAME CONTENT is already downloaded under
     * another library item (the same series saved twice, see [DuplicateDownloadPolicy]). That
     * avoids downloading the same gigabytes twice even with the duplicate items that already exist,
     * which don't get migrated. The result tells the caller what happened so the UI can let the
     * user know they already have it (see `DuplicateDownloadPolicy.skippedNotice`).
     */
    suspend fun enqueue(episodeId: String, source: String): EnqueueOutcome = withContext(Dispatchers.IO) {
        val existing = downloadDao.get(episodeId)
        if (existing != null && existing.state != LocalDownloadState.FAILED) {
            return@withContext if (existing.state == LocalDownloadState.COMPLETED) {
                EnqueueOutcome.ALREADY_DOWNLOADED
            } else {
                EnqueueOutcome.ALREADY_QUEUED
            }
        }
        val target = EpisodeOrigin(episodeId, itemDao.getEpisode(episodeId)?.torrentFileIndex)
        val twin = DuplicateDownloadPolicy.completedDuplicateOf(target, downloadDao.completedOrigins())
        if (twin != null) return@withContext EnqueueOutcome.ALREADY_DOWNLOADED
        downloadDao.upsert(
            DownloadEntity(
                episodeId = episodeId,
                variant = "",
                state = LocalDownloadState.QUEUED,
                progress = 0f,
                localUri = null,
                bytes = 0,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        )
        wakeWorker(appContext)
        EnqueueOutcome.QUEUED
    }

    /** The user accepted a download that exceeded the size threshold. Legacy of the torrent gate (source removed in this branch's pruning): nothing triggers this state today. */
    suspend fun confirmSize(episodeId: String) = withContext(Dispatchers.IO) {
        downloadDao.markConfirmed(episodeId)
        wakeWorker(appContext)
    }

    /**
     * Re-queues a failed row. Any `.part` left behind is kept on purpose: the downloader resumes
     * from there with `Range` instead of starting from zero.
     */
    suspend fun retry(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext
        if (!DownloadQueuePolicy.isRetryable(row.state)) return@withContext
        // An old row might be left pointing at the wrong strategy (see [DownloadSource]);
        // re-queuing it as-is would make it fail with the same message forever.
        val source = DownloadSource.sourceFor(episodeId)
        if (row.source != source) downloadDao.updateSource(episodeId, source)
        downloadDao.updateState(episodeId, LocalDownloadState.QUEUED, null)
        wakeWorker(appContext)
    }

    /**
     * STOPS an in-progress download without deleting anything: the row is left `failed` with reason
     * "Cancelada" and the `.part` intact, so "Reintentar" resumes from where it left off instead of
     * starting from zero.
     *
     * How the signal reaches the strategy: there's no direct channel to the worker, so the whole
     * worker gets cut ([restartWorker], which is an `enqueueUniqueWork` with REPLACE). The coroutine
     * receives the cancellation and the HTTP downloader breaks its write loop at its
     * `ensureActive()`. The new pass that REPLACE queues up picks up the next row in the queue.
     *
     * Only cuts if this row is the one in flight: the queue is one at a time, so a row in
     * `downloading`/`staging` IS the one running, and one in `queued` isn't running anything
     * (cutting for it would kill someone else's download that's actually in progress).
     */
    suspend fun cancel(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext
        if (DownloadQueuePolicy.isTerminal(row.state)) return@withContext
        val inFlight = row.state == LocalDownloadState.DOWNLOADING || row.state == LocalDownloadState.STAGING
        // The state is written BEFORE cutting: otherwise the new pass finds the row still in
        // `downloading` and picks it up again immediately (nextToProcess prefers what's already started).
        downloadDao.updateState(episodeId, LocalDownloadState.FAILED, "Cancelada")
        if (inFlight) restartWorker(appContext)
    }

    /**
     * Deletes the row and the file (and the partial, if it was left halfway). If the download is
     * running, it STOPS it first: without that, the strategy kept working on a row that no longer
     * exists — the HTTP downloader kept spending mobile data writing to an already-unlinked inode.
     */
    suspend fun remove(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId)
        val inFlight = row != null &&
            (row.state == LocalDownloadState.DOWNLOADING || row.state == LocalDownloadState.STAGING)
        // Deliberate order: 1) take the row out of the queue, 2) cut the worker, 3) only then
        // delete the files. Deleting first would let the new pass pick the row back up; cutting
        // without deleting the row would do the same. There's a minimal window where the strategy
        // hasn't heard about the cancellation yet and can recreate its working directory: it's
        // benign, because the strategy itself cleans up its own mess on exit and the final file
        // never gets produced.
        downloadDao.delete(episodeId)
        if (inFlight) restartWorker(appContext)
        // Whatever isn't a file in `targetDir` gets deleted by whoever wrote it: for Caracol that's
        // the segments inside media3's shared cache, which no sweep by name reaches. Goes BEFORE
        // deleting the record by prefix, because that record is what says which cache bytes belong
        // to this chapter.
        row?.source?.let { source ->
            runCatching { strategies()[source]?.clearLeftovers(episodeId, targetDir()) }
        }
        val path = row?.filePath ?: row?.localUri?.removePrefix("file://")
        // Two rows can share the SAME file: when the worker finds that content was already on disk
        // under another item, it adopts the twin's file instead of re-downloading it (see
        // LocalDownloadWorker.adoptTwinIfAlreadyDownloaded). Deleting it from EITHER of the two
        // would leave the other saying "Listo" over a file that's no longer there.
        //
        // The filter applies to BOTH deletion paths below, not just the explicit one: the prefix
        // sweep deletes by NAME, and the shared file is named after the ORIGINAL twin's episodeId.
        // Meaning removing the adopter effectively doesn't touch it, but removing the original
        // would sweep it even if the explicit delete had skipped it — the file would vanish and the
        // adopter would be left lying. See DuplicateDownloadPolicy.deletablePaths.
        val referenced = downloadDao.filePathsReferencedByOthers(episodeId).toSet()
        if (path != null && DuplicateDownloadPolicy.canDeleteFile(path, referenced)) {
            // Covers the exact name old (pre-migration) downloads left, which might not follow the
            // sanitize(episodeId) + extension pattern LocalFilePaths.fileNameFor builds.
            val file = File(path)
            runCatching { file.delete() }
            runCatching { LocalFilePaths.partOf(file).delete() }
            runCatching { LocalFilePaths.originOf(file).delete() }
        }
        // Prefix sweep: for archive/web the target name is deterministic
        // (LocalFilePaths.fileNameFor = sanitize(episodeId) + extension), so this covers the final
        // file AND the ".part" (and its origin mark ".part.src") even if the row doesn't have a
        // filePath yet (QUEUED/DOWNLOADING, which is when the user most often taps "Quitar").
        // Without this the .part is left orphaned: nobody else references it or cleans it up, and
        // it eats up exactly the disk FreeSpacePolicy protects. The .part files are never another
        // row's filePath, so the shared-file filter changes nothing for them.
        val prefix = "${LocalFilePaths.sanitize(episodeId)}."
        runCatching {
            val candidates = targetDir().listFiles { f -> f.name.startsWith(prefix) }.orEmpty()
                .map { it.absolutePath }
            DuplicateDownloadPolicy.deletablePaths(candidates, referenced)
                .forEach { p -> runCatching { File(p).delete() } }
        }
    }
}
