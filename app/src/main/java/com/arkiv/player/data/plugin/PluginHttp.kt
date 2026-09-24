package com.arkiv.player.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostNotAllowedException(val host: String) : IOException("host no permitido: $host")

/**
 * The single rule of plugin networking: a request (and every redirect hop) goes only to a host the
 * plugin declared and the person approved, over https — and never to an IP literal or a local
 * name, whatever the list says. Used by `kino.fetch` ([PluginHttp]) and by the player
 * ([PluginStreamGate]). `allowInsecureLocalhost` exists for MockWebServer tests; production code
 * never sets it.
 */
object PluginHostGate {
    fun check(url: HttpUrl, hosts: List<String>, allowInsecureLocalhost: Boolean = false) {
        val testLocalhost = allowInsecureLocalhost && url.host == "localhost"
        if (HostRules.isLocalAddress(url.host) && !testLocalhost) throw HostNotAllowedException(url.host)
        if (!HostRules.matches(url.host, hosts)) throw HostNotAllowedException(url.host)
        if (url.scheme == "https") return
        if (allowInsecureLocalhost && url.host == "localhost") return
        throw IOException("solo se permite https")
    }
}

/**
 * Refuses a declared name that resolves into the local network: loopback, RFC 1918, link-local,
 * "this network" 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10, multicast, IPv6 unique-local and
 * NAT64 64:ff9b::/96 (which maps onto any IPv4 address, private ones included). A public-looking
 * domain must not become a way into the home LAN.
 */
class PluginDns(
    private val allowLoopback: Boolean = false,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val usable = delegate.lookup(hostname).filterNot { a -> isLocal(a) && !(allowLoopback && a.isLoopbackAddress) }
        if (usable.isEmpty()) throw UnknownHostException("$hostname apunta a una dirección privada")
        return usable
    }

    private fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) {
            return true
        }
        val b = a.address.map { it.toInt() and 0xFF }
        return if (a is Inet6Address) {
            (b[0] and 0xFE) == 0xFC || b.take(12) == NAT64_PREFIX
        } else {
            b[0] == 0 || (b[0] == 100 && (b[1] and 0xC0) == 64)
        }
    }

    private companion object {
        val NAT64_PREFIX = listOf(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * `kino.fetch` for one plugin: https only, host-gated per hop, capped (5 MB body, 60 requests per
 * call, 15 s default / 30 s max), with its own in-memory cookie jar.
 *
 * Redirects are followed HERE, not by OkHttp: OkHttp opens the connection to a redirect target
 * before any network interceptor sees it, so an undeclared host would already have been
 * contacted. Checking each `Location` first keeps the refusal on the device.
 */
class PluginHttp(
    base: OkHttpClient,
    private val pluginId: String,
    private val hosts: List<String>,
    appVersion: String,
    private val allowInsecureLocalhost: Boolean = false,
) {
    data class Request(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeoutMs: Long = 0,
    )

    data class Response(
        val ok: Boolean,
        val status: Int,
        val url: String,
        /** Header names lowercased; repeated headers joined with ", ". */
        val headers: Map<String, String>,
        val body: String,
    )

    private val userAgent = "Kino/$appVersion (plugin $pluginId)"
    private val requests = AtomicInteger(0)
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(MemoryCookieJar())
        .dns(PluginDns(allowLoopback = allowInsecureLocalhost))
        .build()

    /** Starts a new plugin call: the 60-request budget is per call. */
    fun beginCall() = requests.set(0)

    suspend fun fetch(req: Request): Response = withContext(Dispatchers.IO) {
        var url = req.url.toHttpUrlOrNull() ?: throw IOException("URL inválida: ${req.url.take(200)}")
        var method = req.method.uppercase().takeIf { it in METHODS } ?: throw IOException("método no permitido: ${req.method}")
        var body = req.body
        val timeout = if (req.timeoutMs > 0) req.timeoutMs.coerceAtMost(MAX_TIMEOUT_MS) else DEFAULT_TIMEOUT_MS
        val callClient = client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        repeat(MAX_REDIRECTS + 1) {
            PluginHostGate.check(url, hosts, allowInsecureLocalhost)
            if (requests.incrementAndGet() > MAX_REQUESTS_PER_CALL) {
                throw IOException("demasiadas solicitudes en una sola llamada (máximo $MAX_REQUESTS_PER_CALL)")
            }
            callClient.newCall(buildRequest(url, method, body, req.headers)).execute().use { resp ->
                val location = resp.header("Location")
                if (resp.code in REDIRECTS && location != null) {
                    url = url.resolve(location) ?: throw IOException("redirección inválida")
                    if (resp.code == 303 || (resp.code in 301..302 && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                } else {
                    val headers = resp.headers.names().associate { name ->
                        name.lowercase() to resp.headers.values(name).joinToString(", ")
                    }
                    return@withContext Response(resp.isSuccessful, resp.code, url.toString(), headers, readCapped(resp))
                }
            }
        }
        throw IOException("demasiadas redirecciones")
    }

    private fun buildRequest(url: HttpUrl, method: String, body: String?, headers: Map<String, String>): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> if (k.lowercase() !in FORBIDDEN_HEADERS) runCatching { b.header(k, v) } }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) b.header("User-Agent", userAgent)
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
        val requestBody = if (method in BODY_METHODS) (body ?: "").toRequestBody(type) else null
        return b.method(method, requestBody).build()
    }

    private fun readCapped(resp: okhttp3.Response): String {
        val responseBody = resp.body ?: return ""
        val source = responseBody.source()
        if (source.request(MAX_BODY_BYTES + 1L)) throw IOException("respuesta demasiado grande (más de 5 MB)")
        val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return source.buffer.readString(charset)
    }

    private class MemoryCookieJar : CookieJar {
        private val jar = LinkedHashMap<String, Cookie>()

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { jar["${it.name}|${it.domain}|${it.path}"] = it }
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            jar.values.removeAll { it.expiresAt < now }
            return jar.values.filter { it.matches(url) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_TIMEOUT_MS = 30_000L
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_REQUESTS_PER_CALL = 60
        private const val MAX_REDIRECTS = 10
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "cookie2")
    }
}
