package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.gateway.LiveChannel

/**
 * Builds the home's "recent live channels" row (phone and TV): crosses what was last watched
 * ([recent], ALREADY sorted by `vistoAt DESC` -- see `LiveRecentDao.flowRecent`, this function does
 * NOT reorder) with the local channel cache ([cache], indexed by `code`) to fill in logo and
 * number, which `live_recents` doesn't store.
 *
 * Why cross-reference the cache instead of storing logo/number in `live_recents` directly: that
 * table used to sync through PocketBase (`cloudsync/CloudSyncManager`, both removed with this
 * branch's pruning) -- back then, adding presentation-only columns meant touching the schema,
 * writing a Room migration, AND deciding whether that field should travel between devices. Cloud
 * sync is gone now, but the schema/migration cost of adding a column is unrelated to that and
 * still applies -- and `live_channels_cache` (which ALREADY stores logo/numero, and never needed
 * to sync since it's a reconstructible cache) solves the same need without touching any of that.
 * The cost of this shortcut is that the cache is keyed by the portal's category and may not have
 * one specific channel (one seen recently whose category was never reloaded): that's why, when
 * `code` doesn't show up in [cache], the result falls back to `numero = 0` and `logo = null` --
 * "unknown" values, not an error. Whoever paints the card treats `logo == null` with the same
 * criterion `ChannelCard` in `LiveScreen.kt` already uses (grayed out + large text instead of a
 * broken logo), except there not even the number is known, so the card falls back further, to
 * the name's initials.
 */
fun recentChannelsForHome(
    recent: List<LiveRecentEntity>,
    cache: Map<String, LiveChannelCacheEntity>,
): List<LiveChannel> = recent.map { r ->
    val cached = cache[r.code]
    LiveChannel(
        code = r.code,
        // The name comes from `recent`, not from the cache: it's the one the user saw when
        // opening the channel, and stays valid even if that category's cache is stale or absent.
        name = r.nombre,
        number = cached?.numero ?: 0,
        logo = cached?.logo,
    )
}
