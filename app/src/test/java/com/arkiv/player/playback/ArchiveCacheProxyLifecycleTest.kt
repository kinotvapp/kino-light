package com.arkiv.player.playback

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proxy lifecycle: start, stop, and start again. Uses real sockets. */
class ArchiveCacheProxyLifecycleTest {

    private fun tempDir(): File = Files.createTempDirectory("arkiv-proxy").toFile()

    /** Is anyone listening and accepting on that port? */
    private fun acceptsConnections(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
        true
    }.getOrDefault(false)

    @Test fun starting_leaves_the_port_accepting() {
        val p = ArchiveCacheProxy(tempDir())
        try {
            val port = p.start()
            assertTrue("nobody is listening on $port after start()", acceptsConnections(port))
        } finally {
            p.stop()
        }
    }

    /**
     * THE BUG: the bar's stop button calls `stop()`, which closed the ServerSocket created in the
     * constructor. Since the proxy is a singleton and lives the whole process, and a closed
     * ServerSocket doesn't reopen, `start()` returned a port nobody was listening on anymore:
     * **archive was left loading forever** until the app was killed. Stopping has to be reversible.
     */
    @Test fun starting_again_after_stopping_revives_the_proxy() {
        val p = ArchiveCacheProxy(tempDir())
        try {
            val first = p.start()
            assertTrue(acceptsConnections(first))
            p.stop()
            val second = p.start()
            assertTrue("the proxy didn't revive: nobody is listening on $second", acceptsConnections(second))
        } finally {
            p.stop()
        }
    }

    /** Stopping has to really cut off: if it kept accepting, the button would be useless. */
    @Test fun stopping_stops_accepting() {
        val p = ArchiveCacheProxy(tempDir())
        val port = p.start()
        assertTrue(acceptsConnections(port))
        p.stop()
        assertFalse("still accepting on $port after stop()", acceptsConnections(port))
    }

    /** The URL handed to VLC has to point at the LIVE port, not the previous session's. */
    @Test fun the_url_points_at_the_port_that_is_listening() {
        val p = ArchiveCacheProxy(tempDir())
        try {
            p.start()
            p.stop()
            val live = p.start()
            assertTrue(p.proxyUrl("https://archive.org/download/x/y.mp4").contains(":$live/"))
        } finally {
            p.stop()
        }
    }

    /** Calling start() twice must not open a second socket nor change the port in use. */
    @Test fun starting_twice_is_idempotent() {
        val p = ArchiveCacheProxy(tempDir())
        try {
            val a = p.start()
            val b = p.start()
            assertNotEquals(-1, a)
            assertTrue("repeated start() changed the port: $a -> $b", a == b)
        } finally {
            p.stop()
        }
    }
}
