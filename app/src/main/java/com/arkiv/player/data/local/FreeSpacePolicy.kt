package com.arkiv.player.data.local

import java.io.IOException

/**
 * Decides whether a download fits on disk. Pure (doesn't touch `StatFs`) so it can be tested
 * without Robolectric: whoever writes the bytes ([HttpRangeDownloader]) is the one that measures the
 * real space and asks this.
 *
 * It is ENFORCED there, both before the first byte (the declared size against the disk) and every
 * [CHECK_EVERY_BYTES] while writing. It used to exist only as a tested-but-uncalled function, so a
 * download simply wrote until Android refused -- and on a small TV box "Android refused" means every
 * app failing and the box rebooting for a full disk (the reported case: 6 GB of Kino's data).
 */
object FreeSpacePolicy {

    /** Margin to avoid leaving the device on the edge: other apps break before Arkiv does. */
    const val MARGIN_BYTES = 500L * 1024 * 1024

    /**
     * How many bytes a running download writes between two measurements of the disk. Measuring on
     * every 64 KB buffer would hit the filesystem thousands of times per movie; the margin is
     * hundreds of MB, so re-checking every few MB can't overshoot it.
     */
    const val CHECK_EVERY_BYTES = 8L * 1024 * 1024

    /** [neededBytes] <= 0 means "not known yet": no point blocking over data that isn't there. */
    fun fits(availableBytes: Long, neededBytes: Long): Boolean {
        if (neededBytes <= 0) return true
        return availableBytes >= neededBytes + MARGIN_BYTES
    }

    /**
     * The disk is already at or under the [MARGIN_BYTES] reserve: NOTHING more may be written, no
     * matter how small. Covers what [fits] deliberately lets through (a size that isn't known yet).
     */
    fun isExhausted(availableBytes: Long): Boolean = availableBytes < MARGIN_BYTES
}

/**
 * A download stopped because the disk has no room left (see [FreeSpacePolicy]). Typed so
 * [DownloadRetryPolicy] can classify it as DEFINITIVE: retrying only writes more onto a disk that
 * is already full. The message is what the Downloads screen shows, so it says what to do.
 */
class InsufficientSpaceException(val availableBytes: Long) : IOException(
    "No hay espacio suficiente en el dispositivo (quedan ${FileSizeFormat.formatSize(availableBytes)}). " +
        "Libera espacio y toca Reintentar.",
)
