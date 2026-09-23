package com.arkiv.player.data.local

/**
 * A web download used to have TWO phases in series (the NUC would download from the origin, then
 * the device would download from the NUC), but the UI showed a single bar. Each phase took up
 * half, so the bar always moved forward instead of resetting to zero when the phase changed.
 *
 * Unused today: the web source and the NUC/arkiv-offline server were both removed in this
 * branch's pruning, and Magis downloads go straight device<->CDN with no staging phase.
 */
object StagingProgress {

    fun fromStaging(jobProgress: Float): Float = (jobProgress.coerceIn(0f, 1f)) * 0.5f

    fun fromTransfer(bytesDone: Long, totalBytes: Long): Float {
        if (totalBytes <= 0) return 0.5f
        val ratio = (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f)
        return 0.5f + ratio * 0.5f
    }
}
