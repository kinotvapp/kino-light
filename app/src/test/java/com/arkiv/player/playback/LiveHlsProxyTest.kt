package com.arkiv.player.playback

import com.arkiv.player.data.gateway.ChannelCdn
import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class LiveHlsProxyTest {
    /** Predictable signature, so the test can assert which `Content-Auth` went out on each request. */
    private class FakeSignatures : SegmentSignature {
        var issued = 0
        var rejections = 0
        var acceptances = 0
        override suspend fun sign(token: String): LiveSignature {
            issued++
            return LiveSignature(1000L, "sig%02d".format(issued))
        }
        override fun rejected() { rejections++ }
        override fun accepted() { acceptances++ }
    }

    private fun read(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val body = runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
        return c.responseCode to body
    }

    @Test
    fun `rewrites absolute segments toward the proxy itself`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n#EXTINF:6,\nhttp://seg2.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val port = proxy.start()
        val session = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (code, body) = read(proxy.urlFor(session))

        assertEquals(200, code)
        assertTrue(body.contains("http://127.0.0.1:$port/seg?u="))
        assertTrue("no CDN URL should be left unrewritten", !body.contains("seg1.cdn/live"))
        assertEquals(2, body.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `sets all three headers when requesting the playlist`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        read(proxy.urlFor(session))

        val req = upstream.takeRequest()
        assertEquals("LIC", req.getHeader("Content-License"))
        assertEquals("Ranger/4.9.4-17294ac0", req.getHeader("User-Agent"))
        val auth = req.getHeader("Content-Auth")!!
        assertTrue(auth.contains("sign2_method=sign_o3"))
        assertTrue(auth.contains("start_moment=1000"))
        assertTrue(auth.contains("sign2=sig01"))
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `on a 403 it asks for a fresh signature and retries exactly once`() = runBlocking {
        val upstream = MockWebServer()
        var requests = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                return if (requests == 1) MockResponse().setResponseCode(403)
                else MockResponse().setBody("#EXTM3U\n")
            }
        }
        upstream.start()

        val signatures = FakeSignatures()
        val proxy = LiveHlsProxy(signatures)
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, _) = read(proxy.urlFor(session))

        assertEquals(200, code)
        assertEquals("one 403 and its retry, nothing more", 2, requests)
        assertEquals("the 403 is reported to the signature source", 1, signatures.rejections)
        assertEquals("the retry that did work is reported as accepted", 1, signatures.acceptances)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Finding F1 from the final review: before, `requestFromOrigin` called `signatures.rejected()`
     * ONCE PER HTTP ATTEMPT (up to two, in here) -- so a single "real" 403 (the "session expired,
     * not the signature" case, where the retry also 403s without the algorithm being broken) was
     * already pushing `FirmaConRespaldo`'s counter TWO steps at once, half the real threshold.
     * "two 403s in a row" here is ONE `LiveHlsProxy` request (with its internal retry) giving up:
     * that has to count as ONE rejection, not two.
     */
    @Test
    fun `two 403s in a row give up instead of retrying forever, and count as a SINGLE rejection`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val signatures = FakeSignatures()
        val proxy = LiveHlsProxy(signatures)
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, _) = read(proxy.urlFor(session))
        assertEquals(502, code)
        assertEquals("a 403 and its retry -also 403- count as a SINGLE rejection", 1, signatures.rejections)
        assertEquals(0, signatures.acceptances)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * "In the same wave" of the final review: a 403 that survives the retry means the channel's
     * SESSION (token/license) expired, not the signature -see `requestFromOrigin`'s KDoc-. Nobody
     * used to report this: `LiveController` kept serving that cached session for up to 300s more,
     * so zapping away and back to a broken channel left it broken that whole time.
     */
    @Test
    fun `two 403s in a row report that the channel's session died`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val deadChannels = CopyOnWriteArrayList<String>()
        val proxy = LiveHlsProxy(FakeSignatures(), onSessionDead = { deadChannels.add(it) })
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "canal-x", 0)
        read(proxy.urlFor(session))

        assertEquals(listOf("canal-x"), deadChannels)
        proxy.stop(); upstream.shutdown()
    }

    /** The happy path (no 403) must not report a dead session -- that would be a false positive. */
    @Test
    fun `no 403 means no dead-session report`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val deadChannels = CopyOnWriteArrayList<String>()
        val proxy = LiveHlsProxy(FakeSignatures(), onSessionDead = { deadChannels.add(it) })
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        read(proxy.urlFor(session))

        assertTrue(deadChannels.isEmpty())
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Finding C2 from the review: `handle()` didn't catch exceptions, and `stop()` can set
     * `session = null` while ANOTHER connection already in flight is still using it (the user
     * leaves the player right as a segment is halfway through downloading). Before this test, that
     * ended in an NPE (`session!!.license`) escaping the connection's thread -- and on Android an
     * uncaught exception on ANY thread kills the whole process.
     *
     * To avoid depending on real timing (which would be a flaky test), the signature source itself
     * is used as a hook: `sign()` is the last thing that runs INSIDE `contentAuth()` before
     * `requestFromOrigin` reads `session` again for `Content-License` -- so calling `stop()` right
     * there reproduces the exact window the review flagged, every time, with no randomness.
     */
    @Test
    fun `a session that disappears mid-request does not crash the thread with an NPE`() {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        lateinit var proxy: LiveHlsProxy
        val signatureThatKillsTheSession = object : SegmentSignature {
            override suspend fun sign(token: String): LiveSignature {
                proxy.stop()  // simulates: the user leaves the player mid-request
                return LiveSignature(1000L, "evil-sig")
            }
        }
        proxy = LiveHlsProxy(signatureThatKillsTheSession)
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        // The URL returned by urlFor() has to be used (with the session token, see Task 19), not
        // rebuilt by hand with the port: without the token, handle() would reject it with 403
        // BEFORE ever reaching the race window this test wants to reproduce.
        val url = proxy.urlFor(session)

        val exceptions = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> exceptions.add(e) }
        try {
            runCatching { read(url) }
            // handle() runs on a separate daemon thread: give it room to finish (with or without
            // an exception) before checking what got captured.
            Thread.sleep(300)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertTrue(
            "a session disappearing mid-request should not throw an uncaught exception " +
                "(would kill the process on Android): $exceptions",
            exceptions.isEmpty(),
        )
        upstream.shutdown()
    }

    /**
     * Finding I1 from the review: the original rewrite only touched lines that literally started
     * with `"http"` and contained `".ts"`. A relative URL (`c_1.ts`) would have resolved against
     * the proxy on a path it doesn't handle (404), and a protocol-relative one (`//other.cdn/...`)
     * would have gone straight to the CDN unsigned.
     */
    @Test
    fun `also rewrites relative and protocol-relative segments`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nc_1.ts\n#EXTINF:6,\n//otro.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (code, body) = read(proxy.urlFor(session))

        assertEquals(200, code)
        // the relative one resolves against the playlist's directory (/live/) and comes out rewritten
        val expectedRelative = "http://${upstream.hostName}:${upstream.port}/live/c_1.ts"
        assertTrue(
            "the relative URL should resolve against /live/ and get rewritten: $body",
            body.contains("seg?u=" + URLEncoder.encode(expectedRelative, "UTF-8")),
        )
        // the protocol-relative one must never be left pointing DIRECTLY at the CDN, unsigned --
        // "otro.cdn" CAN appear encoded INSIDE the proxy's u= parameter, so what matters is that no
        // line starts out pointing straight there.
        assertTrue(
            "the protocol-relative one must not be left unrewritten: $body",
            body.lines().none { it.trim().let { l -> l.startsWith("//otro.cdn") || l.startsWith("http://otro.cdn") } },
        )
        assertEquals(2, body.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    /** An `#EXT-X-KEY` with an absolute URI also has to come out rewritten toward the proxy. */
    @Test
    fun `rewrites the EXT-X-KEY URI so it also comes out signed`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://cdn.key/live/c/key.bin\"\n" +
                "#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (code, body) = read(proxy.urlFor(session))

        assertEquals(200, code)
        val keyLine = body.lines().first { it.startsWith("#EXT-X-KEY") }
        assertTrue(
            "the key's URI should come out rewritten toward the proxy, not straight to the CDN: $keyLine",
            keyLine.contains("URI=\"http://127.0.0.1"),
        )
        // same as above: "cdn.key" can appear encoded inside the proxy's u=, what must not happen
        // is the tag's URI still pointing DIRECTLY there.
        assertTrue(!keyLine.contains("URI=\"http://cdn.key"))
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Leak from Task 14: `AppGraph` created `liveHlsProxy` but nothing closed it on leaving the
     * live channel, so the `ServerSocket` on 127.0.0.1 (and its `accept()` thread) stayed alive for
     * the rest of the process. The real fix lives in `PlaybackService.releaseNetworkResources()`
     * (not testable without Robolectric: it's an `android.app.Service`), so this test verifies the
     * part that CAN be tested in pure JVM: that `stop()` really does release the socket, not just
     * null out a reference.
     */
    @Test
    fun `stop closes the ServerSocket and releases the port`() {
        val proxy = LiveHlsProxy(FakeSignatures())
        val port = proxy.start()
        assertEquals(port, proxy.port)

        proxy.stop()

        assertEquals("port must go back to -1: the ServerSocket no longer exists", -1, proxy.port)
        // The old port must no longer accept connections: if the accept() thread were still alive
        // (the ServerSocket wasn't really closed) this connection would succeed anyway.
        //
        // Polled, not a single attempt: close() returning doesn't guarantee the OS has finished
        // tearing down the listening socket that instant, and a loaded CI runner can lag behind a
        // local machine enough to catch it mid-teardown -- same class of flake as PreWarmSeekTest's
        // CI poll window.
        var stillConnects = true
        for (attempt in 1..20) {
            stillConnects = runCatching { Socket("127.0.0.1", port).close(); true }.getOrDefault(false)
            if (!stillConnects) break
            Thread.sleep(50)
        }
        assertTrue("the old port should not accept connections after stop()", !stillConnects)
    }

    /** `stop()` without ever calling `start()`, and calling it twice in a row, must not throw. */
    @Test
    fun `stop is idempotent`() {
        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.stop() // never started: must not blow up
        assertEquals(-1, proxy.port)

        val port = proxy.start()
        assertTrue(port > 0)
        proxy.stop()
        proxy.stop() // a second time over an already-closed server: must not blow up either
        assertEquals(-1, proxy.port)
    }

    /**
     * Task 18 (Chromecast/DLNA for live): with no channel open yet there's nothing to cast --
     * `lanUrl` must not invent a URL with a port that doesn't even exist.
     */
    @Test
    fun `lanUrl with no channel open returns null`() {
        val proxy = LiveHlsProxy(FakeSignatures())
        assertEquals(null, proxy.lanUrl("192.168.1.50"))
    }

    /**
     * `urlFor` (the real path that opens a channel) has to leave the proxy reachable over the LAN
     * from the first channel -- `lanUrl` reflects the SAME port already recorded in the local URL
     * VLC is consuming (see `urlFor`'s KDoc: there's no "widening" mid-playback, that would change
     * the port and break what's already playing).
     */
    @Test
    fun `lanUrl matches the port of the local url VLC is already using`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlFor(session)

        // The token (Task 19) is the same in both URLs -same proxy session-, only the host
        // changes: extracted from `local` instead of hardcoded, since it's random per run.
        val token = local.substringAfter("?t=")
        val lan = proxy.lanUrl("192.168.1.50")
        assertEquals("http://192.168.1.50:${proxy.port}/live.m3u8?t=$token", lan)
        assertTrue(
            "same path, port and token as the local URL, only the host changes",
            local.endsWith(":${proxy.port}/live.m3u8?t=$token"),
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * The socket `urlFor` opens (bindLan=true, see its KDoc) has to keep accepting loopback
     * connections just like before -- 127.0.0.1 connects the same with the socket listening on
     * every interface, so this changes nothing for local playback.
     */
    @Test
    fun `urlFor stays reachable over loopback after switching to listen on the whole LAN`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, _) = read(proxy.urlFor(session))

        assertEquals(200, code)
        proxy.stop(); upstream.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Task 19 (mitigating the LAN-exposure finding): since start() started listening on the
    // whole LAN instead of loopback only (Task 18, Chromecast/DLNA), the session token is the
    // only access control. These tests cover: 403 with no token / with the wrong token, 200 with
    // the right token (both on the playlist and on the rewritten segment), and that a rejection
    // never throws an uncaught exception.
    // ---------------------------------------------------------------------------------------

    /** Requesting the playlist without the `t` query param has to bounce with 403, serving nothing. */
    @Test
    fun `playlist with no token answers 403`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        proxy.urlFor(session) // starts the server and sets the session; the URL with the token is ignored

        val (code, body) = read("http://127.0.0.1:${proxy.port}/live.m3u8")

        assertEquals(403, code)
        assertTrue("a 403 must not leak anything in the body", body.isEmpty())
        proxy.stop(); upstream.shutdown()
    }

    /** A token that is NOT the current session's (guessed, old, from another process) bounces too. */
    @Test
    fun `playlist with the wrong token answers 403`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        proxy.urlFor(session)

        val (code, _) = read("http://127.0.0.1:${proxy.port}/live.m3u8?t=not-the-token")

        assertEquals(403, code)
        proxy.stop(); upstream.shutdown()
    }

    /** Requesting a segment with no token must not pass either, even if the segment URL is valid. */
    @Test
    fun `segment with no token answers 403 even if the segment URL exists`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (_, body) = read(proxy.urlFor(session))
        // pulls out the /seg?u=...&t=... path the proxy itself generated, and strips the token by hand
        val segmentPathWithToken = body.lineSequence().first { it.startsWith("http://127.0.0.1") }
        val withoutToken = segmentPathWithToken.substringBefore("&t=")

        val (code, _) = read(withoutToken)

        assertEquals(403, code)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * End-to-end happy path: the playlist rewrites the segments WITH the token, and requesting
     * that rewritten URL (exactly as the proxy handed it out, untouched) serves the real segment.
     */
    @Test
    fun `the right token serves both the playlist and the rewritten segment`() = runBlocking {
        val upstream = MockWebServer()
        upstream.start()
        // The playlist's "absolute" segment points at the SAME upstream (not a made-up CDN): that
        // way, when the proxy asks the origin again for the URL it decoded from `u=`, it's a real
        // request the MockWebServer can answer with its second enqueue.
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://${upstream.hostName}:${upstream.port}/live/c/c_1.ts\n"
        ))
        upstream.enqueue(MockResponse().setBody("segment-content"))

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (playlistCode, body) = read(proxy.urlFor(session))
        assertEquals(200, playlistCode)

        val segmentPath = body.lineSequence().first { it.startsWith("http://127.0.0.1") }
        val (segmentCode, segmentBody) = read(segmentPath)

        assertEquals(200, segmentCode)
        assertEquals("segment-content", segmentBody)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Finding from the review (Task 19): the "LAN" tests that already existed (`lanUrl matches the
     * port...`, `urlFor stays reachable over loopback...`) only test the FORMAT of `lanUrl()`'s
     * string or that loopback still works -- neither one proves the `ServerSocket` is REALLY
     * listening on a non-loopback interface. The reviewer proved it themselves: forced the bind to
     * loopback ALWAYS (ignoring `bindLan`) and the whole suite (1068 tests) stayed green.
     *
     * This test requests the playlist over a real non-loopback interface's IP on the machine
     * (`NetworkInterface`, the same source a LAN Chromecast/DLNA device would query) instead of
     * `127.0.0.1`. If the bind were loopback-only, this connection fails with "Connection
     * refused" -the port exists, but doesn't listen on that interface-. If the machine has no
     * active non-loopback interface (some CI sandboxes), the test skips itself: there's no real
     * network to test anything against, and failing over that would be for a reason unrelated to
     * what's being verified.
     */
    @Test
    fun `the proxy is reachable over a real LAN IP, not just lanUrl's string`() = runBlocking {
        val lanIp = reachableNonLoopbackAddress() ?: run {
            // android.util.Log blows up in this pure-JVM test environment (that's why handle()
            // traps it with runCatching); a println is enough to leave a trace that the test
            // skipped for lack of network, without risking an uncaught exception here.
            println("LiveHlsProxyTest: no reachable LAN interface on this machine, skipping the real-reachability test")
            return@runBlocking
        }

        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlFor(session) // bindLan=true inside, see its KDoc

        val urlOverLan = local.replaceFirst("127.0.0.1", lanIp.hostAddress!!)
        val (code, _) = read(urlOverLan)

        assertEquals(
            "the proxy has to be reachable over a non-loopback IP (for Chromecast/DLNA on the " +
                "LAN), not only through the string lanUrl() builds",
            200, code,
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Finding from a previous agent (Task 20), confirmed here: `rewriteLine` always pinned segment
     * URLs' host to `127.0.0.1`, no matter which interface the playlist request came in on. That
     * breaks Chromecast/DLNA: when casting, the receiver requests the playlist over the phone's LAN
     * IP (`lanUrl`), but the segment URLs it gets inside point at `127.0.0.1` -- which for the
     * Chromecast IS ITSELF, not the phone. Black screen, with no error explaining why.
     *
     * This test requests the playlist over a real LAN IP (non-loopback, same helper as the
     * reachability test above) and checks that the rewritten segment URLs use THAT IP, not
     * `127.0.0.1`. With the bug, this assertion fails: the URLs keep pointing at loopback even
     * though the request came in over the LAN.
     */
    @Test
    fun `segment URLs point at the host the playlist was requested through, not always loopback`() = runBlocking {
        val lanIp = reachableNonLoopbackAddress() ?: run {
            println("LiveHlsProxyTest: no reachable LAN interface on this machine, skipping the LAN-host test")
            return@runBlocking
        }

        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://cdn.key/live/c/key.bin\"\n" +
                "#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlFor(session) // bindLan=true inside, see its KDoc

        val urlOverLan = local.replaceFirst("127.0.0.1", lanIp.hostAddress!!)
        val (code, body) = read(urlOverLan)

        assertEquals(200, code)
        assertTrue(
            "the rewritten segment should point at the LAN IP the playlist was requested through, " +
                "not loopback (breaks Chromecast/DLNA): $body",
            body.contains("http://${lanIp.hostAddress}:${proxy.port}/seg?u="),
        )
        assertTrue(
            "no segment URL should be left fixed to 127.0.0.1 when requested over the LAN: $body",
            !body.contains("http://127.0.0.1"),
        )
        val keyLine = body.lines().first { it.startsWith("#EXT-X-KEY") }
        assertTrue(
            "the EXT-X-KEY URI has the same problem: it should also use the LAN IP: $keyLine",
            keyLine.contains("URI=\"http://${lanIp.hostAddress}:${proxy.port}/seg?u="),
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * A non-loopback IPv4 that is REALLY reachable from this machine (not just "the first one
     * `NetworkInterface` enumerates"). Dev or CI machines often have extras: Docker/VM bridges, VPN
     * tunnels (`utunN`), interfaces reporting the NETWORK address instead of a host one (e.g.
     * `172.20.0.0/16` reporting `172.20.0.0`) -- all of them show up "up" and "non-loopback" but a
     * real `connect()` against them blows up with `BindException: Can't assign requested address`,
     * which has NOTHING to do with what this test wants to prove (whether the proxy listens on
     * 0.0.0.0 or not). That's why each candidate is tried with a short real connection against a
     * test socket (the same `ServerSocket(0)` with no IP pattern the real proxy uses) and dropped
     * if it fails, instead of blindly trusting the OS's enumeration order.
     */
    private fun reachableNonLoopbackAddress(): java.net.Inet4Address? {
        val candidates = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
        val probe = java.net.ServerSocket(0)
        return try {
            candidates.firstOrNull { candidate ->
                runCatching {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(candidate, probe.localPort), 300)
                    }
                }.isSuccess
            }
        } finally {
            probe.close()
        }
    }
    /**
     * THE 2026-08-14 BUG. The signal isn't always called on the CDN the same as the channel:
     * `cyx-RCNHD` is served as `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`. Asking the CDN for the channel's
     * code, the path didn't match the signal the license we sent authorizes, and it answered 401 --
     * the channel stayed loading forever. The ones that worked were exactly those where `playCode`
     * and `channel` coincide, which is what hid it.
     */
    @Test
    fun `the playlist is requested from the CDN by playCode, not by the channel's code`() {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:6,\nseg1.ts\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "cyx-RCNHD", expiresAt = 0,
            playCode = "cyx-2EF7E10E40C1ac19D6A9F3ED4CD2",
        )
        read(proxy.urlFor(session))

        assertEquals(
            "/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8",
            upstream.takeRequest().path,
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * THE CHANNEL DOESN'T DIE IF ONE CDN REJECTS. Measured on 2026-08-14: `getSlbInfo` returns
     * THREE live CDNs and only the first was being used; that day it answered 401 twice in a row
     * and the channel ended up (`EndReached`) with another host available in that same portal
     * response.
     *
     * Each CDN goes with ITS OWN `authBase`, because the token travels inside that url: signing
     * with one's token against another's host is exactly the crossed pair the CDN rejects.
     */
    @Test
    fun `if the first CDN rejects, the playlist is requested from the next one`() {
        val bad = MockWebServer()
        repeat(4) { bad.enqueue(MockResponse().setResponseCode(401)) }
        bad.start()
        val good = MockWebServer()
        good.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:6,\nseg1.ts\n"))
        good.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${bad.hostName}:${bad.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
            cdns = listOf(
                ChannelCdn("${bad.hostName}:${bad.port}", "http://x/?a=1&token=${"A".repeat(32)}"),
                ChannelCdn("${good.hostName}:${good.port}", "http://x/?a=2&token=${"B".repeat(32)}"),
            ),
        )
        val (code, body) = read(proxy.urlFor(session))

        assertEquals("the second CDN served the playlist, this can't come out 502", 200, code)
        assertTrue("the body has to come from the good CDN", body.contains("127.0.0.1"))
        assertTrue("the good CDN had to receive the request", good.requestCount >= 1)
        proxy.stop(); bad.shutdown(); good.shutdown()
    }

    /** With a single CDN, everything stays the same as always: a rejection is a 502 to the player. */
    @Test
    fun `with a single CDN, a rejection is still a 502`() {
        val bad = MockWebServer()
        repeat(4) { bad.enqueue(MockResponse().setResponseCode(401)) }
        bad.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${bad.hostName}:${bad.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (code, _) = read(proxy.urlFor(session))

        assertEquals(502, code)
        proxy.stop(); bad.shutdown()
    }

    // -----------------------------------------------------------------------------------------
    // Segments the CDN hasn't published yet (404 at the live edge).
    //
    // Measured on the Fire TV on 2026-08-14 with RCN FHD: VLC requested three ~5s video segments
    // 1.3s apart -- it was running toward the live edge -- and the third gave 404 because it didn't
    // exist yet. The proxy used to write the header with the CDN's code as-is and then try to copy
    // `inputStream`, which throws on a 404: the player received ZERO bytes. An empty body looks
    // exactly like the end of a stream, so VLC drained the decoder and fired EndReached at 19s with
    // the channel perfectly alive (the playlist kept refreshing, seq 2080 -> 2082).
    // -----------------------------------------------------------------------------------------

    /** Returns the segment URL already rewritten by the proxy (with its token), from the playlist. */
    private fun segmentUrl(proxy: LiveHlsProxy, session: LiveSession): String {
        val (code, body) = read(proxy.urlFor(session))
        assertEquals(200, code)
        return body.lineSequence().first { it.startsWith("http://127.0.0.1") }
    }

    @Test
    fun `a segment not published yet gets retried and ends up served`() = runBlocking {
        val upstream = MockWebServer()
        var segmentRequests = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith(".m3u8")) {
                    return MockResponse().setBody(
                        "#EXTM3U\n#EXTINF:6,\nhttp://${upstream.hostName}:${upstream.port}/live/c/c_1.ts\n"
                    )
                }
                segmentRequests++
                // Not there on the first request; the CDN publishes it a moment later.
                return if (segmentRequests < 2) {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse().setBody("segment-content")
                }
            }
        }
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, body) = read(segmentUrl(proxy, session))

        assertEquals(200, code)
        assertEquals("segment-content", body)
        assertEquals("it had to have retried", 2, segmentRequests)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * THE test for this fix: no matter what happens, the player NEVER receives an empty body
     * behind a non-error header. A 502 gets retried; zero bytes are read as the end of the stream
     * and the channel dies.
     */
    @Test
    fun `a segment that never shows up answers 502, not an empty body`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith(".m3u8")) {
                    MockResponse().setBody(
                        "#EXTM3U\n#EXTINF:6,\nhttp://${upstream.hostName}:${upstream.port}/live/c/c_1.ts\n"
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, body) = read(segmentUrl(proxy, session))

        assertEquals(502, code)
        assertTrue("a 200 with an empty body is indistinguishable from the end of the stream", body.isEmpty())
        proxy.stop(); upstream.shutdown()
    }

    /**
     * The backup was there and unused: the playlist path walks every CDN, but the segment path
     * stuck to the active one and a single 404 killed the channel.
     */
    @Test
    fun `if the active CDN doesn't have the segment, it's looked up on the channel's other CDN`() = runBlocking {
        val first = MockWebServer()
        first.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith(".m3u8")) {
                    MockResponse().setBody(
                        "#EXTM3U\n#EXTINF:6,\nhttp://${first.hostName}:${first.port}/live/c/c_1.ts\n"
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        first.start()
        val second = MockWebServer()
        second.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody("backup-segment")
        }
        second.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val auth = "http://x/?a=1&token=${"A".repeat(32)}"
        val session = LiveSession(
            cflHost = "${first.hostName}:${first.port}",
            authBase = auth, license = "LIC", channel = "c", expiresAt = 0,
            cdns = listOf(
                ChannelCdn("${first.hostName}:${first.port}", auth),
                ChannelCdn("${second.hostName}:${second.port}", auth),
            ),
        )
        val (code, body) = read(segmentUrl(proxy, session))

        assertEquals(200, code)
        assertEquals("backup-segment", body)
        proxy.stop(); first.shutdown(); second.shutdown()
    }

    // -----------------------------------------------------------------------------------------
    // The playlist gets retried too. On 2026-08-14 `cyx-RCNHD`'s origin answered 404 to the
    // playlist four times over 9s, with the channel playing fine until second 39, and came back on
    // its own. Before, that was a 502 on the first attempt and without ever trying the backup CDN.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a playlist that fails and then comes back is still served`() = runBlocking {
        val upstream = MockWebServer()
        var requests = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                return if (requests < 3) MockResponse().setResponseCode(404)
                else MockResponse().setBody("#EXTM3U\n#EXTINF:5,\nhttp://cdn/live/c/c_1.ts\n")
            }
        }
        upstream.start()

        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (code, body) = read(proxy.urlFor(session))

        assertEquals(200, code)
        assertTrue(body.contains("#EXTM3U"))
        assertEquals("two 404s and then the good one", 3, requests)
        proxy.stop(); upstream.shutdown()
    }

    /** A 404 is not a rejected signature: asking the gateway for the session again would treat a pothole as an expired credential. */
    @Test
    fun `a playlist with 404 does not give the session up for dead`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        upstream.start()

        val dead = CopyOnWriteArrayList<String>()
        val proxy = LiveHlsProxy(FakeSignatures(), onSessionDead = { dead.add(it) })
        proxy.start()
        val session = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "canal-x", 0)
        val (code, _) = read(proxy.urlFor(session))

        assertEquals(502, code)
        assertEquals("a 404 from the CDN is not an expired session", emptyList<String>(), dead)
        proxy.stop(); upstream.shutdown()
    }

    /** A 404 from the active CDN has to move on to the next one, same as a signature rejection. */
    @Test
    fun `if the active CDN gives a 404 on the playlist, the other one is tried`() = runBlocking {
        val bad = MockWebServer()
        bad.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        bad.start()
        val good = MockWebServer()
        good.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n")
        }
        good.start()

        val auth = "http://x/?a=1&token=${"A".repeat(32)}"
        val proxy = LiveHlsProxy(FakeSignatures())
        proxy.start()
        val session = LiveSession(
            cflHost = "${bad.hostName}:${bad.port}", authBase = auth,
            license = "LIC", channel = "c", expiresAt = 0,
            cdns = listOf(
                ChannelCdn("${bad.hostName}:${bad.port}", auth),
                ChannelCdn("${good.hostName}:${good.port}", auth),
            ),
        )
        val (code, body) = read(proxy.urlFor(session))

        assertEquals(200, code)
        assertTrue(body.contains("#EXT-X-MEDIA-SEQUENCE:7"))
        proxy.stop(); bad.shutdown(); good.shutdown()
    }
}
