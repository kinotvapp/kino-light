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

/**
 * The home's Magis rows over time: one pass as soon as it's collected, then another each time
 * [retry] fires while the last pass left a root out ([shouldRefetch]). So a cold start with no
 * network yet doesn't hide a whole kind until the process dies, and a complete home never asks
 * again.
 *
 * [retry] is listened to from the start, not after the first pass: a signal that arrives mid-pass
 * is kept (conflated to one) and weighed when the pass lands. Otherwise connectivity that came back
 * while a doomed request was still timing out would be lost -- by then it's just "online".
 */
fun magisHomeRows(fetch: suspend () -> MagisHome, retry: Flow<Unit>): Flow<List<MagisHomeRow>> = channelFlow {
    val signals = retry.buffer(Channel.CONFLATED).produceIn(this)
    var last = fetch()
    send(last.rows)
    for (signal in signals) {
        if (shouldRefetch(last)) {
            last = fetch()
            send(last.rows)
        }
    }
}
