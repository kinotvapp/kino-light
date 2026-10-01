package com.arkiv.player.playback

import kotlinx.coroutines.delay
import java.io.File

/**
 * The wait before a growing remux goes to a TV, the SAME for every protocol (Chromecast and DLNA):
 * once a second, get the remux ready on [RemuxHlsServer] as soon as the new run has written
 * anything (an earlier cast's leftover, see [RemuxLeftover], joins it once its header vouched for
 * it), pace it against where the TV will start ([RemuxHlsServer.planStart]) and hand the TV over
 * once it covers the phone's position with [RemuxHls.START_LEAD_SEC] to spare -- or, past
 * [RemuxHls.RESUME_WAIT_SEC], the furthest point it covers before it ([RemuxHls.castStart]): never
 * back at 0:00 because the wait ran out.
 *
 * How the TV is then told to start there is the protocol's business ([start]): the Chromecast loads
 * the playlist with that position, a DLNA renderer gets the playlist (whose `#EXT-X-START` says it)
 * and a `Seek` to it.
 */
object RemuxCastStart {

    /** Ticks of [TICK_MS] the wait lasts at most: the Chromecast's ten minutes. */
    const val MAX_TICKS = 600
    const val TICK_MS = 1_000L

    /**
     * The point the TV can start from. [url] is the remux's master playlist on the LAN (null without
     * a LAN address); [fromMs] where to start; [readySec] how much of the title is playable now.
     */
    data class Start(val url: String?, val fromMs: Long, val readySec: Double)

    /**
     * Waits for the remux of [key] to cover [wantedMs], then offers it to [start], which says
     * whether it took it (false keeps waiting, as when the cast request could not be built yet).
     * Ends early, with false, once [stillWanted] says the cast moved on. True once [start] took one.
     *
     * The remux is only STAGED on [server] while it waits ([RemuxHlsServer.stage]): a TV still
     * playing another remux of the title (another audio) keeps it until [start] took this one,
     * which is when this one is served ([RemuxHlsServer.serve]) and the other retired. A wait that
     * ran out stops pacing it against a TV that never came; one given up leaves it to whatever moved
     * on ([RemuxHlsServer.stage] of another, [RemuxHlsServer.unstage], [RemuxHlsServer.endCast]).
     *
     * Nothing is offered before the new run has written something ([inProgress]), even when an
     * earlier cast's leftover covers the position: the server only serves that leftover once the
     * new run's header vouched for it, and the Chromecast's request decides "remux or TS playlist"
     * by that same [inProgress] -- offering the leftover alone sent the TV the TS playlist and left
     * the remux paced against it forever (review 2026-10-01). It costs a re-cast ~13 s.
     *
     * [wantedNow] is where the TV should start, asked on every tick: a TV already on the title
     * (another audio) keeps playing meanwhile, so the point moves with it. Past [maxWaitSec] the
     * furthest point covered is taken ([RemuxHls.castStart]); a TV that keeps playing passes
     * [Int.MAX_VALUE] and waits instead of jumping back minutes.
     *
     * [lan] and [diagPrefix] go to the server (a DLNA cast counts the TV's requests).
     */
    suspend fun await(
        server: RemuxHlsServer,
        key: String,
        wantedMs: Long,
        inProgress: (String) -> Pair<File, Boolean>?,
        stillWanted: () -> Boolean,
        lan: LanRequestListener? = null,
        diagPrefix: String = "",
        leadSec: Double = RemuxHls.START_LEAD_SEC,
        maxTicks: Int = MAX_TICKS,
        tickMs: Long = TICK_MS,
        maxWaitSec: Int = RemuxHls.RESUME_WAIT_SEC,
        wantedNow: () -> Long = { wantedMs },
        start: (Start) -> Boolean,
    ): Boolean {
        repeat(maxTicks) { tick ->
            delay(tickMs)
            // Not unstaged here: another wait may share the key (a re-cast of the same title), and
            // the staged remux is retired by whatever moved on (another stage, a load, the cast's end).
            if (!stillWanted()) return false
            if (inProgress(key) == null) return@repeat
            val wanted = wantedNow()
            val url = server.stage(key, lan, diagPrefix) { inProgress(key) }
            // Paced against where the TV will start, until the TV asks on its own.
            server.planStart(key, wanted)
            val ready = server.availableSec(key)
            val from = RemuxHls.castStart(wanted, ready, leadSec, tick + 1, maxWaitSec) ?: return@repeat
            if (start(Start(url, from, ready))) {
                // Handed over: the TV's previous remux, if another, is retired now and not before.
                server.serve(key, lan, diagPrefix) { inProgress(key) }
                return true
            }
        }
        server.unplan(key)
        return false
    }
}
