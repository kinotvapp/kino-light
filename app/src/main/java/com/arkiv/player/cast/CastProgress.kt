package com.arkiv.player.cast

/** Position and duration that should be persisted for an episode currently being cast. */
data class SavedProgress(val positionMs: Long, val durationMs: Long)

/**
 * Clamps what the receiver reports to what makes sense to show or save.
 *
 * Without a transcoder the receiver counts the position and duration of the same file as the
 * phone, so there is no longer an offset to add. The only thing still needed is clamping
 * `C.TIME_UNSET` (a large negative, what a live stream that doesn't know its duration sends) to
 * "unknown" (0), which is what the bar and the progress save already interpret.
 */
object CastProgress {

    /**
     * CONTENT position to show on the bar. Unlike [toSave], something always has to be returned
     * here: the bar gets drawn either way.
     */
    fun contentPosition(receiverPosMs: Long): Long = receiverPosMs.coerceAtLeast(0)

    /**
     * CONTENT duration for the bar. Returns 0 when the receiver doesn't know it (a live stream
     * sends `TIME_UNSET`), which is what the bar already interprets as "no duration".
     */
    fun contentDuration(receiverDurMs: Long): Long = receiverDurMs.coerceAtLeast(0)

    /**
     * @param reportedPosMs position as reported by the receiver.
     * @param reportedDurMs duration as reported by the receiver (0 or negative for a live stream).
     * @return what to save, or null if there's nothing reliable to save.
     */
    fun toSave(reportedPosMs: Long, reportedDurMs: Long): SavedProgress? {
        if (reportedDurMs <= 0) return null
        if (reportedPosMs < 0) return null
        if (reportedPosMs >= reportedDurMs) return null
        return SavedProgress(reportedPosMs, reportedDurMs)
    }

    /**
     * [toSave] for a cast whose media begins [offsetMs] into the title (a remux started mid-title,
     * `CastRequest.offsetMs`): the receiver counts position AND duration from that point, so both
     * move onto the title's clock. [requestDurMs] is the media's own length as the load stated it,
     * for when the receiver reports none.
     */
    fun toSaveInTitle(receiverPosMs: Long, receiverDurMs: Long, requestDurMs: Long, offsetMs: Long): SavedProgress? {
        val mediaDur = if (receiverDurMs > 0L) receiverDurMs else requestDurMs
        if (mediaDur <= 0L) return null
        return toSave(receiverPosMs + offsetMs, mediaDur + offsetMs)
    }
}
