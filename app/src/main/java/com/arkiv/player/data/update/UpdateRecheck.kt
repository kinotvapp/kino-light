package com.arkiv.player.data.update

/**
 * Whether the automatic OTA check is still owed: it is from the moment one fails or is skipped for want of a
 * network until one gets an answer (up to date or an update). While owed, the app checks again as soon as the
 * network comes back or the app comes to the front ([takeDue]), instead of waiting for the next periodic run
 * (3 h, and far more under Doze). On 0.9.50 that periodic wait left the people whose check failed -- most of
 * them offline at the moment it ran -- without ever hearing of 0.9.51.
 *
 * [minGapMs] keeps a flapping network or a person switching apps from firing a check every few seconds.
 */
class UpdateRecheck(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private var owed = false
    private var lastAttemptAt: Long? = null

    /** The check was not run: there is no network. Owed, and due at the next trigger. */
    @Synchronized
    fun skipped() {
        owed = true
    }

    /** A check is starting now (any trigger, the manual one included). */
    @Synchronized
    fun started() {
        lastAttemptAt = clock()
    }

    /** The check finished: [answered] = it reached a source (up to date or an update), false = every source failed. */
    @Synchronized
    fun finished(answered: Boolean) {
        owed = !answered
    }

    /** True, and marks the attempt as started, when a check is owed and the last one began [minGapMs] or more ago. */
    @Synchronized
    fun takeDue(): Boolean {
        val last = lastAttemptAt
        if (!owed || (last != null && clock() - last < minGapMs)) return false
        lastAttemptAt = clock()
        return true
    }

    companion object {
        const val MIN_GAP_MS = 2 * 60 * 1000L
    }
}