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
     * builds a fresh one from the current state. A replay resumes at the best position known
     * ([resumeAt]), unless it is live (no position to resume).
     */
    fun replay(pending: CastRequest?, stillServed: (String) -> Boolean, lastKnownMs: Long?, savedMs: Long? = null): CastRequest? {
        pending ?: return null
        if (!stillServed(pending.uri)) return null
        if (pending.asLive || pending.durationMs <= 0L) return pending
        val at = resumeAt(pending, lastKnownMs, savedMs)
        return if (at == pending.startPositionMs) pending else pending.copy(startPositionMs = at)
    }

    /**
     * Where a replay of [pending] starts, freshest first: [lastKnownMs], the receiver's last
     * report; the request's own start when it was a real position; [savedMs], the progress saved
     * for the title. Only with none of them, the request's start as it was. A load "from the top"
     * ([CastIdleWatch.TOP_MS]) is not a position: it replayed a title the TV had been playing for
     * a while from 0:00 whenever the receiver dropped before reporting.
     */
    fun resumeAt(pending: CastRequest, lastKnownMs: Long?, savedMs: Long?): Long =
        lastKnownMs?.takeIf { it > CastIdleWatch.TOP_MS }
            ?: pending.startPositionMs.takeIf { it > CastIdleWatch.TOP_MS }
            ?: savedMs?.takeIf { it > CastIdleWatch.TOP_MS }
            ?: pending.startPositionMs

    /** Is the saved progress worth reading for a replay of [pending] ([resumeAt] would use it)? */
    fun needsSaved(pending: CastRequest, lastKnownMs: Long?): Boolean =
        !pending.asLive && pending.durationMs > 0L &&
            (lastKnownMs ?: 0L) <= CastIdleWatch.TOP_MS && pending.startPositionMs <= CastIdleWatch.TOP_MS
}
