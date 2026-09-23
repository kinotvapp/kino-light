package com.arkiv.player.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * One single live connection per origin.
 *
 * Magis's CDN serves one at a time: when seeking forward, the player opens the new range before
 * finding out the previous one died, and the CDN leaves the new one hanging until the timeout
 * fires (on device the request was seen entering the proxy and never leaving, with the player
 * stuck at "buffering 0%" forever). Registering the new one closes the previous one on the spot.
 */
class SingleConnection {

    /** What can be closed: the HTTP connection to the origin. An interface so it can be tested without network. */
    fun interface Closer {
        fun close()
    }

    private val active = ConcurrentHashMap<String, Closer>()

    /** Leaves [connection] as the only live one for [origin], closing whatever was there before. */
    fun register(origin: String, connection: Closer) {
        active.put(origin, connection)?.let { previous ->
            if (previous !== connection) runCatching { previous.close() }
        }
    }

    /** Drops [connection] if it's still the active one (if it was already replaced, does nothing). */
    fun release(origin: String, connection: Closer) {
        active.remove(origin, connection)
    }
}
