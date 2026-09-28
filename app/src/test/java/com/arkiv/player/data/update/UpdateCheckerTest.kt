package com.arkiv.player.data.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UpdateCheckerTest {
    private val net = FakeTransport()
    private val reports = ArrayList<Map<String, String>>()
    private val checker = UpdateChecker(net.client(), report = { reports += it })

    private val archive = OtaSources.ARCHIVE.manifestUrl
    private val jsdelivr = OtaSources.JSDELIVR.manifestUrl
    private val unpkg = OtaSources.UNPKG.manifestUrl

    private val sha = "a".repeat(64)

    private fun manifest(vc: Int, url: String, sha256: String? = sha, notes: String = "fix") =
        """{"versionCode":$vc,"versionName":"0.$vc.0","url":"$url","notes":"$notes"""" +
            (if (sha256 != null) ""","sha256":"$sha256"}""" else "}")

    private val archiveApk = "https://archive.org/download/kino-app/kino.apk"
    private fun jsdelivrApk(v: String) = "https://cdn.jsdelivr.net/npm/static-asset-pack@$v/ota/kino.apk"
    private fun unpkgApk(v: String) = "https://unpkg.com/static-asset-pack@$v/ota/kino.apk"

    @Test
    fun `sources are archive, then jsDelivr, then unpkg, all on the static-asset-pack major 1`() {
        assertEquals(
            listOf(
                "https://archive.org/download/kino-app/latest.json",
                "https://cdn.jsdelivr.net/npm/static-asset-pack@1/ota/latest.json",
                "https://unpkg.com/static-asset-pack@1/ota/latest.json",
            ),
            OtaSources.DEFAULT.map { it.manifestUrl },
        )
    }

    @Test
    fun `a newer version on archive is Available, with its sha256, and the mirrors are never asked`() = runBlocking {
        net.body(archive, manifest(74, archiveApk))
        val result = checker.check(currentVersionCode = 73)
        val info = (result as UpdateCheckResult.Available).info
        assertEquals(74, info.versionCode)
        assertEquals("0.74.0", info.versionName)
        assertEquals(archiveApk, info.url)
        assertEquals("fix", info.notes)
        assertEquals(sha, info.sha256)
        assertEquals(listOf(archive), net.requested)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `the same version on archive is UpToDate`() = runBlocking {
        net.body(archive, manifest(73, archiveApk))
        assertEquals(UpdateCheckResult.UpToDate, checker.check(currentVersionCode = 73))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `archive unreachable, jsDelivr's manifest is used`() = runBlocking {
        net.fail(archive, UnknownHostException("archive.org"))
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        val info = (checker.check(73) as UpdateCheckResult.Available).info
        assertEquals(jsdelivrApk("1.0.4"), info.url)
        assertEquals(listOf(archive, jsdelivr), net.requested)
        assertTrue("a rescued check is not a failure", reports.isEmpty())
    }

    @Test
    fun `archive answering non-2xx or garbage moves on to the next source`() = runBlocking {
        net.body(archive, "oops", code = 503)
        net.body(jsdelivr, "<html>not json</html>")
        net.body(unpkg, manifest(74, unpkgApk("1.0.4")))
        val info = (checker.check(73) as UpdateCheckResult.Available).info
        assertEquals(unpkgApk("1.0.4"), info.url)
    }

    @Test
    fun `every source down is Failed, never UpToDate, with the first source's reason`() = runBlocking {
        net.fail(archive, UnknownHostException("archive.org"))
        net.fail(jsdelivr, SocketTimeoutException("timeout"))
        net.body(unpkg, "nope", code = 404)
        val result = checker.check(73)
        assertEquals(UpdateCheckResult.Failed("dns"), result)
        assertEquals(
            listOf(mapOf("reason" to "dns", "archive" to "dns", "jsdelivr" to "timeout", "unpkg" to "http_404")),
            reports,
        )
    }

    @Test
    fun `failure reasons are one stable word each`() = runBlocking {
        net.fail(archive, SSLHandshakeException("bad cert"))
        net.fail(jsdelivr, ConnectException("refused"))
        net.body(unpkg, "{not json")
        assertEquals(UpdateCheckResult.Failed("tls"), checker.check(73))
        assertEquals(mapOf("reason" to "tls", "archive" to "tls", "jsdelivr" to "offline", "unpkg" to "parse"), reports.single())
    }

    @Test
    fun `a failed check is reported only once per process`() = runBlocking {
        repeat(3) { assertTrue(checker.check(73) is UpdateCheckResult.Failed) }
        assertEquals(1, reports.size)
        assertFalse("no URL in telemetry", reports.single().values.any { "://" in it })
    }

    /**
     * Decision: the FIRST source that answers a valid manifest wins -- archive.org is the source of
     * truth, the mirrors are only for when it cannot be reached. So a newer mirror never overrides a
     * reachable archive (the mirror step runs after archive is verified, so this can't happen in a
     * real release anyway), and an older mirror never offers a downgrade.
     */
    @Test
    fun `first successful source wins, a reachable archive is not overridden by a mirror`() = runBlocking {
        net.body(archive, manifest(73, archiveApk))
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        assertEquals(UpdateCheckResult.UpToDate, checker.check(73))
        assertEquals(listOf(archive), net.requested)
    }

    @Test
    fun `a stale mirror while archive is down never offers a downgrade`() = runBlocking {
        net.fail(archive, UnknownHostException("archive.org"))
        net.body(jsdelivr, manifest(72, jsdelivrApk("1.0.2")))
        assertEquals(UpdateCheckResult.UpToDate, checker.check(73))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a manifest whose APK url is on an unknown host is rejected and the next source used`() = runBlocking {
        net.body(archive, manifest(74, "https://evil.example.com/kino.apk"))
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        assertEquals(jsdelivrApk("1.0.4"), (checker.check(73) as UpdateCheckResult.Available).info.url)
    }

    @Test
    fun `a manifest with a malformed sha256 is unparsable`() = runBlocking {
        net.body(archive, manifest(74, archiveApk, sha256 = "xyz"))
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        assertEquals(jsdelivrApk("1.0.4"), (checker.check(73) as UpdateCheckResult.Available).info.url)
    }

    @Test
    fun `a legacy manifest without sha256 is still parsed, with an empty sha`() = runBlocking {
        net.body(archive, manifest(74, archiveApk, sha256 = null))
        assertEquals("", (checker.check(73) as UpdateCheckResult.Available).info.sha256)
    }

    @Test
    fun `mirror APK urls are the mirrors' copies of the SAME version and bytes`() = runBlocking {
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        net.body(unpkg, manifest(74, unpkgApk("1.0.4")))
        val info = UpdateInfo(74, "0.74.0", archiveApk, "", sha)
        assertEquals(listOf(jsdelivrApk("1.0.4"), unpkgApk("1.0.4")), checker.mirrorApkUrls(info))
        assertEquals(listOf(jsdelivr, unpkg), net.requested)
    }

    @Test
    fun `mirror APK urls skip a mirror on another version, another sha, or down`() = runBlocking {
        net.body(jsdelivr, manifest(73, jsdelivrApk("1.0.3")))
        net.body(unpkg, manifest(74, unpkgApk("1.0.4"), sha256 = "b".repeat(64)))
        assertEquals(emptyList<String>(), checker.mirrorApkUrls(UpdateInfo(74, "0.74.0", archiveApk, "", sha)))
        net.fail(unpkg, UnknownHostException("unpkg.com"))
        assertEquals(emptyList<String>(), checker.mirrorApkUrls(UpdateInfo(74, "0.74.0", archiveApk, "", sha)))
    }

    /**
     * The mirror publishes ONE latest.json (its url on jsDelivr) that both CDNs serve. A device that got it
     * from unpkg has just proved unpkg reachable, so it downloads unpkg's copy of the same exact version.
     */
    @Test
    fun `a manifest read from unpkg downloads unpkg's copy of the same exact version`() = runBlocking {
        net.fail(archive, UnknownHostException("archive.org"))
        net.fail(jsdelivr, UnknownHostException("cdn.jsdelivr.net"))
        net.body(unpkg, manifest(74, jsdelivrApk("1.0.4")))
        assertEquals(unpkgApk("1.0.4"), (checker.check(73) as UpdateCheckResult.Available).info.url)
    }

    @Test
    fun `mirror APK urls include both CDNs' copies of the one published url`() = runBlocking {
        net.body(jsdelivr, manifest(74, jsdelivrApk("1.0.4")))
        net.body(unpkg, manifest(74, jsdelivrApk("1.0.4")))
        val info = UpdateInfo(74, "0.74.0", archiveApk, "", sha)
        assertEquals(listOf(jsdelivrApk("1.0.4"), unpkgApk("1.0.4")), checker.mirrorApkUrls(info))
    }

    @Test
    fun `twins only map the static-asset-pack package at an exact version`() {
        assertEquals(listOf(jsdelivrApk("1.0.4"), unpkgApk("1.0.4")), OtaSources.npmTwins(unpkgApk("1.0.4")))
        assertEquals(listOf(archiveApk), OtaSources.npmTwins(archiveApk))
        val other = "https://cdn.jsdelivr.net/npm/other-pack@1.0.4/ota/kino.apk"
        assertEquals(listOf(other), OtaSources.npmTwins(other))
        assertEquals(listOf(jsdelivrApk("1")), OtaSources.npmTwins(jsdelivrApk("1")))
    }

    @Test
    fun `only https urls on the three known hosts are accepted as APKs`() {
        assertTrue(OtaSources.isAllowedApkUrl(archiveApk))
        assertTrue(OtaSources.isAllowedApkUrl(jsdelivrApk("1.0.4")))
        assertTrue(OtaSources.isAllowedApkUrl(unpkgApk("1.0.4")))
        assertFalse(OtaSources.isAllowedApkUrl("http://archive.org/download/kino-app/kino.apk"))
        assertFalse(OtaSources.isAllowedApkUrl("https://archive.org.evil.com/kino.apk"))
        assertFalse(OtaSources.isAllowedApkUrl("https://user:pw@archive.org/download/kino-app/kino.apk"))
        assertFalse(OtaSources.isAllowedApkUrl("https://archive.org:8443/download/kino-app/kino.apk"))
        assertFalse(OtaSources.isAllowedApkUrl("not a url"))
    }
}
