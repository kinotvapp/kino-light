package com.arkiv.player.data.local

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS downloads: the playlist parser (master/media, byte ranges, init map, keys, endlist), the
 * variant choice, AES-128, the TS/fMP4 output, resume at segment granularity and the refusals.
 */
class HlsDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val routes = HashMap<String, () -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes[request.path!!]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()
    private fun body(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))
    private fun text(s: String) = MockResponse().setBody(s)

    /** [packets] MPEG-TS packets whose payload bytes are all [n]. */
    private fun ts(n: Int, packets: Int = 3): ByteArray = ByteArray(packets * 188) { i -> if (i % 188 == 0) 0x47 else n.toByte() }

    private fun downloader(freeSpace: Long = Long.MAX_VALUE, concurrency: Int = 4) =
        HlsDownloader(OkHttpClient(), freeSpace = { freeSpace }, concurrency = concurrency, attempts = 1, retryDelayMs = 0)

    /** Every file the download of "ep" left in the directory. */
    private fun leftovers() = tmp.root.list()!!.filter { it.startsWith("ep.") }.sorted()

    private fun HlsDownloader.run(path: String, headers: Map<String, String> = emptyMap(), progress: MutableList<Pair<Long, Long>> = mutableListOf()) =
        runBlocking { download(url(path), headers, tmp.root, "ep", resumeKey = "ep-1") { d, t -> progress += d to t } }

    // ---- parser -------------------------------------------------------------------------------

    @Test fun `a master playlist lists its variants and audio renditions with absolute URIs`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="es",URI="audio/es.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            https://other.example/1080.m3u8
        """.trimIndent()
        val master = (HlsPlaylistParser.parse(text, "https://cdn.example/v/master.m3u8") as HlsPlaylist.Master).playlist
        assertEquals(listOf("https://cdn.example/v/360/index.m3u8", "https://other.example/1080.m3u8"), master.variants.map { it.uri })
        assertEquals(listOf(360, 1080), master.variants.map { it.height })
        assertEquals("avc1.4d401e,mp4a.40.2", master.variants[0].codecs)
        assertEquals(listOf(HlsAudioRendition("aud", "https://cdn.example/v/audio/es.m3u8")), master.audio)
    }

    @Test fun `a media playlist reads durations, sequence, byte ranges, the init map, keys and endlist`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="700@0"
            #EXT-X-KEY:METHOD=AES-128,URI="k1.key"
            #EXTINF:4.5,
            #EXT-X-BYTERANGE:1000@700
            all.mp4
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:500
            all.mp4
            #EXT-X-KEY:METHOD=AES-128,URI="k2.key",IV=0x0000000000000000000000000000000A
            #EXT-X-DISCONTINUITY
            #EXTINF:2,
            c.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:1,
            d.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = (HlsPlaylistParser.parse(text, "https://cdn.example/v/index.m3u8") as HlsPlaylist.Media).playlist
        assertTrue(media.endList)
        assertEquals(listOf(7L, 8L, 9L, 10L), media.segments.map { it.sequence })
        assertEquals(11.5, media.totalDurationSec, 0.0001)
        assertEquals(HlsByteRange(1000, 700), media.segments[0].range)
        assertEquals("the implicit offset follows the previous range of the same resource", HlsByteRange(500, 1700), media.segments[1].range)
        assertEquals("bytes=1700-2199", media.segments[1].range!!.header())
        assertEquals(HlsInit("https://cdn.example/v/init.mp4", HlsByteRange(700, 0)), media.init)
        assertEquals("https://cdn.example/v/k1.key", media.segments[0].key!!.uri)
        assertNull(media.segments[0].key!!.iv)
        assertEquals(10, media.segments[2].key!!.iv!!.last().toInt())
        assertTrue(media.segments[2].discontinuity)
        assertNull("METHOD=NONE ends the encryption", media.segments[3].key)
    }

    @Test fun `SAMPLE-AES and non-identity key formats are refused, not HLS is refused`() {
        assertThrows(HlsRefusedException::class.java) {
            HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:4,\na.ts\n", "https://c.example/i.m3u8")
        }
        assertThrows(HlsRefusedException::class.java) {
            HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"com.widevine\"\n#EXTINF:4,\na.ts\n", "https://c.example/i.m3u8")
        }
        assertThrows(HlsRefusedException::class.java) { HlsPlaylistParser.parse("<MPD/>", "https://c.example/i.mpd") }
    }

    @Test fun `the plan refuses live, empty and map-changing playlists`() {
        fun media(s: String) = (HlsPlaylistParser.parse(s, "https://c.example/i.m3u8") as HlsPlaylist.Media).playlist
        assertThrows(HlsRefusedException::class.java) { HlsDownloadPlan.check(media("#EXTM3U\n#EXTINF:4,\na.ts\n")) }
        assertThrows(HlsRefusedException::class.java) { HlsDownloadPlan.check(media("#EXTM3U\n#EXT-X-ENDLIST\n")) }
        assertThrows(HlsRefusedException::class.java) {
            HlsDownloadPlan.check(media("#EXTM3U\n#EXT-X-MAP:URI=\"i1.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-MAP:URI=\"i2.mp4\"\n#EXTINF:4,\nb.m4s\n#EXT-X-ENDLIST\n"))
        }
        HlsDownloadPlan.check(media("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"))
    }

    // ---- variant choice -----------------------------------------------------------------------

    private fun variant(h: Int?, bw: Long, audio: String? = null, codecs: String? = null) = HlsVariant("https://c/$h-$bw", bw, h, codecs, audio)

    @Test fun `the highest variant up to 1080p is picked, else the lowest above it`() {
        val pick = HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(480, 1), variant(2160, 9), variant(1080, 5), variant(1080, 6), variant(720, 3)), emptyList()))
        assertEquals(1080 to 6L, pick.height to pick.bandwidth)
        val above = HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(2160, 9), variant(1440, 7)), emptyList()))
        assertEquals(1440, above.height)
        // No resolution declared: by bandwidth.
        assertEquals(8L, HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(null, 2), variant(null, 8)), emptyList())).bandwidth)
    }

    @Test fun `a variant with muxed audio wins over a better one whose audio is separate, none at all is refused`() {
        val audio = listOf(HlsAudioRendition("sep", "https://c/a.m3u8"), HlsAudioRendition("in", null))
        val pick = HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(1080, 9, "sep"), variant(720, 3, "in")), audio))
        assertEquals(720, pick.height)
        assertThrows(HlsRefusedException::class.java) {
            HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(1080, 9, "sep"), variant(720, 3, "sep")), audio))
        }
        // An audio-only variant is never the video.
        assertThrows(HlsRefusedException::class.java) {
            HlsVariantPicker.pick(HlsMasterPlaylist(listOf(variant(null, 1, codecs = "mp4a.40.2")), emptyList()))
        }
    }

    // ---- AES-128, TS sync, sidx, resume -------------------------------------------------------

    @Test fun `AES-128 CBC decrypts the NIST SP800-38A vector, IV from the media sequence by default`() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("6bc1bee22e409f96e93d7e117393172a")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(plain)
        assertArrayEquals(hex("7649abac8119b246cee98e9b12e9197d"), cipher.copyOf(16))
        assertArrayEquals(plain, HlsCrypto.decrypting(cipher.inputStream(), key, iv).readBytes())

        val seg = HlsSegment("https://c/a.ts", 4.0, sequence = 258, key = HlsKey("https://c/k", null))
        assertArrayEquals(ByteBuffer.allocate(16).putLong(8, 258).array(), HlsCrypto.ivFor(seg))
        val explicit = seg.copy(key = HlsKey("https://c/k", iv))
        assertArrayEquals(iv, HlsCrypto.ivFor(explicit))
    }

    @Test fun `TS packets are found after a disguise header`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x47, 1, 2)
        assertEquals(png.size, TsSync.start(png + ts(1)))
        assertEquals(0, TsSync.start(ts(1)))
        assertEquals(-1, TsSync.start("<html>not video</html>".toByteArray()))
    }

    @Test fun `the sidx box carries one reference per segment`() {
        val box = Mp4Sidx.build(listOf(1000L, 2000L), listOf(4.0, 2.5))
        assertEquals(Mp4Sidx.size(2), box.size)
        val b = ByteBuffer.wrap(box)
        assertEquals(box.size, b.getInt(0))
        assertEquals("sidx", String(box, 4, 4))
        assertEquals(1000, b.getInt(16)) // timescale
        assertEquals(2, b.getShort(30).toInt()) // reference_count
        assertEquals(1000, b.getInt(32)); assertEquals(4000, b.getInt(36))
        assertEquals(2000, b.getInt(44)); assertEquals(2500, b.getInt(48))
    }

    @Test fun `resume only from a state of the same content whose bytes are still on disk`() {
        val s = HlsResumeState("ep", "3:12:ts", 2, 300, listOf(100, 200), 0)
        assertEquals(s, HlsResumeState.fromJson(s.toJson()))
        assertEquals(s, HlsResumeState.resumable(s, "ep", "3:12:ts", partLength = 350, segmentCount = 3))
        assertNull("another episode", HlsResumeState.resumable(s, "other", "3:12:ts", 350, 3))
        assertNull("another rendition or cut", HlsResumeState.resumable(s, "ep", "4:16:ts", 350, 3))
        assertNull("the part lost bytes", HlsResumeState.resumable(s, "ep", "3:12:ts", 299, 3))
        assertNull("no part at all", HlsResumeState.resumable(s, "ep", "3:12:ts", -1, 3))
        assertNull("inconsistent", HlsResumeState.resumable(s.copy(partBytes = 301), "ep", "3:12:ts", 400, 3))
        assertNull(HlsResumeState.fromJson("garbage"))
    }

    // ---- the downloader against a server ------------------------------------------------------

    @Test fun `master to TS file, headers on every request, disguise stripped, progress to 100 percent`() {
        routes["/master.m3u8"] = { text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000,RESOLUTION=1280x720\nhd/index.m3u8\n") }
        routes["/hd/index.m3u8"] = { text("#EXTM3U\n" + (0 until 6).joinToString("") { "#EXTINF:4,\ns$it.png\n" } + "#EXT-X-ENDLIST\n") }
        val png = "\u0089PNG\r\n".toByteArray()
        (0 until 6).forEach { i -> routes["/hd/s$i.png"] = { body(png + ts(i)) } }
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = downloader(concurrency = 4).run("/master.m3u8", mapOf("Referer" to "https://site.example/", "User-Agent" to "UA/1"), progress).getOrThrow()

        assertEquals("ep.ts", file.name)
        assertArrayEquals((0 until 6).map { ts(it) }.reduce { a, b -> a + b }, file.readBytes())
        assertTrue(requests.none { it.path == "/low.m3u8" })
        assertTrue(requests.all { it.getHeader("Referer") == "https://site.example/" && it.getHeader("User-Agent") == "UA/1" })
        assertEquals(file.length() to file.length(), progress.last())
        assertEquals(listOf("ep.ts"), tmp.root.list()!!.toList())
    }

    @Test fun `AES-128 segments are decrypted with the key fetched once, IV from the sequence`() {
        val key = ByteArray(16) { it.toByte() }
        fun enc(plain: ByteArray, seq: Long) = Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteBuffer.allocate(16).putLong(8, seq).array())) }
            .doFinal(plain)
        routes["/i.m3u8"] = { text("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:40\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n") }
        routes["/k.bin"] = { body(key) }
        routes["/a.ts"] = { body(enc(ts(1), 40)) }
        routes["/b.ts"] = { body(enc(ts(2), 41)) }

        val file = downloader().run("/i.m3u8", mapOf("X-Auth" to "t")).getOrThrow()

        assertArrayEquals(ts(1) + ts(2), file.readBytes())
        assertEquals(1, requests.count { it.path == "/k.bin" })
        assertEquals("t", requests.first { it.path == "/k.bin" }.getHeader("X-Auth"))
    }

    @Test fun `fMP4 with a byte-range init and byte-range fragments becomes init, sidx and fragments in an mp4`() {
        val init = "INITINIT".toByteArray()
        val frags = listOf("FRAG-ONE-".toByteArray(), "FRAG-TWO--".toByteArray())
        val all = init + frags[0] + frags[1]
        routes["/f.m3u8"] = {
            text("#EXTM3U\n#EXT-X-MAP:URI=\"all.mp4\",BYTERANGE=\"8@0\"\n#EXTINF:4,\n#EXT-X-BYTERANGE:9@8\nall.mp4\n#EXTINF:2,\n#EXT-X-BYTERANGE:10\nall.mp4\n#EXT-X-ENDLIST\n")
        }
        routes["/all.mp4"] = { body(all) } // ignores Range: the downloader takes the slice itself

        val file = downloader().run("/f.m3u8").getOrThrow()

        assertEquals("ep.mp4", file.name)
        val bytes = file.readBytes()
        assertArrayEquals(init, bytes.copyOf(8))
        assertArrayEquals(Mp4Sidx.build(listOf(9L, 10L), listOf(4.0, 2.0)), bytes.copyOfRange(8, 8 + Mp4Sidx.size(2)))
        assertArrayEquals(frags[0] + frags[1], bytes.copyOfRange(8 + Mp4Sidx.size(2), bytes.size))
        assertEquals(setOf("bytes=0-7", "bytes=8-16", "bytes=17-26"), requests.filter { it.path == "/all.mp4" }.mapNotNull { it.getHeader("Range") }.toSet())
    }

    @Test fun `a failed segment keeps what was appended, and the retry resumes at the first missing segment`() {
        routes["/r.m3u8"] = { text("#EXTM3U\n" + (0 until 5).joinToString("") { "#EXTINF:4,\nr$it.ts\n" } + "#EXT-X-ENDLIST\n") }
        (0 until 5).forEach { i -> routes["/r$i.ts"] = { body(ts(i)) } }
        routes["/r3.ts"] = { MockResponse().setResponseCode(503) }

        val first = downloader(concurrency = 2).run("/r.m3u8")
        assertTrue(first.isFailure)
        assertTrue(DownloadRetryPolicy.isTransient(first.exceptionOrNull()!!))
        assertTrue(File(tmp.root, "ep.hls.part").exists())
        assertFalse("no raw segment is left behind", tmp.root.list()!!.any { it.startsWith("ep.hls.seg") })

        routes["/r3.ts"] = { body(ts(3)) }
        requests.clear()
        val file = downloader(concurrency = 2).run("/r.m3u8").getOrThrow()

        assertArrayEquals((0 until 5).map { ts(it) }.reduce { a, b -> a + b }, file.readBytes())
        assertEquals("segments 0-1 were kept", listOf("/r.m3u8", "/r0.ts", "/r2.ts", "/r3.ts", "/r4.ts"), requests.mapNotNull { it.path }.sorted())
        assertEquals("segment 0 is only probed for the fingerprint", "bytes=0-16383", requests.single { it.path == "/r0.ts" }.getHeader("Range"))
        assertEquals(listOf("ep.ts"), tmp.root.list()!!.toList())
    }

    @Test fun `the same content from another CDN still resumes`() {
        val cdn = { host: String -> "#EXTM3U\n" + (0 until 4).joinToString("") { "#EXTINF:4,\n$host/s$it.ts\n" } + "#EXT-X-ENDLIST\n" }
        (0 until 4).forEach { i -> routes["/a/s$i.ts"] = { body(ts(i)) }; routes["/b/s$i.ts"] = { body(ts(i)) } }
        routes["/a/s2.ts"] = { MockResponse().setResponseCode(503) }
        routes["/p.m3u8"] = { text(cdn("a")) }
        assertTrue(downloader(concurrency = 2).run("/p.m3u8").isFailure)
        routes["/p.m3u8"] = { text(cdn("b")) }
        requests.clear()
        val file = downloader(concurrency = 2).run("/p.m3u8").getOrThrow()
        assertArrayEquals((0 until 4).map { ts(it) }.reduce { a, b -> a + b }, file.readBytes())
        assertTrue("segment 1 was not fetched again", requests.none { it.path == "/b/s1.ts" })
    }

    @Test fun `another TS encode with the same count and duration starts over instead of splicing`() {
        routes["/e.m3u8"] = { text("#EXTM3U\n" + (0 until 4).joinToString("") { "#EXTINF:4,\ne$it.ts\n" } + "#EXT-X-ENDLIST\n") }
        (0 until 4).forEach { i -> routes["/e$i.ts"] = { body(ts(i)) } }
        routes["/e2.ts"] = { MockResponse().setResponseCode(503) }
        assertTrue(downloader(concurrency = 2).run("/e.m3u8").isFailure)
        // The retry resolved to another mirror: same playlist shape, other bytes.
        (0 until 4).forEach { i -> routes["/e$i.ts"] = { body(ts(i + 10)) } }
        val file = downloader(concurrency = 2).run("/e.m3u8").getOrThrow()
        assertArrayEquals((10 until 14).map { ts(it) }.reduce { a, b -> a + b }, file.readBytes())
    }

    @Test fun `another fMP4 init or another variant starts over instead of splicing`() {
        routes["/f.m3u8"] = { text("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nf0.m4s\n#EXTINF:4,\nf1.m4s\n#EXT-X-ENDLIST\n") }
        routes["/init.mp4"] = { body("INIT-A".toByteArray()) }
        routes["/f0.m4s"] = { body("OLD0".toByteArray()) }
        assertTrue(downloader(concurrency = 1).run("/f.m3u8").isFailure) // f1 missing
        routes["/init.mp4"] = { body("INIT-B".toByteArray()) }
        routes["/f0.m4s"] = { body("NEW0".toByteArray()) }
        routes["/f1.m4s"] = { body("NEW1".toByteArray()) }
        val bytes = downloader(concurrency = 1).run("/f.m3u8").getOrThrow().readBytes()
        assertArrayEquals("INIT-B".toByteArray(), bytes.copyOf(6))
        assertArrayEquals("NEW0NEW1".toByteArray(), bytes.copyOfRange(6 + Mp4Sidx.size(2), bytes.size))

        val media = (HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "https://c/i.m3u8") as HlsPlaylist.Media).playlist
        val hd = HlsVariant("https://c/hd.m3u8", 4_000_000, 1080, "avc1.640028,mp4a.40.2", null)
        val h = HlsResumeState.contentHash(byteArrayOf(1, 2, 3))
        assertEquals(HlsResumeState.fingerprint(media, hd, h), HlsResumeState.fingerprint(media, hd.copy(uri = "https://other/hd.m3u8"), h))
        assertTrue(HlsResumeState.fingerprint(media, hd, h) != HlsResumeState.fingerprint(media, hd.copy(height = 720, bandwidth = 2_000_000), h))
        assertTrue(HlsResumeState.fingerprint(media, hd, h) != HlsResumeState.fingerprint(media, hd.copy(codecs = "hvc1.1.6.L120,mp4a.40.2"), h))
        assertTrue(HlsResumeState.fingerprint(media, hd, h) != HlsResumeState.fingerprint(media, hd, HlsResumeState.contentHash(byteArrayOf(9))))
    }

    // ---- bounded reads (I1) -------------------------------------------------------------------

    @Test fun `a byte-range init on a server that ignores Range reads only its slice of a huge file`() {
        val init = "INITINIT".toByteArray()
        val huge = ByteArray(4) { 'x'.code.toByte() } + init + ByteArray((HlsDownloader.MAX_INIT_BYTES + 1024).toInt())
        routes["/h.m3u8"] = { text("#EXTM3U\n#EXT-X-MAP:URI=\"big.mp4\",BYTERANGE=\"8@4\"\n#EXTINF:4,\nh0.m4s\n#EXT-X-ENDLIST\n") }
        // 200 with the whole resource, trickled: reading all of it would take minutes, the slice is instant.
        routes["/big.mp4"] = { body(huge).throttleBody(64 * 1024, 1, java.util.concurrent.TimeUnit.SECONDS) }
        routes["/h0.m4s"] = { body("FRAG".toByteArray()) }
        val bytes = downloader().run("/h.m3u8").getOrThrow().readBytes()
        assertArrayEquals(init, bytes.copyOf(8))
        assertEquals(8 + Mp4Sidx.size(1) + 4, bytes.size)
    }

    @Test fun `an init section over the cap and an init byte range over the cap are refused`() {
        routes["/i1.m3u8"] = { text("#EXTM3U\n#EXT-X-MAP:URI=\"big.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST\n") }
        routes["/big.mp4"] = { MockResponse().setChunkedBody(Buffer().write(ByteArray((HlsDownloader.MAX_INIT_BYTES + 1).toInt())), 1 shl 20) }
        assertTrue(downloader().run("/i1.m3u8").exceptionOrNull() is HlsRefusedException)
        routes["/i2.m3u8"] = { text("#EXTM3U\n#EXT-X-MAP:URI=\"big.mp4\",BYTERANGE=\"99999999@0\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST\n") }
        requests.clear()
        assertTrue(downloader().run("/i2.m3u8").exceptionOrNull() is HlsRefusedException)
        assertTrue("refused before asking for it", requests.none { it.path == "/big.mp4" })
    }

    @Test fun `a chunked playlist over 8 MB is refused though it declares no length`() {
        val big = "#EXTM3U\n" + "#EXTINF:4,\na.ts\n".repeat(600_000) + "#EXT-X-ENDLIST\n"
        routes["/big.m3u8"] = { MockResponse().setChunkedBody(big, 64 * 1024) }
        assertTrue(big.length > 8 * 1024 * 1024)
        assertTrue(downloader().run("/big.m3u8").exceptionOrNull() is HlsRefusedException)
    }

    @Test fun `a key that is not exactly 16 bytes is refused permanently`() {
        routes["/k.m3u8"] = { text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n") }
        routes["/a.ts"] = { body(ts(1)) }
        for (key in listOf("<html>error page</html>".toByteArray(), ByteArray(15), ByteArray(10_000_000))) {
            routes["/k.bin"] = { body(key) }
            val e = downloader().run("/k.m3u8").exceptionOrNull()
            assertTrue("${key.size} bytes", e is HlsRefusedException)
            assertFalse(DownloadRetryPolicy.isTransient(e!!))
        }
    }

    // ---- permanent failures found early (M2) --------------------------------------------------

    @Test fun `a key that does not decrypt and a bad IV are refusals`() {
        routes["/w.m3u8"] = { text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n") }
        routes["/k.bin"] = { body(ByteArray(16) { 7 }) }
        val right = ByteArray(16) { it.toByte() }
        routes["/a.ts"] = {
            body(Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(right, "AES"), IvParameterSpec(ByteArray(16))) }.doFinal(ts(1)))
        }
        assertTrue(downloader().run("/w.m3u8").exceptionOrNull() is HlsRefusedException)
        assertThrows(HlsRefusedException::class.java) {
            HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0xZZ\n#EXTINF:4,\na.ts\n", "https://c/i.m3u8")
        }
        assertThrows(HlsRefusedException::class.java) {
            HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x${"1".repeat(33)}\n#EXTINF:4,\na.ts\n", "https://c/i.m3u8")
        }
    }

    @Test fun `an empty segment is refused as it arrives, not after the whole download`() {
        routes["/z.m3u8"] = { text("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" + (0 until 4).joinToString("") { "#EXTINF:4,\nz$it.m4s\n" } + "#EXT-X-ENDLIST\n") }
        routes["/init.mp4"] = { body("INIT".toByteArray()) }
        (0 until 4).forEach { i -> routes["/z$i.m4s"] = { body("F$i".toByteArray()) } }
        routes["/z1.m4s"] = { MockResponse().setBody("") }
        val e = downloader(concurrency = 1).run("/z.m3u8").exceptionOrNull()
        assertTrue(e is HlsRefusedException)
        assertTrue(requests.none { it.path == "/z2.m4s" || it.path == "/z3.m4s" })
        // TS too: an empty answer is a hole in the video.
        routes["/t.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nt0.ts\n#EXTINF:4,\nt1.ts\n#EXT-X-ENDLIST\n") }
        routes["/t0.ts"] = { body(ts(0)) }
        routes["/t1.ts"] = { MockResponse().setBody("") }
        assertTrue(downloader().run("/t.m3u8").exceptionOrNull() is HlsRefusedException)
    }

    @Test fun `an empty segment is asked for again, and a CDN hiccup does not kill the download`() {
        routes["/e.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\ne0.ts\n#EXTINF:4,\ne1.ts\n#EXT-X-ENDLIST\n") }
        routes["/e0.ts"] = { body(ts(0)) }
        var asked = 0
        routes["/e1.ts"] = { if (++asked < 3) MockResponse().setBody("") else body(ts(1)) }
        val file = downloader().run("/e.m3u8").getOrThrow()
        assertArrayEquals(ts(0) + ts(1), file.readBytes())
        assertEquals(3, asked)

        // Empty every time: a hole in the video, refused after the same three asks, nothing left.
        tmp.root.listFiles()!!.forEach { it.delete() }
        routes["/e1.ts"] = { MockResponse().setBody("") }
        requests.clear()
        assertTrue(downloader().run("/e.m3u8").exceptionOrNull() is HlsRefusedException)
        assertEquals(3, requests.count { it.path == "/e1.ts" })
        assertEquals(emptyList<String>(), leftovers())
    }

    // ---- a refusal leaves nothing behind (M1) --------------------------------------------------

    @Test fun `a refusal after some segments deletes the part, the state and the raw segments`() {
        routes["/m.m3u8"] = { text("#EXTM3U\n" + (0 until 3).joinToString("") { "#EXTINF:4,\nm$it.ts\n" } + "#EXT-X-DISCONTINUITY\n#EXTINF:4,\nm3.ts\n#EXT-X-ENDLIST\n") }
        (0 until 3).forEach { i -> routes["/m$i.ts"] = { body(tsProgram(listOf(0x1B, 0x0F), i)) } }
        routes["/m3.ts"] = { body(tsProgram(listOf(0x24, 0x0F), 3)) } // HEVC after the splice
        val e = downloader(concurrency = 1).run("/m.m3u8").exceptionOrNull()
        assertTrue(e is HlsRefusedException)
        assertEquals("nothing will ever resume it", emptyList<String>(), leftovers())
    }

    @Test fun `a network failure still keeps the part for the retry`() {
        routes["/n.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nn0.ts\n#EXTINF:4,\nn1.ts\n#EXT-X-ENDLIST\n") }
        routes["/n0.ts"] = { body(ts(0)) }
        routes["/n1.ts"] = { MockResponse().setResponseCode(503) }
        assertTrue(downloader(concurrency = 1).run("/n.m3u8").isFailure)
        assertEquals(listOf("ep.hls.part", "ep.hls.state"), leftovers())
    }

    // ---- a segment is bounded while it is written (M2) ----------------------------------------

    @Test fun `a segment over the cap is refused while it streams, and one that declares too much before`() {
        routes["/b.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nb0.ts\n#EXTINF:4,\nb1.ts\n#EXT-X-ENDLIST\n") }
        routes["/b0.ts"] = { body(ts(0)) }
        // Chunked: no length to check up front, 64 KB of "segment" against a 16 KB cap.
        routes["/b1.ts"] = { MockResponse().setChunkedBody(Buffer().write(ts(1, packets = 350)), 4096) }
        val small = HlsDownloader(OkHttpClient(), freeSpace = { Long.MAX_VALUE }, concurrency = 1, attempts = 3, retryDelayMs = 0, minSegmentCapBytes = 16 * 1024)
        val e = small.run("/b.m3u8").exceptionOrNull()
        assertTrue(e is HlsRefusedException)
        assertFalse(DownloadRetryPolicy.isTransient(e!!))
        assertEquals("refused once, not retried", 1, requests.count { it.path == "/b1.ts" })
        assertEquals(emptyList<String>(), leftovers())

        routes["/b1.ts"] = { body(ts(1, packets = 350)) } // Content-Length says 65800
        requests.clear()
        assertTrue(small.run("/b.m3u8").exceptionOrNull() is HlsRefusedException)
    }

    @Test fun `the cap follows the declared bandwidth, so a big but plausible segment is kept`() {
        // 4 Mbit/s × 4 s = 2 MB expected: a 100 KB segment is far below 8 times that.
        routes["/master.m3u8"] = { text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720\nv.m3u8\n") }
        routes["/v.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nv0.ts\n#EXT-X-ENDLIST\n") }
        routes["/v0.ts"] = { body(ts(0, packets = 550)) }
        val small = HlsDownloader(OkHttpClient(), freeSpace = { Long.MAX_VALUE }, attempts = 1, retryDelayMs = 0, minSegmentCapBytes = 16 * 1024)
        assertTrue(small.run("/master.m3u8").isSuccess)
    }

    @Test fun `the disk is measured while a segment is written, and a full disk keeps the part`() {
        routes["/s.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\ns0.ts\n#EXTINF:4,\ns1.ts\n#EXT-X-ENDLIST\n") }
        routes["/s0.ts"] = { body(ts(0)) }
        routes["/s1.ts"] = { body(ts(1, packets = 700)) } // ~130 KB
        val free = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val d = HlsDownloader(
            OkHttpClient(), freeSpace = { checks.incrementAndGet(); free.get() }, checkEveryBytes = 16 * 1024,
            concurrency = 1, attempts = 3, retryDelayMs = 0,
        )
        // Segment 0 passes; the disk fills up during segment 1.
        routes["/s1.ts"] = { free.set(0); body(ts(1, packets = 700)) }
        val e = d.run("/s.m3u8").exceptionOrNull()
        assertTrue(e is InsufficientSpaceException)
        assertEquals("not retried onto a full disk", 1, requests.count { it.path == "/s1.ts" })
        assertTrue(checks.get() > 2)
        assertEquals("the part stays: freeing space and retrying resumes it", listOf("ep.hls.part", "ep.hls.state"), leftovers())
    }

    // ---- the plugin's host gate (I1) ----------------------------------------------------------

    /** The plugin's download client: `localhost` declared (over http, test-only), [dns] for every other name. */
    private fun gated(counted: MutableList<String>, dns: (String) -> List<java.net.InetAddress> = { listOf(java.net.InetAddress.getLoopbackAddress()) }): OkHttpClient {
        val base = OkHttpClient.Builder().addInterceptor { chain -> counted += chain.request().url.host; chain.proceed(chain.request()) }.build()
        val lookup = object : okhttp3.Dns { override fun lookup(hostname: String) = dns(hostname) }
        return com.arkiv.player.data.plugin.PluginStreamHttp.client(
            base, com.arkiv.player.data.plugin.EffectiveHosts(listOf("localhost", "cdn.declared.example")),
            allowInsecureLocalhost = true, delegateDns = lookup, askAboutFor = "demo",
        )
    }

    /** Through [gated]: the playlist on `localhost` by that name (the declared host), not the server's own. */
    private fun HlsDownloader.runLocal(path: String) =
        runBlocking { download("http://localhost:${server.port}$path", emptyMap(), tmp.root, "ep", resumeKey = "ep-1") { _, _ -> } }

    @Test fun `segments on a CDN the plugin never declared are refused once, for good, with nothing left`() {
        routes["/u.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nhttps://cdn.other.example/u0.ts\n#EXT-X-ENDLIST\n") }
        val counted = mutableListOf<String>()
        val d = HlsDownloader(gated(counted), freeSpace = { Long.MAX_VALUE }, attempts = 3, retryDelayMs = 0)
        val e = d.runLocal("/u.m3u8").exceptionOrNull()!!
        assertTrue(e is com.arkiv.player.data.plugin.UndeclaredPlaybackHostException)
        assertFalse(DownloadRetryPolicy.isTransient(e))
        assertEquals("no quick retries either", 1, counted.count { it == "cdn.other.example" })
        assertTrue(PluginHostRefusal.message(e).contains("Reprodúcelo una vez"))
        assertEquals(PluginHostRefusal.message(e), HlsFailureText.of(e))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `a declared CDN that resolves into the home network, and a redirect off the declared hosts, are refusals`() {
        routes["/p.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nhttps://cdn.declared.example/p0.ts\n#EXT-X-ENDLIST\n") }
        val lan = { host: String ->
            if (host == "localhost") listOf(java.net.InetAddress.getLoopbackAddress()) else listOf(java.net.InetAddress.getByName("192.168.1.5"))
        }
        val e = HlsDownloader(gated(mutableListOf(), lan), freeSpace = { Long.MAX_VALUE }, attempts = 3, retryDelayMs = 0).runLocal("/p.m3u8").exceptionOrNull()!!
        assertTrue(e.toString(), PluginHostRefusal.of(e) is com.arkiv.player.data.plugin.PrivateAddressException)
        assertFalse(DownloadRetryPolicy.isTransient(e))

        routes["/r.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nr0.ts\n#EXT-X-ENDLIST\n") }
        routes["/r0.ts"] = { MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/r0.ts") }
        val counted = mutableListOf<String>()
        val redirected = HlsDownloader(gated(counted), freeSpace = { Long.MAX_VALUE }, attempts = 3, retryDelayMs = 0).runLocal("/r.m3u8").exceptionOrNull()!!
        assertTrue(PluginHostRefusal.of(redirected) is com.arkiv.player.data.plugin.HostNotAllowedException)
        assertFalse(DownloadRetryPolicy.isTransient(redirected))
        assertEquals(1, requests.count { it.path == "/r0.ts" })
    }

    // ---- AES-128 resume (fingerprint on the plaintext) ----------------------------------------

    @Test fun `an AES stream whose key changes on every resolve still resumes`() {
        fun enc(key: ByteArray, plain: ByteArray, seq: Long) = Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteBuffer.allocate(16).putLong(8, seq).array())) }
            .doFinal(plain)
        fun serve(dir: String, key: ByteArray) {
            routes["/$dir/i.m3u8"] = { text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n" + (0 until 4).joinToString("") { "#EXTINF:4,\ns$it.ts\n" } + "#EXT-X-ENDLIST\n") }
            routes["/$dir/k.bin"] = { body(key) }
            (0 until 4).forEach { i -> routes["/$dir/s$i.ts"] = { body(enc(key, ts(i, packets = 100), i.toLong())) } }
        }
        serve("one", ByteArray(16) { 1 })
        routes["/one/s2.ts"] = { MockResponse().setResponseCode(503) }
        assertTrue(downloader(concurrency = 2).run("/one/i.m3u8").isFailure)

        // The next resolve: another key URL and other key bytes, the same video.
        serve("two", ByteArray(16) { 2 })
        requests.clear()
        val file = downloader(concurrency = 2).run("/two/i.m3u8").getOrThrow()
        assertArrayEquals((0 until 4).map { ts(it, packets = 100) }.reduce { a, b -> a + b }, file.readBytes())
        assertTrue("segment 1 was not fetched again", requests.none { it.path == "/two/s1.ts" })
        assertEquals("segment 0 is only probed", "bytes=0-16383", requests.single { it.path == "/two/s0.ts" }.getHeader("Range"))
    }

    @Test fun `a prefix of whole AES blocks decrypts without the padding`() {
        val key = ByteArray(16) { 3 }
        val iv = ByteArray(16) { 4 }
        val plain = ByteArray(100) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(plain)
        assertArrayEquals(plain.copyOf(48), HlsCrypto.decryptPrefix(cipher.copyOf(50), key, iv))
        assertEquals(0, HlsCrypto.decryptPrefix(ByteArray(15), key, iv).size)
    }

    @Test fun `every non-refusal failure reads as a Spanish sentence`() {
        assertEquals("El servidor ya no tiene este video (HTTP 404).", HlsFailureText.of(HttpStatusException(404)))
        assertEquals("El servidor del video no dio acceso (HTTP 403).", HlsFailureText.of(HttpStatusException(403)))
        assertEquals("El servidor del video respondió con un error (HTTP 503).", HlsFailureText.of(HttpStatusException(503)))
        assertEquals("No se pudo conectar con el servidor del video. Revisa tu conexión.", HlsFailureText.of(java.net.UnknownHostException("cdn.example")))
        assertEquals("No se pudo conectar con el servidor del video. Revisa tu conexión.", HlsFailureText.of(java.net.SocketTimeoutException("timeout")))
        assertEquals("La conexión se cortó a mitad de la descarga.", HlsFailureText.of(IncompleteDownloadException(1, 2)))
        assertEquals(InsufficientSpaceException(0).message, HlsFailureText.of(InsufficientSpaceException(0)))
        assertEquals("Falló la conexión con el servidor del video.", HlsFailureText.of(java.io.IOException("unexpected end of stream")))
        assertEquals("Falló la descarga del video.", HlsFailureText.of(IllegalStateException("boom")))
    }

    // ---- discontinuities (M1) -----------------------------------------------------------------

    /** A TS packet on [pid] starting a PSI section: pointer 0, [section], 0xFF stuffing. */
    private fun psi(pid: Int, section: ByteArray): ByteArray =
        (byteArrayOf(0x47, (0x40 or (pid shr 8)).toByte(), pid.toByte(), 0x10, 0) + section).copyOf(188).also { it.fill(0xFF.toByte(), 5 + section.size, 188) }

    /** PAT (program 1 on PID 0x100) + PMT with [types], then [packets] payload packets. */
    private fun tsProgram(types: List<Int>, n: Int, packets: Int = 3): ByteArray {
        val pat = byteArrayOf(0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xE1.toByte(), 0x00, 0, 0, 0, 0)
        val streams = types.flatMapIndexed { i, t -> listOf(t.toByte(), 0xE1.toByte(), (0x01 + i).toByte(), 0xF0.toByte(), 0) }.toByteArray()
        val pmt = byteArrayOf(0x02, 0xB0.toByte(), (13 + streams.size).toByte(), 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0) + streams + ByteArray(4)
        return psi(0, pat) + psi(0x100, pmt) + ts(n, packets)
    }

    /** One TS packet on [pid] whose payload bytes are all [n]. */
    private fun pkt(pid: Int, n: Int): ByteArray =
        ByteArray(188) { n.toByte() }.also { it[0] = 0x47; it[1] = (pid shr 8).toByte(); it[2] = pid.toByte(); it[3] = 0x10 }

    /** PAT (program 1 on PID 0x100) + a PMT declaring [streams] (pid to type). */
    private fun programAt(streams: List<Pair<Int, Int>>): ByteArray {
        val pat = byteArrayOf(0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xE1.toByte(), 0x00, 0, 0, 0, 0)
        val entries = streams.flatMap { (pid, t) -> listOf(t.toByte(), (0xE0 or (pid shr 8)).toByte(), pid.toByte(), 0xF0.toByte(), 0) }.toByteArray()
        val pmt = byteArrayOf(0x02, 0xB0.toByte(), (13 + entries.size).toByte(), 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0) + entries + ByteArray(4)
        return psi(0, pat) + psi(0x100, pmt)
    }

    @Test fun `the PMT's streams are read with their PIDs`() {
        assertEquals(listOf(TsStream(0x101, 0x1B), TsStream(0x102, 0x0F), TsStream(0x1F0, 0x15)), TsProgram.streams(programAt(listOf(0x101 to 0x1B, 0x102 to 0x0F, 0x1F0 to 0x15))))
        assertTrue(TsProgram.isAudioVideo(0x1B) && TsProgram.isAudioVideo(0x0F) && TsProgram.isAudioVideo(0x81))
        assertFalse(TsProgram.isAudioVideo(0x15) || TsProgram.isAudioVideo(0x86) || TsProgram.isAudioVideo(0x06))
    }

    @Test fun `metadata streams coming and going at a discontinuity are not a change`() {
        routes["/id3.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n") }
        val first = programAt(listOf(0x101 to 0x1B, 0x102 to 0x0F, 0x103 to 0x15)) + pkt(0x101, 1) + pkt(0x102, 2)
        val second = programAt(listOf(0x101 to 0x1B, 0x102 to 0x0F, 0x1F0 to 0x86)) + pkt(0x101, 3) + pkt(0x1F0, 4)
        routes["/a.ts"] = { body(first) }
        routes["/b.ts"] = { body(second) }
        val file = downloader().run("/id3.m3u8").getOrThrow()
        assertArrayEquals("same PIDs: written as they came", first + second, file.readBytes())
    }

    @Test fun `the same codecs on other PIDs after a splice are written back under the first PIDs`() {
        routes["/pid.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts\n#EXTINF:4,\nc.ts\n#EXT-X-ENDLIST\n") }
        val first = programAt(listOf(0x101 to 0x1B, 0x102 to 0x0F)) + pkt(0x101, 1) + pkt(0x102, 2)
        // The splice's muxer: video on 0x102 (the first segment's audio PID), audio on 0x201, ID3 on 0x101.
        val secondPmt = programAt(listOf(0x102 to 0x1B, 0x201 to 0x0F, 0x101 to 0x15))
        routes["/a.ts"] = { body(first) }
        routes["/b.ts"] = { body(secondPmt + pkt(0x102, 3) + pkt(0x201, 4) + pkt(0x101, 5)) }
        routes["/c.ts"] = { body(pkt(0x102, 6) + pkt(0x201, 7)) } // no PMT of its own: the renumbering goes on
        val file = downloader(concurrency = 1).run("/pid.m3u8").getOrThrow()
        val expected = first + secondPmt + pkt(0x101, 3) + pkt(0x102, 4) + pkt(TsPidRewriter.NULL_PID, 5) + pkt(0x101, 6) + pkt(0x102, 7)
        assertArrayEquals(expected, file.readBytes())
    }

    @Test fun `the PMT's stream types are read from a segment head`() {
        assertEquals(listOf(0x0F, 0x1B), TsProgram.streamTypes(tsProgram(listOf(0x1B, 0x0F), 1)))
        assertEquals(listOf(0x0F, 0x24), TsProgram.streamTypes(byteArrayOf(1, 2, 3) + tsProgram(listOf(0x24, 0x0F), 1)))
        assertNull("no PAT/PMT: nothing concluded", TsProgram.streamTypes(ts(1)))
    }

    @Test fun `a TS discontinuity with the same streams is kept, one that changes them is refused`() {
        val h264 = listOf(0x1B, 0x0F)
        routes["/d.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nd0.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nd1.ts\n#EXTINF:4,\nd2.ts\n#EXT-X-ENDLIST\n") }
        routes["/d0.ts"] = { body(tsProgram(h264, 0)) }
        routes["/d1.ts"] = { body(tsProgram(h264, 1)) }
        routes["/d2.ts"] = { body(tsProgram(h264, 2)) }
        val file = downloader().run("/d.m3u8").getOrThrow()
        assertArrayEquals(tsProgram(h264, 0) + tsProgram(h264, 1) + tsProgram(h264, 2), file.readBytes())

        file.delete()
        routes["/d1.ts"] = { body(tsProgram(listOf(0x24, 0x0F), 1)) } // HEVC after the splice
        val e = downloader().run("/d.m3u8").exceptionOrNull()
        assertTrue(e is HlsRefusedException)
        // A change WITHOUT a discontinuity tag is not looked for (the player would not reset either).
        routes["/d.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nd0.ts\n#EXTINF:4,\nd1.ts\n#EXT-X-ENDLIST\n") }
        tmp.root.listFiles()!!.forEach { it.delete() }
        assertTrue(downloader().run("/d.m3u8").isSuccess)
    }

    @Test fun `a resumed TS download still compares a discontinuity with the first segment's streams`() {
        routes["/q.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nq0.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nq1.ts\n#EXT-X-ENDLIST\n") }
        routes["/q0.ts"] = { body(tsProgram(listOf(0x1B, 0x0F), 0)) }
        assertTrue(downloader(concurrency = 1).run("/q.m3u8").isFailure) // q1 missing
        routes["/q1.ts"] = { body(tsProgram(listOf(0x24, 0x0F), 1)) }
        assertTrue(downloader(concurrency = 1).run("/q.m3u8").exceptionOrNull() is HlsRefusedException)
    }

    @Test fun `a changed playlist starts over instead of resuming`() {
        routes["/c.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nc0.ts\n#EXTINF:4,\nc1.ts\n#EXT-X-ENDLIST\n") }
        routes["/c0.ts"] = { body(ts(0)) }
        assertTrue(downloader(concurrency = 1).run("/c.m3u8").isFailure) // c1 missing
        routes["/c.m3u8"] = { text("#EXTM3U\n#EXTINF:6,\nc0.ts\n#EXTINF:6,\nc1.ts\n#EXTINF:6,\nc2.ts\n#EXT-X-ENDLIST\n") }
        routes["/c1.ts"] = { body(ts(1)) }
        routes["/c2.ts"] = { body(ts(2)) }
        requests.clear()
        val file = downloader(concurrency = 1).run("/c.m3u8").getOrThrow()
        assertArrayEquals(ts(0) + ts(1) + ts(2), file.readBytes())
        assertTrue(requests.any { it.path == "/c0.ts" })
    }

    @Test fun `a first segment that is not MPEG-TS is refused`() {
        routes["/x.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nx.ts\n#EXT-X-ENDLIST\n") }
        routes["/x.ts"] = { text("<html>blocked</html>") }
        assertTrue(downloader().run("/x.m3u8").exceptionOrNull() is HlsRefusedException)
    }

    @Test fun `a master whose only video needs a separate audio stream is refused before any segment`() {
        routes["/m.m3u8"] = { text("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nv.m3u8\n") }
        assertTrue(downloader().run("/m.m3u8").exceptionOrNull() is HlsRefusedException)
        assertEquals(listOf("/m.m3u8"), requests.map { it.path })
    }

    @Test fun `no room for the declared size fails before any segment, as a definitive failure`() {
        routes["/master.m3u8"] = { text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080\nv.m3u8\n") }
        routes["/v.m3u8"] = { text("#EXTM3U\n" + (0 until 900).joinToString("") { "#EXTINF:10,\ns$it.ts\n" } + "#EXT-X-ENDLIST\n") }
        // 8 Mbit/s × 9000 s ≈ 9 GB, on 2 GB free.
        val e = downloader(freeSpace = 2L * 1024 * 1024 * 1024).run("/master.m3u8").exceptionOrNull()
        assertTrue(e is InsufficientSpaceException)
        assertFalse(DownloadRetryPolicy.isTransient(e!!))
        assertTrue(requests.none { it.path!!.endsWith(".ts") })
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
