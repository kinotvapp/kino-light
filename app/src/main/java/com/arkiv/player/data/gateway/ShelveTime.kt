package com.arkiv.player.data.gateway

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When the Magis portal put a title on the shelf, and whether that was recent enough to call it new.
 *
 * The portal sends `shelveTime` as `yyyy-MM-dd HH:mm:ss` with no zone. It is read as China time (UTC+8), which is where
 * the portal is; if it were UTC instead, every date would come out up to 8 hours early, which moves the edge of the
 * 48-hour window and nothing else.
 */
object ShelveTime {
    /** A title uploaded within this long ago is marked as new. */
    const val NEW_WINDOW_MS = 48L * 60 * 60 * 1000

    /** The badge a plugin item carries, in its `badges` list, when it is new. */
    const val NEW_BADGE = "NUEVO"

    private val PORTAL_ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    /** [text] as epoch milliseconds, or 0 when it is missing or unreadable (0 means "unknown", never a date). */
    fun parse(text: String?): Long {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        // One formatter per call: SimpleDateFormat is not thread-safe, and the catalog is parsed off the main thread.
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = PORTAL_ZONE
            isLenient = false
        }
        return runCatching { format.parse(raw)?.time ?: 0L }.getOrDefault(0L)
    }

    /** Whether a title shelved at [shelvedAtMs] (0 = unknown) is new at [nowMs]. */
    fun isNew(shelvedAtMs: Long, nowMs: Long): Boolean = shelvedAtMs > 0 && nowMs - shelvedAtMs <= NEW_WINDOW_MS

    /** Whether the `|`-joined badges of a plugin item (its `extra["badges"]`) include the new mark. */
    fun hasNewBadge(joinedBadges: String?): Boolean = joinedBadges?.split('|')?.any { it == NEW_BADGE } == true
}
