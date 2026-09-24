package com.arkiv.player.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = UpdateChecker(OkHttpClient(), server.url("/latest.json").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `returns UpdateInfo when remote versionCode is higher`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"versionCode":2,"versionName":"0.2.0","url":"https://example.com/app.apk","notes":"fix"}
        """.trimIndent()))
        val result = checker.check(currentVersionCode = 1)
        assertNotNull(result)
        assertEquals(2, result!!.versionCode)
        assertEquals("0.2.0", result.versionName)
        assertEquals("https://example.com/app.apk", result.url)
        assertEquals("fix", result.notes)
    }

    @Test
    fun `returns null when remote versionCode equals current`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"versionCode":1,"versionName":"0.1.0","url":"https://example.com/app.apk","notes":""}
        """.trimIndent()))
        assertNull(checker.check(currentVersionCode = 1))
    }

    @Test
    fun `returns null when server returns error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(checker.check(currentVersionCode = 1))
    }

    @Test
    fun `returns null when JSON is malformed`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        assertNull(checker.check(currentVersionCode = 1))
    }

    /** Older manifests still carry a `sha256`; the app no longer verifies it, so parsing must
     *  simply ignore the extra field rather than choke on it. */
    @Test
    fun `a manifest that still carries sha256 is parsed, the field ignored`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"versionCode":2,"versionName":"0.2.0","url":"https://example.com/app.apk","notes":"fix",
             "sha256":"abcd1234ef"}
        """.trimIndent()))
        val result = checker.check(currentVersionCode = 1)
        assertNotNull(result)
        assertEquals(2, result!!.versionCode)
        assertEquals("https://example.com/app.apk", result.url)
    }

    @Test
    fun `default url points at the archive-dot-org manifest, not the old private GitHub release`() {
        assertEquals(
            "https://archive.org/download/kino-app/latest.json",
            UpdateChecker.DEFAULT_URL,
        )
    }
}
