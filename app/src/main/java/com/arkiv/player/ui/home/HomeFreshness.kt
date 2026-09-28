package com.arkiv.player.ui.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/**
 * When Home asks for its rows again, as pure rules (the ViewModel, the screens and AppGraph only
 * apply them). Rate safety: only the top bar's "Recargar" ever forces a pass past the caches, and at
 * most once per [FORCED_RELOAD_DEBOUNCE_MS]; every other refresh (resume, the visible-Home timer,
 * back online) goes through the normal TTLs, so it reaches the portal only when a cache has expired.
 */
object HomeFreshness {
    /** Presses of "Recargar" closer than this to the last accepted one are ignored. */
    const val FORCED_RELOAD_DEBOUNCE_MS = 60_000L

    /** A Home whose last settled pass is older than this re-asks on resume (never forced). */
    const val RESUME_STALE_AFTER_MS = 30 * 60_000L

    /** How often a visible, resumed Home (a TV left on) checks [shouldReloadOnResume]. Only a clock
     *  comparison: the reload itself still needs [RESUME_STALE_AFTER_MS] to have passed. */
    const val VISIBLE_CHECK_EVERY_MS = 5 * 60_000L

    /** How long a partial catalog pass (a root missing) is reused before the missing roots are retried. */
    const val PARTIAL_PASS_TTL_MS = 5 * 60_000L

    /**
     * Whether a resumed (or still visible) Home should re-ask for its rows: its last settled pass is
     * at least [RESUME_STALE_AFTER_MS] old. `null` = a pass is in flight (or never started): never.
     * A clock that went backwards counts as stale rather than freezing Home until it catches up.
     */
    fun shouldReloadOnResume(lastPassAt: Long?, now: Long): Boolean {
        if (lastPassAt == null) return false
        return now < lastPassAt || now - lastPassAt >= RESUME_STALE_AFTER_MS
    }

    /** Whether a forced reload may go ahead, given when the last ACCEPTED one happened. */
    fun acceptForcedReload(lastForcedAt: Long?, now: Long): Boolean =
        lastForcedAt == null || now < lastForcedAt || now - lastForcedAt >= FORCED_RELOAD_DEBOUNCE_MS

    /** One event per offline -> online transition of [online]; starting online is not one. */
    fun backOnline(online: Flow<Boolean>): Flow<Unit> =
        online.distinctUntilChanged()
            .runningFold(null as Pair<Boolean?, Boolean>?) { prev, now -> prev?.second to now }
            .drop(1)
            .filter { it != null && it.first == false && it.second }
            .map { }
}

/** [HomeFreshness.acceptForcedReload] with its state: the time of the last accepted forced reload. */
class ForcedReloadGate(private val now: () -> Long = System::currentTimeMillis) {
    private var lastForcedAt: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        if (!HomeFreshness.acceptForcedReload(lastForcedAt, t)) return false
        lastForcedAt = t
        return true
    }
}
