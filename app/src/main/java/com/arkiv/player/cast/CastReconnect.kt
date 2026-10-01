package com.arkiv.player.cast

/**
 * What a session (re)connect may replay of the last request.
 *
 * The request is remembered so a session that shows up late still gets loaded, but it carries a
 * URL into one of this device's LAN servers, and those are torn down with the session that used
 * them: replaying it after a reconnect handed the receiver a dead token (`-> 404 …/media.m3u8`) or
 * a closed port (`ERR_CONNECTION_REFUSED`), with the start position of the request before that
 * one, while the right load only arrived 30-40 s later (measured 2026-10-01 on the KALLEY).
 */
object CastReconnect {

    /**
     * The request to load on a (re)connect, or null when there is nothing safe to replay: nothing
     * pending, or its URL is no longer served ([stillServed] false) -- whoever owns the title then
     * builds a fresh one from the current state. A replay resumes at [lastKnownMs], the last
     * position the receiver reported for it, unless it is live (no position to resume).
     */
    fun replay(pending: CastRequest?, stillServed: (String) -> Boolean, lastKnownMs: Long?): CastRequest? {
        pending ?: return null
        if (!stillServed(pending.uri)) return null
        if (pending.asLive || pending.durationMs <= 0L || lastKnownMs == null || lastKnownMs <= 0L) return pending
        return pending.copy(startPositionMs = lastKnownMs)
    }
}
