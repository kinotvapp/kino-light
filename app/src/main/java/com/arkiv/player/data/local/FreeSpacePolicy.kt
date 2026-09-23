package com.arkiv.player.data.local

/**
 * Decides whether a download fits on disk. Pure (doesn't touch `StatFs`) so it can be tested
 * without Robolectric: `LocalDownloadManager` is the one that measures the real space.
 */
object FreeSpacePolicy {

    /** Margin to avoid leaving the device on the edge: other apps break before Arkiv does. */
    const val MARGIN_BYTES = 500L * 1024 * 1024

    /** [neededBytes] <= 0 means "not known yet": no point blocking over data that isn't there. */
    fun fits(availableBytes: Long, neededBytes: Long): Boolean {
        if (neededBytes <= 0) return true
        return availableBytes >= neededBytes + MARGIN_BYTES
    }
}
