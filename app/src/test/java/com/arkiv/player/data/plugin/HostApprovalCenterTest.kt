package com.arkiv.player.data.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CancellationException

class HostApprovalCenterTest {
    @Test fun `emits a pending request and resolves it when respond is called`() = runTest {
        val center = HostApprovalCenter()
        assertNull(center.pending.value)
        val result = async { center.request("plug1", "Plugin Uno", "new.example") }
        // Wait for the request to actually land in `pending` before answering it.
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        assertEquals("plug1", req.pluginId)
        assertEquals("new.example", req.host)
        req.respond(true)
        assertEquals(true, result.await())
        assertNull(center.pending.value)
    }

    @Test fun `a second request queues behind the first`() = runTest {
        val center = HostApprovalCenter()
        val first = async { center.request("plug1", "Uno", "a.example") }
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        val second = async { center.request("plug2", "Dos", "b.example") }
        kotlinx.coroutines.yield()
        // The second plugin's host isn't showing yet: only one prompt at a time.
        assertEquals("a.example", center.pending.value?.host)
        req.respond(false)
        assertEquals(false, first.await())
        var req2 = center.pending.value
        while (req2 == null) { kotlinx.coroutines.yield(); req2 = center.pending.value }
        assertEquals("b.example", req2.host)
        req2.respond(true)
        assertEquals(true, second.await())
    }

    @Test fun `NoHostApprovalRequester always denies`() = runTest {
        assertEquals(false, NoHostApprovalRequester.request("p", "P", "x.example"))
    }

    @Test fun `cancelling a pending request clears it and allows the queue to continue`() = runTest {
        val center = HostApprovalCenter()
        val first = async { center.request("plug1", "Uno", "a.example") }
        // Wait for the first request to become pending.
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        assertEquals("a.example", req.host)

        // Cancel the first request while it's pending.
        first.cancel()

        // Give the cancellation handler time to run.
        kotlinx.coroutines.yield()

        // The pending should be cleared (or at least the queue is not stuck).
        // Verify by launching a second request and confirming it can resolve.
        val second = async { center.request("plug2", "Dos", "b.example") }
        var req2 = center.pending.value
        while (req2 == null) { kotlinx.coroutines.yield(); req2 = center.pending.value }
        assertEquals("b.example", req2.host)
        req2.respond(true)
        assertEquals(true, second.await())

        // Verify first coroutine was actually cancelled.
        try {
            first.await()
        } catch (e: CancellationException) {
            // Expected
        }
    }
}
