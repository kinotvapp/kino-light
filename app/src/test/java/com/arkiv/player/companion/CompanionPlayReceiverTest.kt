package com.arkiv.player.companion

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPlayReceiverTest {

    @Test fun `a play that resolves opens the player and acks ok with the same id`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val opened = mutableListOf<String>()
        val resolver = object : CompanionPlayReceiver.PlayResolver {
            override suspend fun resolve(item: CompanionPlayItem) =
                CompanionPlayReceiver.PlayOutcome.Open(episodeId = "magis:cid-42:e164", title = item.title)
        }
        val receiver = CompanionPlayReceiver(this, incoming, { sent += it }, resolver, onOpen = { opened += it })
        receiver.start()
        testScheduler.advanceUntilIdle() // let the collector subscribe before emitting

        val play = newEnvelope(TYPE_PLAY, CompanionPlayItem(kind = "magis", ref = "r", contentId = "cid-42", title = "Bleach").toPayload())
        incoming.emit(play)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("magis:cid-42:e164"), opened)
        assertEquals(1, sent.size)
        assertEquals(TYPE_PLAY_ACK, sent[0].type)
        assertEquals(play.id, sent[0].id)
        assertTrue(CompanionPlayAck.fromPayload(sent[0].payload).ok)
        receiver.stop() // the collector is infinite; stop it so runTest can finish
    }

    @Test fun `a play that cannot resolve acks not-ok and never opens`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val opened = mutableListOf<String>()
        val resolver = object : CompanionPlayReceiver.PlayResolver {
            override suspend fun resolve(item: CompanionPlayItem) = CompanionPlayReceiver.PlayOutcome.Fail("no_link")
        }
        val receiver = CompanionPlayReceiver(this, incoming, { sent += it }, resolver, onOpen = { opened += it })
        receiver.start()
        testScheduler.advanceUntilIdle()

        incoming.emit(newEnvelope(TYPE_PLAY, CompanionPlayItem(kind = "live", liveCode = "caracol").toPayload()))
        testScheduler.advanceUntilIdle()

        assertTrue(opened.isEmpty())
        val ack = CompanionPlayAck.fromPayload(sent.single().payload)
        assertTrue(!ack.ok)
        assertEquals("no_link", ack.reason)
        receiver.stop()
    }

    @Test fun `a malformed play payload is ignored (no ack, no crash)`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val resolver = object : CompanionPlayReceiver.PlayResolver {
            override suspend fun resolve(item: CompanionPlayItem) = CompanionPlayReceiver.PlayOutcome.Fail("x")
        }
        val receiver = CompanionPlayReceiver(this, incoming, { sent += it }, resolver, onOpen = {})
        receiver.start()
        testScheduler.advanceUntilIdle()

        incoming.emit(newEnvelope(TYPE_PLAY, JSONObject().put("kind", "")))
        testScheduler.advanceUntilIdle()

        assertTrue(sent.isEmpty())
        receiver.stop()
    }

    @Test fun `a resolver that throws acks resolve_failed and the receiver keeps working`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        var throwOnce = true
        val resolver = object : CompanionPlayReceiver.PlayResolver {
            override suspend fun resolve(item: CompanionPlayItem): CompanionPlayReceiver.PlayOutcome {
                if (throwOnce) { throwOnce = false; throw IllegalStateException("credentials not ready") }
                return CompanionPlayReceiver.PlayOutcome.Open("magis:x", item.title)
            }
        }
        val receiver = CompanionPlayReceiver(this, incoming, { sent += it }, resolver, onOpen = {})
        receiver.start()
        testScheduler.advanceUntilIdle()

        val p1 = newEnvelope(TYPE_PLAY, CompanionPlayItem(kind = "magis", ref = "r", contentId = "c", title = "A").toPayload())
        incoming.emit(p1)
        testScheduler.advanceUntilIdle()
        // The throw was turned into a resolve_failed ack (not a crash, not a missing ack).
        val ack1 = CompanionPlayAck.fromPayload(sent.single().payload)
        assertEquals(p1.id, sent.single().id)
        assertTrue(!ack1.ok)
        assertEquals("resolve_failed", ack1.reason)

        // The collector survived: a second play still resolves and acks ok.
        val p2 = newEnvelope(TYPE_PLAY, CompanionPlayItem(kind = "magis", ref = "r", contentId = "c", title = "B").toPayload())
        incoming.emit(p2)
        testScheduler.advanceUntilIdle()
        assertEquals(2, sent.size)
        assertTrue(CompanionPlayAck.fromPayload(sent[1].payload).ok)
        receiver.stop()
    }

    @Test fun `envelopes that are not play are ignored`() = runTest {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
        val sent = mutableListOf<Envelope>()
        val resolver = object : CompanionPlayReceiver.PlayResolver {
            override suspend fun resolve(item: CompanionPlayItem) = CompanionPlayReceiver.PlayOutcome.Fail("x")
        }
        val receiver = CompanionPlayReceiver(this, incoming, { sent += it }, resolver, onOpen = {})
        receiver.start()
        testScheduler.advanceUntilIdle()

        incoming.emit(newEnvelope(TYPE_PING, JSONObject()))
        testScheduler.advanceUntilIdle()

        assertTrue(sent.isEmpty())
        receiver.stop()
    }
}
