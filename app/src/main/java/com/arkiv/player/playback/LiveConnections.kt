package com.arkiv.player.playback

import java.util.Collections

/**
 * The connections to the origin that are open RIGHT NOW, so they can all be abandoned at once.
 *
 * Exists because of [NetworkChange]: when the device switches networks, open sockets are left tied
 * to an interface that no longer exists, and with nobody to close them the read just waits until
 * [OriginPolicy]'s body deadline —90 s in archive, 30 s in magis— before even the first retry
 * begins. Closing them makes that read fail on the spot and the retry go out over the new network.
 *
 * NOT [SingleConnection], which did something different and was abandoned: that one closed the
 * previous connection of the SAME origin, believing the CDN served one at a time, and ended up
 * being the cause of the very failure it claimed to prevent (it cut off the reads libVLC uses to
 * identify the stream, leaving it at `tracks=v0/a0`). Here, opening one connection doesn't close
 * any other: the only time something gets closed is when someone from outside says they're no
 * longer any good.
 *
 * Thread-safe: it registers the thread handling each request and closes it on the network-change
 * notification.
 */
class LiveConnections {

    private val openConnections: MutableSet<SingleConnection.Closer> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Adds [connection] to the live set. Doesn't touch any other. */
    fun register(connection: SingleConnection.Closer) {
        openConnections.add(connection)
    }

    /** Drops [connection] because it finished on its own. After this, [closeAll] no longer touches it. */
    fun release(connection: SingleConnection.Closer) {
        openConnections.remove(connection)
    }

    /**
     * Closes everything currently open and empties the registry. Returns how many it closed, for
     * the log.
     *
     * Each close is wrapped: an already-dead socket can throw when closed, and that's expected —
     * it can't be allowed to stop the rest from closing, which is exactly what has to happen.
     */
    fun closeAll(): Int {
        var closed = 0
        val iterator = openConnections.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            iterator.remove()
            closed++
            runCatching { c.close() }
        }
        return closed
    }
}
