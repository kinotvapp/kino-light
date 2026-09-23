package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DituClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = DituClient(baseUrl = server.url("/AGL").toString().trimEnd('/'))

    /** Without these three headers the CDN answers 403, both on the manifest and the segments. */
    @Test fun `sends the headers the API requires`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        client().get("TRAY/SEARCH/VOD")

        val request = server.takeRequest()
        assertEquals("yes", request.getHeader("restful"))
        assertEquals("okhttp/4.12.0", request.getHeader("User-Agent"))
        assertEquals("application/json, text/plain, */*", request.getHeader("Accept"))
    }

    @Test fun `builds the path under the base and adds the parameters`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        client().get("TRAY/SEARCH/VOD", mapOf("query" to "rigo", "filter_contentType" to "BUNDLE"))

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/AGL/TRAY/SEARCH/VOD?"))
        assertTrue(request.path!!.contains("query=rigo"))
        assertTrue(request.path!!.contains("filter_contentType=BUNDLE"))
    }

    /** THE COOKIE. It doesn't come in the body: it comes in the response's Set-Cookie. */
    @Test fun `getWithToken pulls the playback_token from Set-Cookie`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}""")
                .addHeader("Set-Cookie", "playback_token=abc123; Path=/; HttpOnly"),
        )

        val r = client().getWithToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("abc123", r.playbackToken)
        assertEquals("https://cdn/x.mpd", r.json.getJSONObject("resultObj").getString("src"))
    }

    /**
     * With no cookie it does NOT fail: the empty token has to reach the player, because the real
     * symptom (a license 500) is diagnosed much faster if the log shows "sin playback_token" than
     * if resolution blows up earlier with a different message.
     */
    @Test fun `with no Set-Cookie the token is left empty and doesn't fail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}"""))

        val r = client().getWithToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("", r.playbackToken)
    }

    @Test fun `other cookies aren't mistaken for the token`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"ok":1}""")
                .addHeader("Set-Cookie", "session=zzz; Path=/")
                .addHeader("Set-Cookie", "playback_token=elbueno; Path=/"),
        )

        assertEquals("elbueno", client().getWithToken("CONTENT/VIDEOURL/VOD/1/2").playbackToken)
    }

    @Test fun `an error status turns into a DituException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))

        val e = runCatching { client().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("expected DituException but got $e", e is DituException)
        assertTrue(e!!.message!!.contains("500"))
        // The status travels separately: `CaracolFailure` reads it from here and not from the message.
        assertEquals(500, (e as DituException).httpCode)
    }

    @Test fun `a body that isn't JSON turns into a DituException`() = runTest {
        server.enqueue(MockResponse().setBody("<html>error</html>"))

        val e = runCatching { client().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("expected DituException but got $e", e is DituException)
    }
}
