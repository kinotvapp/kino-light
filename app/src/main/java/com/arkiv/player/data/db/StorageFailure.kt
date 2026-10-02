package com.arkiv.player.data.db

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import java.io.IOException

/**
 * A failure that only says the device has no room (or no writable data dir) right now: the database
 * can't be opened or written, the disk is full. ERRORES-AL6: on a device in DEVICE_STORAGE_LOW the
 * `databases/` dir could not even be created, and the startup purge's first query hit
 * SQLITE_CANTOPEN. Optional startup housekeeping skips on one of these (and tries again on the next
 * start) instead of reporting it as a bug of ours.
 */
internal object StorageFailure {
    /** Below this much free space in the app's data dir, optional startup writes are not attempted. */
    const val MIN_FREE_BYTES = 16L * 1024 * 1024

    fun isNoRoom(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any { e ->
        e is SQLiteCantOpenDatabaseException || e is SQLiteFullException || e is SQLiteDiskIOException ||
            (e is IOException && e.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) })
    }

    /** Whether optional startup housekeeping should even try: a negative [usableBytes] (unknown) still tries. */
    fun roomFor(usableBytes: Long): Boolean = usableBytes < 0L || usableBytes >= MIN_FREE_BYTES
}
