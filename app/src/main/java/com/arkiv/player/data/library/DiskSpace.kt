package com.arkiv.player.data.library

import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.FileSizeFormat

/**
 * What the TV's "Downloads" section shows at the very top.
 *
 * Exists because on a Fire TV Stick the disk is small and fills up without warning: saving a
 * whole season queues N downloads and until now there was no screen on the TV to see it.
 */
object DiskSpace {

    /**
     * Bytes already written by downloads, partials included (`bytesDone`, not `bytes`): what
     * matters is what it occupies on disk NOW, not what it will occupy once finished.
     *
     * Can overcount in one known case: when two rows share the same file because one adopted its
     * twin's (see `LocalDownloadWorker.adoptTwinIfAlreadyDownloaded`), the bytes get counted
     * twice. Accepted: it's an informational number, not a decision, and what decides whether a
     * download fits is still `FreeSpacePolicy` measuring the actual disk.
     */
    fun usedByDownloads(groups: List<DownloadGroup>): Long =
        groups.sumOf { group ->
            group.episodes.sumOf { ep ->
                (ep.status as? EpisodeDownloadStatus.Tracked)?.row?.bytesDone ?: 0L
            }
        }

    /**
     * "12.0 GB libres  ·  3.0 GB en descargas". With nothing downloaded, omits the second clause
     * instead of showing a "0 MB" that tells nobody anything.
     *
     * Uses the formatter that already exists (`FileSizeFormat.formatSize`) instead of its own: two
     * different size formats in the same app stand out.
     */
    fun summary(freeBytes: Long, usedBytes: Long): String {
        val free = "${FileSizeFormat.formatSize(freeBytes)} libres"
        return if (usedBytes <= 0) free
        else "$free  ·  ${FileSizeFormat.formatSize(usedBytes)} en descargas"
    }
}
