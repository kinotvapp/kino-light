package com.arkiv.player.data.plugin.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one GitHub API call Kino makes: the repository search that finds community plugins (spec
 * 2026-09-28 §1). The host is a fixed destination of `.claude/reglas.md`, reachable ONLY through
 * [client]: [GithubApiGate] refuses, before any connection, every request that is not a GET of exactly
 * [SEARCH_URL] (query included) without credentials in the URL or in an `Authorization`,
 * `Proxy-Authorization` or `Cookie` header, and redirects are never followed (a 3xx is just a failed
 * search). The client keeps no cookies and never answers an auth challenge; no token is ever sent. This is the only source file allowed to name the
 * host (pinned by `GithubApiTest`): everything else refers to [HOST].
 */
object GithubApi {
    const val HOST = "api.github.com"
    const val SEARCH_PATH = "/search/repositories"
    /**
     * Spelled out in full so the host sweep of `.claude/reglas.md` sees it; `GithubApiTest` pins that
     * it is built on [DiscoveryRules.TOPIC].
     */
    const val SEARCH_URL = "https://api.github.com/search/repositories?q=topic:kino-plugin+fork:false&sort=stars&order=desc&per_page=50"
    private val SEARCH_HTTP_URL: HttpUrl = SEARCH_URL.toHttpUrl()

    /** Request headers that would carry a credential; the gate refuses a request that has any. */
    private val CREDENTIAL_HEADERS = listOf("Authorization", "Proxy-Authorization", "Cookie")

    /** A search answer bigger than this is not read (50 results are ~300 KB). */
    const val MAX_SEARCH_BYTES = 1024 * 1024

    /** Exactly [SEARCH_URL] (query included), by GET, and nothing else. */
    fun allows(method: String, url: HttpUrl): Boolean =
        method == "GET" && url == SEARCH_HTTP_URL && url.scheme == "https" && url.host == HOST &&
            url.port == 443 && url.encodedPath == SEARCH_PATH && url.username.isEmpty() && url.password.isEmpty()

    /** [allows] plus: no credential header of any kind. */
    fun allows(request: Request): Boolean =
        allows(request.method, request.url) && CREDENTIAL_HEADERS.none { request.header(it) != null }

    /** [base] with the gate, no redirects, no HTTP cache and a bounded call. */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(GithubApiGate)
        .build()
}

/** Application interceptor: runs before OkHttp connects, so a refused request never leaves the device. */
object GithubApiGate : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!GithubApi.allows(request)) throw IOException("blocked: only the GitHub repository search is allowed")
        return chain.proceed(request)
    }
}

/** One search answer as discovery needs it. [headers] names are lowercased; [body] is null unless 2xx and within [GithubApi.MAX_SEARCH_BYTES]. */
class GithubResponse(val code: Int, val body: String?, val headers: Map<String, String>)

/** Runs the search; throws [IOException] when there is no answer at all (offline, timeout). */
fun interface GithubTransport {
    suspend fun search(): GithubResponse
}

class OkHttpGithubTransport(base: OkHttpClient, private val userAgent: String) : GithubTransport {
    private val client = GithubApi.client(base)

    override suspend fun search(): GithubResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(GithubApi.SEARCH_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { r ->
            val headers = r.headers.names().associate { it.lowercase() to r.header(it).orEmpty() }
            val source = r.body?.source()
            val body = when {
                !r.isSuccessful || source == null -> null
                source.request(GithubApi.MAX_SEARCH_BYTES + 1L) -> null
                else -> source.buffer.readUtf8()
            }
            GithubResponse(r.code, body, headers)
        }
    }
}
