package com.arkiv.player.playback

import kotlinx.coroutines.delay
import java.io.File

/**
 * The wait before a growing remux goes to a TV, the SAME for every protocol (Chromecast and DLNA):
 * once a second, serve the remux on [RemuxHlsServer] as soon as there is anything of it on disk (a
 * new run or the leftover an earlier cast stopped, see [RemuxLeftover]), pace it against where the
 * TV will start ([RemuxHlsServer.planStart]) and hand the TV over once it covers the phone's
 * position with [RemuxHls.START_LEAD_SEC] to spare -- or, past [RemuxHls.RESUME_WAIT_SEC], the
 * furthest point it covers before it ([RemuxHls.castStart]): never back at 0:00 because the wait
 * ran out.
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
     * [lan] and [diagPrefix] go to [RemuxHlsServer.serve] (a DLNA cast counts the TV's requests).
     */
    suspend fun await(
        server: RemuxHlsServer,
        key: String,
        wantedMs: Long,
        inProgress: (String) -> Pair<File, Boolean>?,
        leftover: (String) -> File?,
        stillWanted: () -> Boolean,
        lan: LanRequestListener? = null,
        diagPrefix: String = "",
        leadSec: Double = RemuxHls.START_LEAD_SEC,
        maxTicks: Int = MAX_TICKS,
        tickMs: Long = TICK_MS,
        start: (Start) -> Boolean,
    ): Boolean {
        repeat(maxTicks) { tick ->
            delay(tickMs)
            if (!stillWanted()) return false
            // An earlier cast's leftover is enough to start: when it already covers where the
            // phone is, the server serves it before the new run has written anything.
            if (inProgress(key) == null && leftover(key) == null) return@repeat
            val url = server.serve(key, lan, diagPrefix) { inProgress(key) }
            // Paced against where the TV will start, until the TV asks on its own.
            server.planStart(key, wantedMs)
            val ready = server.availableSec(key)
            val from = RemuxHls.castStart(wantedMs, ready, leadSec, tick + 1) ?: return@repeat
            if (start(Start(url, from, ready))) return true
        }
        return false
    }
}
