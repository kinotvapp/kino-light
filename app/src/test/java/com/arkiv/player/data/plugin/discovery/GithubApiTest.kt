package com.arkiv.player.data.plugin.discovery

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class GithubApiTest {
    @Test fun `the search URL is the spec's and the gate allows exactly it`() {
        assertEquals(
            "https://api.github.com/search/repositories?q=topic:kino-plugin+fork:false&sort=stars&order=desc&per_page=50",
            GithubApi.SEARCH_URL,
        )
        assertTrue(GithubApi.allows("GET", GithubApi.SEARCH_URL.toHttpUrl()))
        listOf(
            "http://api.github.com/search/repositories",
            "https://api.github.com:8443/search/repositories",
            "https://api.github.com/search/code?q=x",
            "https://api.github.com/repos/o/r",
            "https://user:pw@api.github.com/search/repositories",
            "https://api.github.com.evil.example/search/repositories",
            "https://github.com/search/repositories",
            "https://raw.githubusercontent.com/o/r/HEAD/kino-plugin.json",
            "https://api.github.com/search/repositories",
            "https://api.github.com/search/repositories?q=user:someone",
            "https://api.github.com/search/repositories?q=topic:kino-plugin+fork:false&sort=stars&order=desc&per_page=100",
            GithubApi.SEARCH_URL + "&page=2",
            GithubApi.SEARCH_URL + "#x",
        ).forEach { assertFalse(it, GithubApi.allows("GET", it.toHttpUrl())) }
        assertFalse(GithubApi.allows("POST", GithubApi.SEARCH_URL.toHttpUrl()))
    }

    @Test fun `the client refuses everything else before connecting and never follows a redirect`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = GithubApi.client(OkHttpClient())
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(Authenticator.NONE, client.authenticator)
            assertSame(Authenticator.NONE, client.proxyAuthenticator)
            val refused = listOf(
                Request.Builder().url(server.url("/search/repositories")).build(),
                Request.Builder().url("https://api.github.com/repos/o/r").build(),
                Request.Builder().url("https://raw.githubusercontent.com/o/r/HEAD/kino-plugin.json").build(),
                Request.Builder().url(GithubApi.SEARCH_URL).post("{}".toRequestBody()).build(),
                Request.Builder().url(GithubApi.SEARCH_URL.replace("per_page=50", "per_page=100")).build(),
                Request.Builder().url(GithubApi.SEARCH_URL).header("Authorization", "token x").build(),
                Request.Builder().url(GithubApi.SEARCH_URL).header("Proxy-Authorization", "Basic x").build(),
                Request.Builder().url(GithubApi.SEARCH_URL).header("Cookie", "a=b").build(),
            )
            refused.forEach { request ->
                val e = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
                assertTrue("${request.method} ${request.url}", e is IOException && e.message.orEmpty().startsWith("blocked"))
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `the search URL is a literal the host sweep sees, built on the discovery topic`() {
        val source = File("src/main/java/com/arkiv/player/data/plugin/discovery/GithubApi.kt").readText()
        assertTrue(source.contains("\"" + GithubApi.SEARCH_URL + "\""))
        assertTrue(GithubApi.SEARCH_URL.contains("q=topic:${DiscoveryRules.TOPIC}+fork:false"))
    }

    @Test fun `only the discovery client names the GitHub API host`() {
        val hits = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("api.github.com") }
            .map { it.name }.toList()
        assertEquals(listOf("GithubApi.kt"), hits)
    }
}
