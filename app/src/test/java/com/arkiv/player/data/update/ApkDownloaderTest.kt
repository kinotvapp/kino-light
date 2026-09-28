package com.arkiv.player.data.update

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.UnknownHostException
import java.security.MessageDigest

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val net = FakeTransport()
    private val reports = ArrayList<Map<String, String>>()
    private val downloader by lazy { ApkDownloader(tmp.root, net.client(), report = { reports += it }) }

    private val apk = ByteArray(50_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private val archiveApk = "https://archive.org/download/kino-app/kino.apk"
    private val jsdelivrApk = "https://cdn.jsdelivr.net/npm/static-asset-pack@1.0.4/ota/kino.apk"
    private val unpkgApk = "https://unpkg.com/static-asset-pack@1.0.4/ota/kino.apk"

    private fun info(url: String = archiveApk, sha256: String = sha) = UpdateInfo(74, "0.74.0", url, "", sha256)

    private suspend fun run(info: UpdateInfo, mirrors: List<String> = listOf(jsdelivrApk, unpkgApk)): DownloadState {
        var asked = 0
        val states = downloader.download(info) { asked++; mirrors }.toList()
        assertTrue("mirrors looked up at most once", asked <= 1)
        return states.last()
    }

    @Test
    fun `archive serving the right bytes is Ready without asking the mirrors`() = runBlocking {
        net.bytes(archiveApk, apk)
        var asked = false
        val last = downloader.download(info()) { asked = true; emptyList() }.toList().last()
        assertArrayEquals(apk, (last as DownloadState.Ready).file.readBytes())
        assertFalse(asked)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `archive down, the jsDelivr copy of the same version is used`() = runBlocking {
        net.fail(archiveApk, UnknownHostException("archive.org"))
        net.bytes(jsdelivrApk, apk)
        val last = run(info())
        assertArrayEquals(apk, (last as DownloadState.Ready).file.readBytes())
        assertEquals(listOf(archiveApk, jsdelivrApk), net.requested)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `archive non-2xx moves on to the mirrors`() = runBlocking {
        net.bytes(archiveApk, ByteArray(0), code = 503)
        net.fail(jsdelivrApk, UnknownHostException("cdn.jsdelivr.net"))
        net.bytes(unpkgApk, apk)
        assertTrue(run(info()) is DownloadState.Ready)
    }

    @Test
    fun `a sha256 mismatch on one source moves on to the next, and the bad bytes are never Ready`() = runBlocking {
        net.bytes(archiveApk, apk.copyOf().also { it[0] = 9 })
        net.bytes(jsdelivrApk, apk)
        val last = run(info())
        assertArrayEquals(apk, (last as DownloadState.Ready).file.readBytes())
    }

    @Test
    fun `every source failing is Failed, reported once without urls`() = runBlocking {
        net.bytes(archiveApk, apk.copyOf().also { it[0] = 9 })
        net.bytes(jsdelivrApk, ByteArray(0), code = 404)
        net.fail(unpkgApk, UnknownHostException("unpkg.com"))
        val last = run(info())
        assertTrue(last is DownloadState.Failed)
        assertEquals(
            listOf(mapOf("versionCode" to "74", "archive" to "sha_mismatch", "jsdelivr" to "http_404", "unpkg" to "dns")),
            reports,
        )
        assertFalse("no partial or bad file left behind", tmp.root.walk().any { it.isFile })
    }

    @Test
    fun `an APK url outside the three known hosts is never requested`() = runBlocking {
        net.bytes("https://evil.example.com/kino.apk", apk)
        net.bytes(jsdelivrApk, apk)
        val last = run(info(url = "https://evil.example.com/kino.apk"), mirrors = listOf("https://evil.example.com/other.apk", jsdelivrApk))
        assertTrue(last is DownloadState.Ready)
        assertEquals(listOf(jsdelivrApk), net.requested)
    }

    @Test
    fun `a legacy manifest without sha256 downloads without the gate`() = runBlocking {
        net.bytes(archiveApk, apk)
        assertTrue(run(info(sha256 = "")) is DownloadState.Ready)
    }
}
