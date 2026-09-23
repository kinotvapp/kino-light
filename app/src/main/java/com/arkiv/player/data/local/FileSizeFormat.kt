package com.arkiv.player.data.local

import java.util.Locale

/**
 * Human-readable size format ("4.2 GB" / "480 MB"). Used to live in `TorrentSizeGate` (torrent was
 * removed in this branch's pruning); archive and magis downloads use it just the same, as does the
 * free-disk-space summary, so it's carried as a generic utility.
 */
object FileSizeFormat {
    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    }
}
