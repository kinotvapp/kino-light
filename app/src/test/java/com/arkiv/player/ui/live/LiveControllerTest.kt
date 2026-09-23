package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class LiveControllerTest {
    @Test
    fun `opening a channel returns a local url for VLC`() = runBlocking {
        val ctrl = LiveController(
            resolver = { code -> LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlFor = { s -> "http://127.0.0.1:9999/live.m3u8?c=${s.channel}" },
        )
        assertTrue(ctrl.open("c1").startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `preheating the neighbor doesn't resolve again on opening`() = runBlocking {
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.preheat("c2")
        ctrl.open("c2")
        assertEquals("the preheated channel was already resolved", 1, resolutions)
    }

    @Test
    fun `an expired session resolves again`() = runBlocking {
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code ->
                resolutions++
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, expiresAt = 1)
            },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
            now = { 999_999 },
        )
        ctrl.preheat("c3")
        ctrl.open("c3")
        assertEquals(2, resolutions)
    }

    @Test
    fun `a failing preheat doesn't propagate the exception or leave anything cached`() = runBlocking {
        // Risk from the brief: "preheat is best-effort and cancelable: it must not propagate
        // exceptions or leave inconsistent state if it fails". If the resolution triggered by
        // preheating blows up, open() must re-resolve from scratch, inheriting no partial state
        // and never seeing the original exception.
        var attempts = 0
        val ctrl = LiveController(
            resolver = { code ->
                attempts++
                if (attempts == 1) throw RuntimeException("the portal is down")
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0)
            },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.preheat("c4") // must not throw
        val url = ctrl.open("c4")
        assertEquals(2, attempts)
        assertTrue(url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `preheating different channels doesn't serialize between them`() = runBlocking {
        // Risk from the brief: "several coroutines can request the same channel at once". A
        // GLOBAL lock wrapping resolver() (as a naive first draft would suggest) would serialize
        // preheating the previous and next neighbor against each other -and would block an
        // open() for a third channel behind someone else's preheat-, exactly what preheating
        // exists to avoid (see the brief's intro: "while the overlay sits still, the next and
        // the previous channel get resolved quietly underneath"). With delay() inside
        // resolver(), two DIFFERENT channels must resolve in parallel: if a global lock
        // serialized them, this would take ~2x the delay instead of ~1x.
        val delayMs = 200L
        val ctrl = LiveController(
            resolver = { code -> delay(delayMs); LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
        )
        val start = System.currentTimeMillis()
        coroutineScope {
            launch { ctrl.preheat("prev") }
            launch { ctrl.preheat("next") }
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            "preheating two different channels took ${elapsed}ms; " +
                "if this is close to ${delayMs * 2}ms a global lock serialized them",
            elapsed < delayMs * 3 / 2,
        )
    }

    @Test(timeout = 10_000)
    fun `opening the same channel from several threads at once resolves only once`() {
        // Complements the previous test from the other side: DIFFERENT channels must not share
        // a lock, but the SAME channel requested at once by several coroutines -on real
        // threads, not just cooperative interleaving- must resolve only once. CyclicBarrier
        // lines up the threads to maximize real overlap (same pattern as SegmentSignatureTest).
        val resolutions = AtomicInteger(0)
        val ctrl = LiveController(
            resolver = { code ->
                delay(80)
                resolutions.incrementAndGet()
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0)
            },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
        )
        val threadCount = 16
        val barrier = CyclicBarrier(threadCount)
        val threads = (1..threadCount).map {
            Thread {
                barrier.await()
                runBlocking { ctrl.open("same-channel") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, resolutions.get())
    }

    @Test
    fun `close invalidates the cache and the next open resolves again`() = runBlocking {
        // Replaces an earlier test ("concurrent close with open doesn't corrupt the session
        // map") that this task's review found didn't discriminate: run against the naive version
        // (mutableMapOf + unsynchronized clear()), 4 times -including an amplified variant of 16
        // threads / 200 channels / 2000 iterations-, it never threw an exception; it passed just
        // the same with the correct implementation and with the broken one. Makes sense:
        // HashMap's only documented-guarantee failure (ConcurrentModificationException) comes
        // from its ITERATORS, and neither open()/preheat() nor close() ever iterate the map
        // -only get/put/clear-. The other real mode (a lost write mid resize) isn't something a
        // JUnit test can reliably force on a modern JVM without tooling out of scope (jcstress).
        // The choice of ConcurrentHashMap rests on its documented contract, not on a red→green
        // test -see the comment on `sessions` in LiveController.kt-. This test instead verifies
        // close()'s REAL, testable contract: it invalidates the cache.
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.open("c5")
        assertEquals(1, resolutions)
        ctrl.close()
        ctrl.open("c5")
        assertEquals(2, resolutions)
    }
}
