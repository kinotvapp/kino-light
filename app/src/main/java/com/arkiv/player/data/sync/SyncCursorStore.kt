package com.arkiv.player.data.sync

import android.content.Context

/**
 * Per-peer, per-table pull cursor: how far into a peer's `updatedAt` timeline we've already
 * pulled its rows. The sync engine reads this before asking a peer for rows and advances it
 * after applying a page, so a restart doesn't re-pull what's already applied.
 *
 * Push isn't cursor-tracked here: incremental push is driven by the DB invalidation tick, and the
 * initial catch-up uses the peer's own `since` from its `sync_hello`.
 */
class SyncCursorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_companion_sync", Context.MODE_PRIVATE)

    fun pulled(peerId: String, table: String): Long = prefs.getLong(key(peerId, table), 0)

    fun setPulled(peerId: String, table: String, v: Long) {
        prefs.edit().putLong(key(peerId, table), v).apply()
    }

    private fun key(peerId: String, table: String) = "$peerId|$table"
}
