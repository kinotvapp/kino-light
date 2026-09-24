package com.arkiv.player.ui.home

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn

/**
 * Whether the home should ask the portal again: only when the last pass left a root out. A complete
 * home has nothing to gain from another trip, and every trip spends a turn of the portal's pacing.
 */
internal fun shouldRefetch(last: MagisHome): Boolean = last.missing.isNotEmpty()

/** Connectivity coming back: this going from false to true. Its first value is where it starts, not a change. */
fun Flow<Boolean>.reconnections(): Flow<Unit> = flow {
    var was: Boolean? = null
    collect { now ->
        if (was == false && now) emit(Unit)
        was = now
    }
}

/** Why the home is refetching: [Force] is a manual reload (always), [IfMissing] a reconnect/resume
 *  that only pays a trip when the last pass left a root out ([shouldRefetch]). */
enum class Refetch { Force, IfMissing }

/**
 * The home's Magis rows over time. On subscribe it paints the persisted [cached] snapshot INSTANTLY
 * (a cold start no longer shows a blank home while the portal answers), then refreshes only when
 * that snapshot is missing or older than [ttlMs]. After that it refetches on each [signals] pulse:
 * a [Refetch.Force] (the top-bar reload) always, a [Refetch.IfMissing] (connectivity back, home
 * resumed) only while the last pass left a root out.
 *
 * A refetch that comes back empty (portal down) never overwrites what's on screen -- the stale
 * cache stays -- except on the very first run with nothing cached, where the empty result is sent
 * to leave the loading state. [fetch] itself persists a complete pass (see [MagisHomeCatalog.load]).
 *
 * [signals] is listened to from the start, not after the first pass: a pulse that arrives mid-pass
 * is kept (conflated to one) and weighed when the pass lands. Otherwise connectivity that came back
 * while a doomed request was still timing out would be lost -- by then it's just "online".
 */
fun magisHomeRows(
    cached: suspend () -> CachedRows?,
    fetch: suspend () -> MagisHome,
    signals: Flow<Refetch>,
    ttlMs: Long = MagisHomeCatalog.TTL_MS,
    now: () -> Long = System::currentTimeMillis,
): Flow<List<MagisHomeRow>> = channelFlow {
    val queue = signals.buffer(Channel.CONFLATED).produceIn(this)
    val snap = cached()
    if (snap != null) send(snap.rows)
    var last: MagisHome? = null
    if (snap == null || now() - snap.fetchedAt >= ttlMs) {
        last = fetch()
        if (last.rows.isNotEmpty() || snap == null) send(last.rows)
    }
    for (signal in queue) {
        val doFetch = when (signal) {
            Refetch.Force -> true
            Refetch.IfMissing -> last?.let { shouldRefetch(it) } ?: false
        }
        if (doFetch) {
            last = fetch()
            if (last.rows.isNotEmpty()) send(last.rows)
        }
    }
}
