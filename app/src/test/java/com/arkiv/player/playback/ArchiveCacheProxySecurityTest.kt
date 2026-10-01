package com.arkiv.player.playback

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * The cast proxy listens on the LAN, so it must not be an open relay: every request needs a live
 * token, a token names exactly one registered stream, the stream's headers never reach a URL, and
 * no upstream hop -- redirects included -- may land on a private address.
 */
class ArchiveCacheProxySecurityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy
    private var now = 1_000_000L
    private val tokens = ProxyTokens(idleTtlMs = 60_000L, maxAgeMs = 600_000L, clock = { now })

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"), ProxyOriginGuard(allowLoopback = true), tokens)
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origin.shutdown()
    }

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 10_000
        }
        val code = c.responseCode
        val data = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } }
            .getOrNull() ?: ByteArray(0)
        c.disconnect()
        return code to data
    }

    private fun okBody(n: Int) = MockResponse().setBody(okio.Buffer().write(ByteArray(n) { 7 }))

    @Test
    fun `no token is a 403 and the origin is never contacted`() {
        val target = URLEncoder.encode(origin.url("/secret").toString(), "UTF-8")
        // Both the old open-relay shape and a bare path.
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/s?d=1&u=$target").first)
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/hls.m3u8?u=$target").first)
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/").first)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `a wrong token is a 403`() {
        proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true)
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/t/${"0".repeat(32)}/s").first)
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/t/short/s").first)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `an idle-expired token is a 403`() {
        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true)
        now += 60_001L
        assertEquals(403, get(url).first)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `a token past its max age is a 403 even if it kept being used`() {
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = okBody(100)
        }
        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true)
        repeat(11) {
            now += 59_000L
            if (it < 10) assertEquals(200, get(url).first)
        }
        assertEquals(403, get(url).first)
    }

    @Test
    fun `stop revokes every token`() {
        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true)
        proxy.stop()
        proxy.start()
        val sameTokenNewPort = url.replace(Regex(":\\d+/"), ":${proxy.port}/")
        assertEquals(403, get(sameTokenNewPort).first)
    }

    @Test
    fun `revoke ends one url`() {
        val url = proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true)
        proxy.revoke(url)
        assertEquals(403, get(url).first)
    }

    @Test
    fun `a token only ever fetches the stream it was registered for`() {
        origin.enqueue(okBody(64))
        val url = proxy.proxyUrl(origin.url("/registered.ts").toString(), mapOf("Content-Auth" to "A"), direct = true)
        // A request trying to smuggle another origin and other headers in the old query params.
        val evil = URLEncoder.encode(origin.url("/other").toString(), "UTF-8")
        val (code, _) = get("$url?h=eyJYIjoiMSJ9&u=$evil")
        assertEquals(200, code)
        val seen = origin.takeRequest()
        assertEquals("/registered.ts", seen.path)
        assertEquals("A", seen.getHeader("Content-Auth"))
        assertNull(seen.getHeader("X"))
        assertEquals(1, origin.requestCount)
    }

    @Test
    fun `a redirect to a private address is refused and never contacted`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.11.12.13:9/router-admin"))
        val (code, _) = get(proxy.proxyUrl(origin.url("/v.ts").toString(), direct = true))
        assertEquals(502, code)
        // One request to the origin: the refusal is not retried.
        assertEquals(1, origin.requestCount)
    }

    @Test
    fun `a redirect to a public host name that resolves into the LAN is refused`() {
        val guard = ProxyOriginGuard(allowLoopback = true) { host ->
            if (host == "cdn.example.com") listOf(InetAddress.getByName("192.168.1.1")) else InetAddress.getAllByName(host).toList()
        }
        val p = ArchiveCacheProxy(temp.newFolder("cache2"), guard)
        p.start()
        try {
            origin.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://cdn.example.com/x.ts"))
            assertEquals(502, get(p.proxyUrl(origin.url("/v.ts").toString(), direct = true)).first)
            assertEquals(1, origin.requestCount)
        } finally {
            p.stop()
        }
    }

    @Test
    fun `a redirect to a public origin is still followed, with the headers`() {
        // Loopback stands in for "public" here (allowLoopback): what's checked is the hand-made hop.
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", origin.url("/moved.ts").toString()))
        origin.enqueue(okBody(32))
        val (code, body) = get(proxy.proxyUrl(origin.url("/v.ts").toString(), mapOf("Content-Auth" to "A"), direct = true))
        assertEquals(200, code)
        assertEquals(32, body.size)
        origin.takeRequest()
        val second = origin.takeRequest()
        assertEquals("/moved.ts", second.path)
        assertEquals("A", second.getHeader("Content-Auth"))
    }

    @Test
    fun `the guard refuses private, loopback and link-local origins by default`() {
        val guard = ProxyOriginGuard()
        for (u in listOf(
            "http://127.0.0.1:8080/x", "http://10.0.0.1/x", "http://192.168.2.1/x", "http://172.16.0.1/x",
            "http://169.254.169.254/latest", "http://[::1]/x", "http://[fd00::1]/x", "http://100.64.0.1/x",
            "file:///etc/hosts",
        )) {
            assertNotNull(u, guard.refusal(URL(u)))
        }
        assertNull(guard.refusal(URL("https://8.8.8.8/x")))
    }

    /** The Cast receiver plays `/hls.m3u8`; each segment it lists must carry the same token. */
    @Test
    fun `segment urls inside the served playlist carry the token and play`() {
        val data = tsStream(seconds = 120)
        origin.dispatcher = rangeServer(data)
        val local = proxy.proxyUrl(origin.url("/v.ts").toString(), mapOf("Content-Auth" to "SECRET"), direct = true)
        val token = ArchiveCacheProxy.tokenIn(local.substringAfter(":${proxy.port}"))!!
        val playlistUrl = ArchiveCacheProxy.lanPlaylistUrl(local, "127.0.0.1")!!

        val (code, body) = get(playlistUrl)
        assertEquals(200, code)
        val playlist = String(body)
        val uris = playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("the playlist lists segments", uris.size >= 2)
        uris.forEach { assertTrue(it, it.startsWith("/t/$token/seg?n=")) }
        assertFalse("no headers in the playlist", playlist.contains("SECRET") || playlist.contains("Content-Auth"))
        assertFalse("no origin in the playlist", playlist.contains("/v.ts"))

        // And the receiver's segment request, resolved like it would, plays.
        val (segCode, seg) = get("http://127.0.0.1:${proxy.port}${uris[1]}")
        assertEquals(200, segCode)
        assertTrue(seg.isNotEmpty())
        // Without the token the same segment is refused.
        assertEquals(403, get("http://127.0.0.1:${proxy.port}/seg?n=1").first)
    }

    @Test
    fun `the headers never appear in any url handed out`() {
        val headers = mapOf("Content-Auth" to "AUTH-VALUE-123", "Content-License" to "LIC-VALUE-456")
        val local = proxy.proxyUrl("https://cdn.example.com/v.ts", headers, direct = true)
        val urls = listOfNotNull(
            local,
            ArchiveCacheProxy.lanUrl(local, "192.168.2.20"),
            ArchiveCacheProxy.lanPlaylistUrl(local, "192.168.2.20"),
            ArchiveCacheProxy.withFraction(local, 0.5f),
        )
        for (u in urls) {
            for ((k, v) in headers) {
                assertFalse(u, u.contains(k) || u.contains(v))
                assertFalse(u, u.contains(URLEncoder.encode(v, "UTF-8")))
            }
            assertFalse("no origin either: $u", u.contains("cdn.example.com"))
        }
    }

    /** What reaches the log is a URL through `DlnaXml.safeUrl`: the session token is masked. */
    @Test
    fun `safeUrl masks the session token`() {
        val local = proxy.proxyUrl("https://cdn.example.com/v.ts", mapOf("A" to "b"), direct = true)
        val token = ArchiveCacheProxy.tokenIn(local.substringAfter(":${proxy.port}"))!!
        val logged = com.arkiv.player.dlna.DlnaXml.safeUrl(ArchiveCacheProxy.lanPlaylistUrl(local, "192.168.2.20"))
        assertFalse(logged, logged.contains(token))
        assertTrue(logged, logged.endsWith("/t/…/hls.m3u8"))
    }

    /**
     * The proxy's own log lines: none may interpolate the stream's headers, the raw request path
     * (it holds the token) or the token. Read off the source because unit tests stub `android.util.Log`.
     */
    @Test
    fun `no log line in the proxies interpolates headers or tokens`() {
        val forbidden = Regex("""\$\{?(headers|extraHeaders|stream\.headers|fullPath|token|reqLine|requestLine)\b""")
        val sources = listOf(
            "src/main/java/com/arkiv/player/playback/ArchiveCacheProxy.kt",
            "src/main/java/com/arkiv/player/playback/ProxyOriginGuard.kt",
            "src/main/java/com/arkiv/player/dlna/DlnaProxyServer.kt",
        ).map { path -> File(path).takeIf { it.exists() } ?: File("app/$path") }
        for (file in sources) {
            assertTrue("missing ${file.path}", file.exists())
            val text = file.readText()
            Regex("""Log\.[a-z]\(([\s\S]*?)\)\s*\n""").findAll(text).forEach { call ->
                assertNull("${file.name}: ${call.value.take(160)}", forbidden.find(call.groupValues[1]))
            }
        }
    }

    // --- a synthetic transport stream and an origin that honors Range --------------------------

    private fun tsStream(seconds: Int, packetsPerSecond: Int = 100): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until seconds * packetsPerSecond) {
            val pcr = 90_000L * i / packetsPerSecond
            val p = ByteArray(188)
            p[0] = 0x47
            if (i % 10 == 0) {
                p[1] = 0x01; p[2] = 0x00
                p[3] = 0x20
                p[4] = 7
                // PCR flag, plus random access every 2 s so segments can start on a keyframe.
                p[5] = (0x10 or if (i % (2 * packetsPerSecond) == 0) 0x40 else 0).toByte()
                p[6] = ((pcr shr 25) and 0xFF).toByte()
                p[7] = ((pcr shr 17) and 0xFF).toByte()
                p[8] = ((pcr shr 9) and 0xFF).toByte()
                p[9] = ((pcr shr 1) and 0xFF).toByte()
                p[10] = ((pcr and 1L) shl 7).toByte()
            } else {
                p[1] = 0x01; p[2] = 0x01
                p[3] = 0x10
            }
            out.write(p)
        }
        return out.toByteArray()
    }

    private fun rangeServer(data: ByteArray) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")?.removePrefix("bytes=")
                ?: return MockResponse().setBody(okio.Buffer().write(data))
            val total = data.size.toLong()
            val (from, to) = if (range.startsWith("-")) {
                val n = range.drop(1).toLong()
                (total - n).coerceAtLeast(0) to total - 1
            } else {
                val a = range.substringBefore('-').toLong()
                val b = range.substringAfter('-').toLongOrNull() ?: (total - 1)
                a to minOf(b, total - 1)
            }
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-$to/$total")
                .setBody(okio.Buffer().write(data.copyOfRange(from.toInt(), to.toInt() + 1)))
        }
    }
}
