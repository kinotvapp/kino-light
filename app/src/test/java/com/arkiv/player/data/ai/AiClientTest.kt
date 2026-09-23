package com.arkiv.player.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiClientTest {

    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val store = object : MemoryStore {
        var json: String? = null
        override fun read() = json
        override fun save(json: String) { this.json = json }
    }

    /** Response per model; whatever isn't here answers with a valid chat. */
    private val perModel = mutableMapOf<String, MockResponse>()
    private var catalog: MockResponse = MockResponse().setBody(
        """{"data":[
          {"id":"a:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"b:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"c:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"d:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"e:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]}
        ]}""",
    )
    private val chatRequests = mutableListOf<String>()
    private var catalogRequests = 0

    /** Counts down to zero as soon as the server gets a chat request (not a catalog one): used
     *  to wait, in real time, until the request has actually gone out before cancelling. */
    private var chatRequestLatch = CountDownLatch(1)

    @Before fun setUp() {
        chatRequestLatch = CountDownLatch(1)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/models")) {
                    catalogRequests++
                    return catalog
                }
                // .clone(): request.body is the SAME instance takeRequest() returns below (verified
                // with javap over mockwebserver 4.12.0); reading it here leaves it empty for whoever
                // reads it again afterward (the "OpenAI dialect" test reads it again).
                val body = request.body.clone().readUtf8()
                val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                chatRequests += model
                chatRequestLatch.countDown()
                return perModel[model] ?: MockResponse().setBody(
                    """{"model":"$model","choices":[{"message":{"content":"hi from $model"}}]}""",
                )
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun client() = AiClient(
        baseUrl = server.url("/api/gateway").toString().trimEnd('/'),
        memory = ModelMemory(store) { now },
        nowMs = { now },
    )

    @Test fun `answers with the first model that works`() = runTest {
        val r = client().ask("say hi")
        assertEquals(AiResponse.Text("hi from a:free", "a:free"), r)
    }

    /** Kilo's anonymous tier depends on this header NOT being sent. */
    @Test fun `never sends Authorization`() = runTest {
        client().ask("say hi")
        repeat(server.requestCount) {
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `the request goes out in the OpenAI dialect`() = runTest {
        client().ask("say hi")
        server.takeRequest() // /models
        val chat = server.takeRequest()
        assertTrue(chat.path!!.endsWith("/chat/completions"))
        val body = chat.body.readUtf8()
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("say hi"))
    }

    @Test fun `a 429 moves to the next model`() = runTest {
        perModel["a:free"] = MockResponse().setResponseCode(429)
        val r = client().ask("say hi")
        assertEquals("b:free", (r as AiResponse.Text).model)
    }

    /**
     * A `Retry-After` of days would park a model for days (it is persisted), so a huge value is
     * capped at one hour. Tested by exclusion, not by "which one wins": with the other four models
     * broken, "a:free" is still out just before the hour and can be tried again just after.
     */
    @Test fun `a huge Retry-After is capped at one hour`() = runTest {
        perModel["a:free"] = MockResponse().setResponseCode(429).addHeader("Retry-After", "999999")
        val c = client()
        assertEquals("b:free", (c.ask("x") as AiResponse.Text).model) // a:free is left on hold

        // Just before the hour a:free is still excluded: if the other four fail, nothing is left.
        now += 60 * 60 * 1000L - 1
        listOf("b:free", "c:free", "d:free", "e:free").forEach { perModel[it] = MockResponse().setResponseCode(500) }
        assertEquals(AiResponse.Unable, c.ask("y"))

        // Past the hour a:free is available again (the other four are still broken).
        now += 2
        perModel.remove("a:free")
        assertEquals("a:free", (c.ask("z") as AiResponse.Text).model)
    }

    @Test fun `a 500 moves to the next model`() = runTest {
        perModel["a:free"] = MockResponse().setResponseCode(500)
        assertEquals("b:free", (client().ask("x") as AiResponse.Text).model)
    }

    @Test fun `an answer with no content moves on without a penalty`() = runTest {
        perModel["a:free"] = MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}""")
        val c = client()
        assertEquals("b:free", (c.ask("x") as AiResponse.Text).model)
        // It wasn't put on hold or moved down: with b already up top from its success, a is still in the list.
        perModel.remove("a:free")
        chatRequests.clear()
        perModel["b:free"] = MockResponse().setResponseCode(500)
        assertEquals("a:free", (c.ask("y") as AiResponse.Text).model)
    }

    @Test fun `tries at most four models`() = runTest {
        listOf("a:free", "b:free", "c:free", "d:free").forEach { perModel[it] = MockResponse().setResponseCode(500) }
        assertEquals(AiResponse.Unable, client().ask("x"))
        // e:free would answer, but it is the fifth: the limit stops before it.
        assertEquals(listOf("a:free", "b:free", "c:free", "d:free"), chatRequests)
    }

    @Test fun `the catalog is kept for six hours`() = runTest {
        val c = client()
        c.ask("x")
        c.ask("y")
        assertEquals(1, catalogRequests)
        now += 6 * 60 * 60 * 1000L + 1
        c.ask("z")
        assertEquals(2, catalogRequests)
    }

    @Test fun `if refreshing the catalog fails it keeps the last one`() = runTest {
        val c = client()
        c.ask("x")
        now += 6 * 60 * 60 * 1000L + 1
        catalog = MockResponse().setResponseCode(503)
        assertTrue(c.ask("y") is AiResponse.Text)
    }

    @Test fun `with no catalog it can't`() = runTest {
        catalog = MockResponse().setResponseCode(503)
        assertEquals(AiResponse.Unable, client().ask("x"))
    }

    /**
     * A connection that drops mid-body, after a 200, is a "network error": 5 min wait
     * ([Failure.Server]), not [Failure.Unreadable] (which doesn't count against it). The body has
     * to be long so `DISCONNECT_DURING_RESPONSE_BODY` (cuts off mid-bytes) leaves the reader
     * halfway through a `Content-Length` that never completes.
     */
    @Test fun `a connection dropped mid-response counts as a network failure`() = runTest {
        val longBody = """{"choices":[{"message":{"content":"${"x".repeat(5000)}"}}]}"""
        perModel["a:free"] = MockResponse().setBody(longBody)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        val c = client()
        assertEquals("b:free", (c.ask("x") as AiResponse.Text).model) // a fails, b answers
        // b is already up top from its success: we make it fail too so the order keeps moving down.
        // If a:free was penalized (5 min wait), with the same `now` it's still out and the next
        // candidate is c:free; if it wasn't penalized (the Unreadable bug), a:free gets retried and,
        // since it no longer has the cutoff, answers fine.
        perModel.remove("a:free")
        perModel["b:free"] = MockResponse().setResponseCode(500)
        chatRequests.clear()
        c.ask("y")
        assertFalse(chatRequests.contains("a:free"))
    }

    @Test fun `the model that answered well is tried first next time`() = runTest {
        perModel["a:free"] = MockResponse().setResponseCode(500)
        client().ask("x") // a fails, b answers
        now += 6 * 60 * 1000L // a already left its wait
        perModel.remove("a:free")
        chatRequests.clear()
        client().ask("y")
        assertEquals("b:free", chatRequests.first())
    }

    /**
     * If cancelled while the first model still hasn't answered (skipping a chapter, leaving the
     * player), the ask can't keep trying models, and the one that was answering can't be
     * penalized for a cancellation that says nothing about whether the model works.
     */
    @Test fun `cancelling while the first model is slow does not try the second or penalize it`() = runTest {
        // `setHeadersDelay` (not `setBodyDelay`): delay BEFORE answering, like a model that's slow
        // to generate the response -the real scenario A3 cares about-, not an already-ready answer
        // whose body takes a while to arrive. 2 s is plenty to cancel well before: cancelling cuts
        // the call in milliseconds (see this test's `time`), it doesn't wait out the rest of the
        // delay -only `@After` waits out the rest of those 2 s because the simulated server's delay
        // thread doesn't learn about the socket's cancellation until it tries to write.
        perModel["a:free"] = MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setResponseCode(500)
        val c = client()
        val job = launch(Dispatchers.Default) { c.ask("x") }
        withContext(Dispatchers.Default) { chatRequestLatch.await(2, TimeUnit.SECONDS) }
        job.cancelAndJoin()
        assertEquals(listOf("a:free"), chatRequests) // never got to try b:free

        // If a:free had been penalized (5 min wait from Failure.Server), the next attempt would
        // jump straight to b:free even though a:free already answers fine and fast.
        perModel.remove("a:free") // now the default answers: valid JSON, no delay
        chatRequests.clear()
        assertEquals("a:free", (c.ask("y") as AiResponse.Text).model)
    }
}
