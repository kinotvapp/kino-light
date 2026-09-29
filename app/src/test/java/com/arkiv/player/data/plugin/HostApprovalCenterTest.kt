package com.arkiv.player.data.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // The 12 s window is gone: the plugin call's clock is paused while it asks (PluginCallClock), so
    // nothing but the person -- or the call ending, which cancels this request -- takes it down.
    @Test fun `a fetch prompt waits for the person however long they take`() = runTest {
        val center = HostApprovalCenter()
        val result = async { center.request("plug1", "Uno", "a.example") }
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        kotlinx.coroutines.delay(10 * 60_000)
        assertTrue("still waiting after ten minutes", result.isActive)
        assertEquals(req, center.pending.value)
        req.respond(true)
        assertEquals(true, result.await())
    }

    // On the TV a "RIGHT, CENTER" meant for one prompt landed on the next one, which had replaced it
    // with focus back on "Rechazar". A prompt that follows another closely waits longer before it
    // accepts any answer.
    @Test fun `a prompt shown right after another one closed arms later than a first one`() = runTest {
        var now = 0L
        val center = HostApprovalCenter(clock = { now })
        val first = async { center.request("plug1", "Uno", "a.example") }
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        assertEquals(HostApprovalCenter.ARM_DELAY_MS, req.armDelayMs)
        val second = async { center.request("plug1", "Uno", "b.example") }
        now = 5_000_000_000L
        req.respond(true)
        first.await()
        var req2 = center.pending.value
        while (req2 == null || req2 === req) { kotlinx.coroutines.yield(); req2 = center.pending.value }
        assertEquals(HostApprovalCenter.SUCCESSOR_ARM_DELAY_MS, req2.armDelayMs)
        assertTrue(HostApprovalCenter.SUCCESSOR_ARM_DELAY_MS >= 1_000)
        req2.respond(false)
        second.await()
        // Long after the last one closed, a new prompt is a first one again.
        now += 60_000_000_000L
        val third = async { center.request("plug1", "Uno", "c.example") }
        var req3 = center.pending.value
        while (req3 == null) { kotlinx.coroutines.yield(); req3 = center.pending.value }
        assertEquals(HostApprovalCenter.ARM_DELAY_MS, req3.armDelayMs)
        third.cancel()
    }

    // A Stream URL on an undeclared host is asked about AFTER the plugin's call returned, with the
    // person looking at the screen waiting for the video: nothing may take the dialog down but them.
    @Test fun `a stream prompt outlives the fetch window and waits for the person`() = runTest {
        val center = HostApprovalCenter()
        val result = async { center.requestUntilAnswered("plug1", "Uno", "cdn.example", HostApprovalReason.VIDEO) }
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        kotlinx.coroutines.delay(10 * 60_000)
        assertTrue("still waiting after ten fetch windows", result.isActive)
        assertEquals(req, center.pending.value)
        req.respond(true)
        assertEquals(true, result.await())
        assertNull(center.pending.value)
    }

    @Test fun `leaving while a stream prompt waits takes the dialog down`() = runTest {
        val center = HostApprovalCenter()
        val result = async { center.requestUntilAnswered("plug1", "Uno", "cdn.example", HostApprovalReason.VIDEO) }
        while (center.pending.value == null) kotlinx.coroutines.yield()
        result.cancel()
        kotlinx.coroutines.yield()
        assertNull(center.pending.value)
        // The queue is free again for the next question.
        val next = async { center.request("plug2", "Dos", "b.example") }
        var req = center.pending.value
        while (req == null) { kotlinx.coroutines.yield(); req = center.pending.value }
        req.respond(false)
        assertEquals(false, next.await())
    }

    @Test fun `each prompt says what the host is for, naming it`() = runTest {
        fun question(reason: HostApprovalReason) = HostApprovalRequest("p", "P", "cdn.example", reason) {}.question
        assertEquals("Quiere conectarse por primera vez a cdn.example. ¿Permitir?", question(HostApprovalReason.FETCH))
        for (reason in HostApprovalReason.entries) assertTrue(question(reason), "cdn.example" in question(reason))
        assertTrue(question(HostApprovalReason.VIDEO).startsWith("El video está en cdn.example"))
        assertTrue(question(HostApprovalReason.SUBTITLE).startsWith("Los subtítulos están en cdn.example"))
        // The fetch path keeps asking exactly what it always asked.
        val center = HostApprovalCenter()
        val fetch = async { center.request("p", "P", "x.example") }
        while (center.pending.value == null) kotlinx.coroutines.yield()
        assertEquals(HostApprovalReason.FETCH, center.pending.value!!.reason)
        fetch.cancel()
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
