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
        assertEquals("segments 0-1 were kept", listOf("/r.m3u8", "/r2.ts", "/r3.ts", "/r4.ts"), requests.mapNotNull { it.path }.sorted())
        assertEquals(listOf("ep.ts"), tmp.root.list()!!.toList())
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
