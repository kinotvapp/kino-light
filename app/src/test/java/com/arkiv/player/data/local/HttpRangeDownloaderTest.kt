package com.arkiv.player.data.local

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpRangeDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpRangeDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        // Hermetic: the real free space of whatever machine runs the tests must not decide the result.
        downloader = HttpRangeDownloader(OkHttpClient(), freeSpace = { Long.MAX_VALUE })
    }

    @After fun tearDown() { server.shutdown() }

    /**
     * Leaves a "legitimate" partial: the bytes + the origin mark the downloader itself writes.
     * Without the mark the partial gets discarded (see `a partial with no origin mark doesn't get
     * resumed`), which is exactly what protects against resuming one file against another.
     */
    private fun writePart(target: File, bytes: String, origin: String) {
        LocalFilePaths.partOf(target).writeText(bytes)
        LocalFilePaths.originOf(target).writeText(origin)
    }

    @Test
    fun `with no prior partial, no Range is sent`() {
        assertNull(RangeMath.rangeHeaderFor(0))
    }

    @Test
    fun `with a prior partial, it asks from where it left off`() {
        assertEquals("bytes=1024-", RangeMath.rangeHeaderFor(1024))
    }

    @Test
    fun `the total is what's left plus what's already written`() {
        assertEquals(5000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 1000))
        assertEquals(4000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 0))
    }

    @Test
    fun `downloads completely and renames the partial`() = runBlocking {
        val body = "0123456789".repeat(100)   // 1000 bytes
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8(body)))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals(1000, target.length())
        assertEquals(body, target.readText())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    // --- disk guard (a full disk must stop the download, not the whole device) ---------------------

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun `a disk already at the reserve is not even asked`() = runBlocking {
        val guarded = HttpRangeDownloader(OkHttpClient(), freeSpace = { 100 * mb }) // < 500 MB margin
        server.enqueue(MockResponse().setBody("would-be-bytes"))
        val target = File(tmp.root, "peli.mp4")

        val result = guarded.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertEquals("no request should have been made", 0, server.requestCount)
        assertFalse(target.exists())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `a declared size that does not fit is refused before writing anything`() = runBlocking {
        val guarded = HttpRangeDownloader(OkHttpClient(), freeSpace = { 1 * gb })
        // The server declares a 2 GB file; only a byte of body is ever needed because the guard
        // throws right after reading the headers.
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", (2 * gb).toString()))
        val target = File(tmp.root, "peli.mp4")

        val result = guarded.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        val error = result.exceptionOrNull()
        assertTrue("expected InsufficientSpaceException, got $error", error is InsufficientSpaceException)
        assertEquals(1 * gb, (error as InsufficientSpaceException).availableBytes)
        assertFalse("nothing may be written", LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `a disk that fills up mid-download stops it and keeps the partial to resume`() = runBlocking {
        var measurements = 0
        // Plenty of room for the two checks before writing, then the disk drops under the reserve.
        val guarded = HttpRangeDownloader(
            OkHttpClient(),
            freeSpace = { if (++measurements <= 2) 10 * gb else 100 * mb },
            checkEveryBytes = 1024,
        )
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8("0123456789".repeat(1000)))) // 10 KB
        val target = File(tmp.root, "peli.mp4")

        val result = guarded.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertFalse("the incomplete file must never be presented as done", target.exists())
        assertTrue("the partial is kept so Reintentar resumes it", LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `resumes from the existing partial`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val url = server.url("/f").toString()
        writePart(target, "AAAA", origin = url)                   // 4 bytes already downloaded, same origin
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(url, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        // No more partial left to identify: the mark gets cleaned up on rename.
        assertFalse(LocalFilePaths.originOf(target).exists())
    }

    /**
     * The bug's scenario: an archive episode's `derivative` variant downloads and fails at 40%; the
     * user switches quality to ORIGINAL and re-queues. `variantFor()` picks another URL and another
     * size, `Range: bytes=<40% of the derivative>-` was requested against the original, a 206 came
     * back, and one file's tail got appended to the other's prefix. The `written < total` check
     * didn't catch it (the numbers added up), it got renamed and ended up marked "Listo" while being
     * garbage.
     */
    @Test
    fun `doesn't resume a partial from another origin and downloads the whole file`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val derivative = server.url("/peli_derivative.mp4").toString()
        val original = server.url("/peli_original.mp4").toString()
        writePart(target, "DERIVATIVE-40%", origin = derivative)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ORIGINAL-ENTERO"))

        val result = downloader.download(original, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        // Not a trace of the other variant's prefix.
        assertEquals("ORIGINAL-ENTERO", target.readText())
        // And a resume wasn't even requested: the partial was discarded BEFORE building the request.
        assertNull(server.takeRequest().getHeader("Range"))
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `a partial with no origin mark doesn't get resumed`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        // Partial left by an earlier version of the app: which URL it came from can't be asserted.
        LocalFilePaths.partOf(target).writeText("VIEJO")
        server.enqueue(MockResponse().setResponseCode(200).setBody("NUEVO-COMPLETO"))

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("NUEVO-COMPLETO", target.readText())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `the same resumeKey resumes even if the URL changes`() = runBlocking {
        // The NUC gets served over LAN or through a tunnel depending on where the phone is: the URL
        // changes but the content is the same, so with the URL as the key a valid partial would get
        // thrown away.
        val target = File(tmp.root, "peli.mp4")
        writePart(target, "AAAA", origin = "nuc:item:42")
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(
            server.url("/otra-base/stream/42").toString(), target, emptyMap(), resumeKey = "nuc:item:42",
        ) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `if the server doesn't support Range and answers 200 it discards the partial and doesn't duplicate`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val url = server.url("/f").toString()
        writePart(target, "AAAA", origin = url)                    // 4 bytes already downloaded, same origin
        server.enqueue(MockResponse().setResponseCode(200).setBody("XXXXYYYY"))   // whole file, ignores the Range

        val result = downloader.download(url, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("XXXXYYYY", target.readText())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `sends the headers it's passed`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        val target = File(tmp.root, "peli.mp4")

        downloader.download(
            server.url("/f").toString(), target,
            mapOf("Referer" to "https://origen.example/"),
        ) { _, _ -> }

        assertEquals("https://origen.example/", server.takeRequest().getHeader("Referer"))
    }

    @Test
    fun `a 403 fails and leaves no final file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(target.exists())
        // Typed, so the retry policy can tell a 403 (definitive) apart from a 503.
        val error = result.exceptionOrNull()
        assertTrue(error is HttpStatusException)
        assertEquals(403, (error as HttpStatusException).code)
    }

    @Test
    fun `reports increasing progress`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8("x".repeat(200_000))))
        val target = File(tmp.root, "peli.mp4")
        val seen = mutableListOf<Long>()

        downloader.download(server.url("/f").toString(), target, emptyMap()) { done, _ -> seen.add(done) }

        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen)
        assertEquals(200_000L, seen.last())
    }

    // ---- manifests in disguise (refuseManifests, set only by the plugin strategy) ----

    private fun assertNothingLeft(target: File) {
        assertFalse("no final file", target.exists())
        assertFalse("no partial", LocalFilePaths.partOf(target).exists())
        assertFalse("no origin mark", LocalFilePaths.originOf(target).exists())
    }

    @Test
    fun `a manifest content type is refused before writing anything`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n"))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/hls/index").toString(), target, emptyMap(), refuseManifests = true) { _, _ -> }

        assertTrue(result.exceptionOrNull() is ManifestResponseException)
        assertNothingLeft(target)
    }

    @Test
    fun `a body that starts as an HLS playlist is refused and the partial deleted`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg0.ts\n"))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap(), refuseManifests = true) { _, _ -> }

        assertTrue(result.exceptionOrNull() is ManifestResponseException)
        assertNothingLeft(target)
    }

    @Test
    fun `a body that starts as a DASH manifest is refused, with or without the xml prolog`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        server.enqueue(MockResponse().setBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>"))
        assertTrue(downloader.download(server.url("/a").toString(), target, emptyMap(), refuseManifests = true) { _, _ -> }.exceptionOrNull() is ManifestResponseException)
        assertNothingLeft(target)

        server.enqueue(MockResponse().setBody("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>"))
        assertTrue(downloader.download(server.url("/b").toString(), target, emptyMap(), refuseManifests = true) { _, _ -> }.exceptionOrNull() is ManifestResponseException)
        assertNothingLeft(target)
    }

    @Test
    fun `without refuseManifests the same body downloads as before`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n"))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("#EXTM3U\n", target.readText())
    }

    @Test
    fun `a real progressive file passes the sniff and downloads whole`() = runBlocking {
        // An MP4 head (ftyp box) followed by more than the sniff window of payload.
        val head = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val body = head + ByteArray(5000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(body)))
        val target = File(tmp.root, "peli.mp4")
        val seen = mutableListOf<Long>()

        val result = downloader.download(server.url("/f.mp4").toString(), target, emptyMap(), refuseManifests = true) { done, _ -> seen.add(done) }

        assertTrue(result.isSuccess)
        assertTrue(body.contentEquals(target.readBytes()))
        assertEquals(body.size.toLong(), seen.last())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `a file shorter than the sniff window still downloads whole`() = runBlocking {
        server.enqueue(MockResponse().setBody("tiny"))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap(), refuseManifests = true) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("tiny", target.readText())
    }

    @Test
    fun `a resumed partial is not sniffed, its head is already on disk`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val url = server.url("/f").toString()
        writePart(target, "AAAA", origin = url)
        // The continuation happens to start with what would look like a playlist header: it is
        // the middle of a file, not its start.
        server.enqueue(MockResponse().setResponseCode(206).setBody("#EXTM3U-tail"))

        val result = downloader.download(url, target, emptyMap(), refuseManifests = true) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAA#EXTM3U-tail", target.readText())
    }
}
