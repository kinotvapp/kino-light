package com.arkiv.player.data.local

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
}