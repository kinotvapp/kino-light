package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The registry of connections to the origin that are open RIGHT NOW, so they can all be abandoned
 * at once when the network changes underneath.
 *
 * NOT [SingleConnection], which did something different and was abandoned: that one closed the
 * previous connection of the SAME origin believing the CDN served one at a time, and turned out to
 * be the cause of the very failure it claimed to prevent (it cut off the reads libVLC uses to
 * identify the stream). Here, nothing gets closed by opening another: the only moment something
 * closes is when someone from outside says they're no longer any good, and today that someone is
 * the network change.
 */
class LiveConnectionsTest {

    private class Spy : SingleConnection.Closer {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test fun `closeAll closes everything that is open`() {
        val live = LiveConnections()
        val a = Spy()
        val b = Spy()
        live.register(a)
        live.register(b)

        assertEquals(2, live.closeAll())

        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
    }

    /** Opening one connection does NOT close the others: that's exactly what went wrong in SingleConnection. */
    @Test fun `registering one does not touch the others`() {
        val live = LiveConnections()
        val a = Spy()
        live.register(a)
        live.register(Spy())
        assertEquals(0, a.closed)
    }

    /** A connection that already finished on its own doesn't get closed twice or counted. */
    @Test fun `the one that was released no longer gets closed`() {
        val live = LiveConnections()
        val a = Spy()
        live.register(a)
        live.release(a)

        assertEquals(0, live.closeAll())
        assertEquals(0, a.closed)
    }

    @Test fun `with no open connections there is nothing to close`() {
        assertEquals(0, LiveConnections().closeAll())
    }

    /** After closing them, the registry is left empty: a second notice doesn't close them again. */
    @Test fun `closing twice in a row does not repeat`() {
        val live = LiveConnections()
        val a = Spy()
        live.register(a)

        assertEquals(1, live.closeAll())
        assertEquals(0, live.closeAll())
        assertEquals(1, a.closed)
    }

    /**
     * A connection that blows up on close can't stop the others from closing: the socket is
     * already dead, throwing from here is expected and there's nothing to do about that exception.
     */
    @Test fun `one that blows up on close does not stop the others`() {
        val live = LiveConnections()
        val good = Spy()
        live.register(SingleConnection.Closer { error("dead socket") })
        live.register(good)

        assertEquals(2, live.closeAll())
        assertEquals(1, good.closed)
    }
}
