package com.arkiv.player.data.local

import com.arkiv.player.data.caracol.CaracolDownload
import com.arkiv.player.data.db.ArkivDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only thing the player checks to know if an episode is saved on the device.
 *
 * Verifies the file actually EXISTS, not just that the row says `completed`: if the user deleted
 * it from Android's settings, without this check the player would point at a ghost file and show
 * a black screen with no explanation.
 */
class LocalLibrary(
    private val db: ArkivDatabase,
    /**
     * The player is about to open [episodeId]'s download at a path: `Mp4Prep.onOpened` (the file is
     * in use, and an older download gets its MP4 lazily). Never waits.
     */
    private val onOpened: (episodeId: String, path: String) -> Unit = { _, _ -> },
) {

    private val downloadDao = db.downloadDao()

    /** [pathFor], for the player: the file it is about to open is reported to [onOpened]. */
    suspend fun fileFor(episodeId: String): String? =
        pathFor(episodeId)?.also { path -> runCatching { withContext(Dispatchers.IO) { onOpened(episodeId, path) } } }

    /** The completed download's file for [episodeId], checked to exist (see the class KDoc), or null. */
    suspend fun pathFor(episodeId: String): String? = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext null
        if (row.state != LocalDownloadState.COMPLETED) return@withContext null
        // Caracol does NOT go through here. What its download leaves in `filePath` isn't a video
        // but the download's record (a JSON: see `DituDownloadStrategy`), because its bytes are
        // encrypted DASH segments inside media3's cache and not a playable file. Handing it over
        // as if it were one sends the local-file player to open a JSON: black screen and no error
        // explaining it. The one that knows how to open it is `caracolDownload`, used by
        // Caracol's player.
        if (row.source == CARACOL_SOURCE) return@withContext null

        // filePath is the new one; localUri is the `file://` that downloads made with the system's
        // DownloadManager left before migration 16->17. Both are still valid.
        val path = row.filePath ?: row.localUri?.removePrefix("file://") ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            // The file is gone: clear the row so the UI doesn't keep saying "listo" and so the
            // next play falls back to streaming instead of failing.
            downloadDao.delete(episodeId)
            return@withContext null
        }
        file.absolutePath
    }

    /**
     * The record of a downloaded Caracol chapter, or `null` if it's not on the device.
     *
     * Returns what's needed to OPEN IT: which URL filled the cache and which quality was
     * downloaded. See [CaracolDownload], which explains why neither of the two can be guessed
     * afterward.
     *
     * Same as [fileFor], checks that what's on disk still exists: if the person cleared the app's
     * data from outside, the row gets cleared and the next play falls back to streaming instead of
     * failing.
     */
    suspend fun caracolDownload(episodeId: String): CaracolDownload? = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext null
        if (row.state != LocalDownloadState.COMPLETED || row.source != CARACOL_SOURCE) return@withContext null
        val path = row.filePath ?: return@withContext null
        val record = File(path)
        if (!record.exists()) {
            downloadDao.delete(episodeId)
            return@withContext null
        }
        val data = runCatching { CaracolDownload.fromJson(record.readText()) }.getOrNull()
        if (data == null) {
            // An unreadable record is a download that can't be opened. The row gets cleared so the
            // UI stops promising something that won't work, and it can be downloaded again.
            downloadDao.delete(episodeId)
            return@withContext null
        }
        data
    }

    private companion object {
        /** The value of `downloads.source` for Caracol. See [com.arkiv.player.data.local.DownloadSource]. */
        const val CARACOL_SOURCE = "ditu"
    }
}
