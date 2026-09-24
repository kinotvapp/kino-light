package com.arkiv.player.data.local

import android.content.Context
import com.arkiv.player.playback.RemuxPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Plain file helpers, with no Android in them so the JVM tests can drive them on a temp folder. */
object StorageFiles {

    /** Total bytes of every file under [file] (itself, if it is one). 0 if it doesn't exist. */
    fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    /**
     * Deletes everything INSIDE [dir] and keeps [dir] itself (whoever owns it expects it to be
     * there). Best-effort: what can't be deleted is left and simply not counted. Returns the bytes
     * actually freed.
     */
    fun clearContents(dir: File): Long {
        var freed = 0L
        dir.listFiles().orEmpty().forEach { child ->
            val size = sizeOf(child)
            val deleted = runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }.getOrDefault(false)
            if (deleted) freed += size
        }
        return freed
    }
}

/** What Kino takes up, split by what the person can do about it. */
data class StorageUsage(
    /** Saved movies/episodes, half-finished `.part` files and their subtitles: only the person can decide to delete these. */
    val downloadsBytes: Long,
    /** Regenerable files (see [AppStorage.clearCache]): safe to drop, the app just re-creates them. */
    val cacheBytes: Long,
    /** Free space on the disk the downloads live on. */
    val freeBytes: Long,
)

/**
 * Space management for the Settings screens (phone and TV): shows what Kino takes up and lets the
 * person free it, for the ones who already have the app installed and no other way to do it.
 *
 * Two deliberately different actions:
 *  - [clearCache]: only what the app re-creates on its own. No confirmation needed.
 *  - [deleteAllDownloads]: the person's saved videos. Destructive, so the UI asks first.
 *
 * "Cache" here is EXACTLY the set [clearCache] empties, not everything under `cacheDir`: the number
 * shown must be what the button really frees, and other libraries keep files in there that aren't
 * ours to delete while they're in use.
 */
class AppStorage(
    context: Context,
    /** Where downloads live (`LocalDownloadManager.targetDir`). Also holds the Caracol cache. */
    private val downloadsDir: () -> File,
    /** Coil's image caches, emptied through its own API: deleting its files underneath it corrupts its journal. */
    private val clearImageCaches: () -> Unit,
    /** The Chromecast remux copies (`TsRemuxer.clear`). */
    private val clearRemux: () -> Unit,
    /** `LocalDownloadManager.removeAll`. */
    private val removeAllDownloads: suspend () -> Unit,
) {
    private val cacheDir: File = context.applicationContext.cacheDir

    /** The regenerable files: image covers, Chromecast remuxes, the archive proxy's tails, a downloaded update. */
    private fun regenerable(): List<File> = listOf(
        File(cacheDir, IMAGE_CACHE_DIR),
        File(cacheDir, RemuxPolicy.FOLDER),
        File(cacheDir, ARCHIVE_CACHE_DIR),
        File(cacheDir, UPDATE_APK),
    )

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val downloads = downloadsDir()
        StorageUsage(
            downloadsBytes = StorageFiles.sizeOf(downloads),
            cacheBytes = regenerable().sumOf { StorageFiles.sizeOf(it) },
            freeBytes = downloads.usableSpace,
        )
    }

    /** Empties the regenerable files. Returns the bytes actually freed. */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = regenerable().sumOf { StorageFiles.sizeOf(it) }
        runCatching { clearImageCaches() }
        runCatching { clearRemux() }
        StorageFiles.clearContents(File(cacheDir, ARCHIVE_CACHE_DIR))
        runCatching { File(cacheDir, UPDATE_APK).delete() }
        (before - regenerable().sumOf { StorageFiles.sizeOf(it) }).coerceAtLeast(0L)
    }

    /** Deletes every download, finished or not, with its subtitles. The UI must have asked first. */
    suspend fun deleteAllDownloads() = removeAllDownloads()

    companion object {
        /** Folder names under `cacheDir`, shared with whoever creates them so the two can't drift apart. */
        const val IMAGE_CACHE_DIR = "image_cache"
        const val ARCHIVE_CACHE_DIR = "archive-cache"
        const val UPDATE_APK = "update.apk"
    }
}
