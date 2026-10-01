package com.arkiv.player.playback

import java.io.File

/**
 * The remux an earlier cast stopped and left on disk, kept so the next cast of the same title can
 * start on it at once (see [RemuxHls.Splice]).
 *
 * A stopped remux is a valid, playable prefix -- a fragmented MP4 stops at a fragment boundary --
 * but a new export cannot append to it: media3's Transformer always writes from the start. It used
 * to delete it and start over, so every reconnect waited 30-40 s for the new run to reach the
 * phone's position although 216-507 MB of the title were already there (measured 2026-10-01).
 * Now it is moved aside as `<name>.prev` and served while the new run catches up.
 *
 * Named after the remux key's file, so reuse follows exactly what the remux itself is keyed by:
 * the origin (never its tokens), the start point and the audio track.
 */
object RemuxLeftover {

    /** The leftover that goes with the remux [done] (the finished file's name, `.prev`). */
    fun fileFor(done: File): File = File(done.parentFile, "${done.name}.prev")

    /**
     * Before a new export of the same key: keeps [partial] (what a stopped run wrote) as the
     * [leftover] when it holds more than the one already kept, and removes it otherwise. Either
     * way [partial] is gone afterwards, ready for the new run.
     */
    fun rotate(partial: File, leftover: File) {
        val written = if (partial.exists()) partial.length() else 0L
        if (written > 0L && written > (if (leftover.exists()) leftover.length() else 0L)) {
            runCatching { leftover.delete() }
            if (runCatching { partial.renameTo(leftover) }.getOrDefault(false)) return
        }
        runCatching { partial.delete() }
    }

    /** [leftover] when there is one worth serving: never once the title is finished ([done]). */
    fun usable(done: File, leftover: File): File? {
        if (done.exists() && done.length() > 0L) return null
        return leftover.takeIf { it.exists() && it.length() > 0L }
    }
}
