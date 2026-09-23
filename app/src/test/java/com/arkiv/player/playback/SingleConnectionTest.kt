package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Magis's CDN serves ONE connection per origin: when seeking forward, VLC opens the new range
 * before the previous one dies, and the new one gets left hanging until the timeout (seen on
 * device: the request enters the proxy and never leaves). This registry closes the previous one
 * the instant the next one comes in.
 */
class SingleConnectionTest {

    private class Fake : SingleConnection.Closer {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun `the new one closes the previous one from the same origin`() {
        val reg = SingleConnection()
        val old = Fake()
        val new = Fake()

        reg.register("peli", old)
        reg.register("peli", new)

        assertTrue("the previous one must end up closed", old.closed)
        assertFalse("the new one is still alive", new.closed)
    }

    @Test fun `different origins don't step on each other`() {
        val reg = SingleConnection()
        val a = Fake()
        val b = Fake()

        reg.register("peli", a)
        reg.register("serie", b)

        assertFalse(a.closed)
        assertFalse(b.closed)
    }

    @Test fun `release removes the entry without closing anything else`() {
        val reg = SingleConnection()
        val old = Fake()
        val new = Fake()

        reg.register("peli", old)
        reg.release("peli", old)
        reg.register("peli", new)

        assertFalse("it wasn't registered anymore: no need to close it again", old.closed)
        assertFalse(new.closed)
    }

    @Test fun `releasing one that was already replaced does not touch the new one`() {
        // The old one finishes AFTER the new one registered: releasing must not delete the new one.
        val reg = SingleConnection()
        val old = Fake()
        val new = Fake()

        reg.register("peli", old)
        reg.register("peli", new)
        reg.release("peli", old)

        val third = Fake()
        reg.register("peli", third)
        assertTrue("the new one was still the active one and must get closed", new.closed)
        assertEquals(false, third.closed)
    }
}
