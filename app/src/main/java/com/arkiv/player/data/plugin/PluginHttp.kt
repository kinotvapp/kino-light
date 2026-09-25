package com.arkiv.player.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A `kino.fetch` failure with the [code] the plugin sees on the thrown error (`e.code`):
 * `host_not_allowed | timeout | network | too_large | invalid_request` (contract.json `fetch.errorCodes`).
 */
open class PluginFetchException(val code: String, message: String) : IOException(message)

class HostNotAllowedException(val host: String) : PluginFetchException("host_not_allowed", "host no permitido: ${host.take(200)}")

/** [PluginDns] refused every address a name resolved to. */
class PrivateAddressException(val hostname: String) : UnknownHostException("$hostname apunta a una dirección privada")

/**
 * The single rule of plugin networking: a request (and every redirect hop) goes only to a host the
 * person approved at install, over https and never to an IP literal or a local name — or to a
 * server the person typed in the plugin's settings, exactly as typed (see [UserHost]). Used by
 * `kino.fetch` ([PluginHttp]) and by the player ([PluginStreamGate]). `allowInsecureLocalhost`
 * exists for MockWebServer tests; production code never sets it.
 */
object PluginHostGate {
    /**
     * Mirrors OkHttp's own `Util.canParseAsIpAddress`: OkHttp resolves a host shaped like this
     * (an IPv4/IPv6 literal, or a bare decimal/octal/hex number `InetAddress` parses as one --
     * "2130706433" is 127.0.0.1) ITSELF and never calls the configured [Dns] for it (measured:
     * [PluginDns.lookup] is never invoked). A typed server shaped this way must be judged HERE,
     * synchronously and before the request -- no later DNS-time hook will ever see it.
     */
    private val IP_SHAPED_HOST = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")

    fun check(url: HttpUrl, hosts: EffectiveHosts, allowInsecureLocalhost: Boolean = false) {
        if (hosts.userHostFor(url) != null) {
            // userHostOf already refused these when the value was saved; a second look costs nothing.
            if (PluginHosts.isForbiddenUserHost(url.host)) throw HostNotAllowedException(url.host)
            if (IP_SHAPED_HOST.matches(url.host) && runCatching { PluginDns(userHostNames = setOf(url.host)).lookup(url.host) }.isFailure) {
                throw HostNotAllowedException(url.host)
            }
            return
        }
        val testLocalhost = allowInsecureLocalhost && url.host == "localhost"
        if (HostRules.isLocalAddress(url.host) && !testLocalhost) throw HostNotAllowedException(url.host)
        if (!HostRules.matches(url.host, hosts.declared)) throw HostNotAllowedException(url.host)
        if (url.scheme == "https") return
        if (testLocalhost) return
        throw PluginFetchException("host_not_allowed", "solo se permite https")
    }

    /**
     * One redirect hop, [from] -> [to]: [to] must pass [check], and a request that started at a
     * user host may only move to that same user host or to a declared host — never to another
     * typed server (spec §1.4).
     */
    fun checkRedirect(from: HttpUrl, to: HttpUrl, hosts: EffectiveHosts, allowInsecureLocalhost: Boolean = false) {
        check(to, hosts, allowInsecureLocalhost)
        val origin = hosts.userHostFor(from) ?: return
        val target = hosts.userHostFor(to) ?: return
        if (target != origin) throw HostNotAllowedException(to.host)
    }
}

/**
 * Refuses a declared name that resolves into the local network: loopback, RFC 1918, link-local,
 * "this network" 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10, multicast, reserved 240.0.0.0/4
 * (with the broadcast address), IPv6 unique-local, and NAT64 64:ff9b::/96, 6to4 2002::/16 and
 * Teredo 2001::/32 (which embed an IPv4 address, private ones included). A public-looking
 * domain must not become a way into the home LAN.
 *
 * A name the person typed as a server ([userHostNames]) may resolve into the LAN — that is the
 * point of it — but never to loopback, link-local or the unspecified address.
 */
class PluginDns(
    private val allowLoopback: Boolean = false,
    private val delegate: Dns = Dns.SYSTEM,
    private val userHostNames: Set<String> = emptySet(),
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val typed = hostname.lowercase().trimEnd('.') in userHostNames
        val usable = delegate.lookup(hostname).filterNot { a ->
            val refused = if (typed) isDevice(a) else isLocal(a)
            refused && !(allowLoopback && a.isLoopbackAddress)
        }
        if (usable.isEmpty()) throw PrivateAddressException(hostname)
        return usable
    }

    private fun isDevice(a: InetAddress): Boolean = a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress

    private fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) {
            return true
        }
        val b = a.address.map { it.toInt() and 0xFF }
        return if (a is Inet6Address) {
            (b[0] and 0xFE) == 0xFC || b.take(12) == NAT64_PREFIX ||
                // 6to4 2002::/16 and Teredo 2001::/32 embed an IPv4 address, private ones included.
                (b[0] == 0x20 && b[1] == 0x02) || (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0)
        } else {
            // 240.0.0.0/4 is reserved (and holds the 255.255.255.255 broadcast).
            b[0] == 0 || (b[0] == 100 && (b[1] and 0xC0) == 64) || b[0] >= 240
        }
    }

    private companion object {
        val NAT64_PREFIX = listOf(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * `kino.fetch` for one plugin: host-gated per hop against [hosts] (declared + the servers typed in
 * its settings; a settings change closes the runtime, so a new instance gets the new set), capped
 * (5 MB body, 60 requests per call, 15 s default / 30 s max), with the plugin's persistent
 * [cookies] jar.
 *
 * Redirects are followed HERE, not by OkHttp: OkHttp opens the connection to a redirect target
 * before any network interceptor sees it, so an undeclared host would already have been
 * contacted. Checking each `Location` first keeps the refusal on the device. `redirect: "manual"`
 * returns the 3xx itself.
 *
 * Runs on [Dispatchers.IO]; the cookie jar is written there too.
 */
class PluginHttp(
    base: OkHttpClient,
    private val pluginId: String,
    private val hosts: EffectiveHosts,
    appVersion: String,
    private val cookies: PluginCookies? = null,
    private val allowInsecureLocalhost: Boolean = false,
) {
    /** A request body as the prelude sends it; see [PluginHttp.Request.body]. */
    sealed interface Body {
        data class Text(val text: String) : Body
        data class Json(val json: String) : Body
        data class Form(val fields: List<Pair<String, String>>) : Body
        data class Bytes(val bytes: ByteArray) : Body
    }

    data class Request(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: Body? = null,
        val manualRedirects: Boolean = false,
        val useCookies: Boolean = true,
        val timeoutMs: Long = 0,
    )

    data class Response(
        val ok: Boolean,
        val status: Int,
        val url: String,
        /** Header names lowercased; repeated headers joined with ", "; never `set-cookie`. */
        val headers: Map<String, String>,
        /** Decoded with the response charset; null for a binary body (see [bytesBase64]). */
        val text: String?,
        /** The raw bytes as base64: for a binary body, or a text body whose charset isn't UTF-8. */
        val bytesBase64: String?,
    )

    private val userAgent = "Kino/$appVersion (plugin $pluginId)"
    private val requests = AtomicInteger(0)
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookies ?: CookieJar.NO_COOKIES)
        .dns(PluginDns(allowLoopback = allowInsecureLocalhost, userHostNames = hosts.userHostNames))
        .build()

    /** Starts a new plugin call: the 60-request budget is per call. */
    fun beginCall() = requests.set(0)

    suspend fun fetch(req: Request): Response = withContext(Dispatchers.IO) {
        try {
            doFetch(req)
        } catch (e: PluginFetchException) {
            throw e
        } catch (e: PrivateAddressException) {
            throw HostNotAllowedException(e.hostname)
        } catch (e: InterruptedIOException) {
            throw PluginFetchException("timeout", "la solicitud tardó demasiado")
        } catch (e: IOException) {
            throw PluginFetchException("network", "error de red: ${(e.message ?: e.javaClass.simpleName).take(200)}")
        } finally {
            cookies?.saveIfChanged()
        }
    }

    private fun doFetch(req: Request): Response {
        var url = req.url.toHttpUrlOrNull() ?: throw invalid("URL inválida: ${req.url.take(200)}")
        var method = req.method.uppercase().takeIf { it in METHODS } ?: throw invalid("método no permitido: ${req.method.take(20)}")
        var body = req.body
        val timeout = if (req.timeoutMs > 0) req.timeoutMs.coerceAtMost(MAX_TIMEOUT_MS) else DEFAULT_TIMEOUT_MS
        val callClient = client.newBuilder()
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .apply { if (!req.useCookies) cookieJar(CookieJar.NO_COOKIES) }
            .build()
        var previous: HttpUrl? = null
        repeat(MAX_REDIRECTS + 1) {
            val from = previous
            if (from == null) PluginHostGate.check(url, hosts, allowInsecureLocalhost)
            else PluginHostGate.checkRedirect(from, url, hosts, allowInsecureLocalhost)
            if (requests.incrementAndGet() > MAX_REQUESTS_PER_CALL) {
                throw invalid("demasiadas solicitudes en una sola llamada (máximo $MAX_REQUESTS_PER_CALL)")
            }
            callClient.newCall(buildRequest(url, method, body, req.headers)).execute().use { resp ->
                val location = resp.header("Location")
                if (resp.code in REDIRECTS && location != null && !req.manualRedirects) {
                    previous = url
                    url = url.resolve(location) ?: throw PluginFetchException("network", "redirección inválida")
                    if (resp.code == 303 || (resp.code in 301..302 && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                } else {
                    return response(resp, url)
                }
            }
        }
        throw PluginFetchException("network", "demasiadas redirecciones")
    }

    private fun response(resp: okhttp3.Response, url: HttpUrl): Response {
        val headers = resp.headers.names()
            .filter { it.lowercase() !in HIDDEN_RESPONSE_HEADERS }
            .associate { name -> name.lowercase() to resp.headers.values(name).joinToString(", ") }
        val bytes = readCapped(resp)
        val type = resp.body?.contentType()
        val charset = type?.charset()
        return if (isText(type)) {
            val text = String(bytes, charset ?: Charsets.UTF_8)
            val utf8 = charset == null || charset == Charsets.UTF_8
            Response(resp.isSuccessful, resp.code, url.toString(), headers, text, if (utf8) null else b64(bytes))
        } else {
            Response(resp.isSuccessful, resp.code, url.toString(), headers, null, b64(bytes))
        }
    }

    private fun buildRequest(url: HttpUrl, method: String, body: Body?, headers: Map<String, String>): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> if (k.lowercase() !in FORBIDDEN_HEADERS) runCatching { b.header(k, v) } }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) b.header("User-Agent", userAgent)
        val declared = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
        val requestBody = if (method in BODY_METHODS) requestBody(body, declared) else null
        return b.method(method, requestBody).build()
    }

    private fun requestBody(body: Body?, declared: MediaType?): RequestBody = when (body) {
        null -> "".toRequestBody(declared)
        is Body.Text -> body.text.toRequestBody(declared)
        is Body.Json -> body.json.toRequestBody(declared ?: JSON)
        is Body.Bytes -> body.bytes.toRequestBody(declared)
        // Always application/x-www-form-urlencoded, UTF-8: that is what `{ form }` means.
        is Body.Form -> FormBody.Builder(Charsets.UTF_8).apply { body.fields.forEach { (k, v) -> add(k, v) } }.build()
    }

    private fun readCapped(resp: okhttp3.Response): ByteArray {
        val responseBody = resp.body ?: return ByteArray(0)
        val source = responseBody.source()
        if (source.request(MAX_BODY_BYTES + 1L)) throw PluginFetchException("too_large", "respuesta demasiado grande (más de 5 MB)")
        return source.buffer.readByteArray()
    }

    private fun invalid(message: String) = PluginFetchException("invalid_request", message)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_TIMEOUT_MS = 30_000L
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_REQUESTS_PER_CALL = 60
        const val MAX_REDIRECTS = 10
        val METHODS = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val ERROR_CODES = listOf("host_not_allowed", "timeout", "network", "too_large", "invalid_request")
        /** `contract.json` `fetch.bodyKinds`; the prelude sends one of these in every request's `body.kind`. */
        val BODY_KINDS = listOf("text", "json", "form", "base64")
        /** `contract.json` `fetch.redirectModes`; the prelude sends one of these as `redirect`. */
        val REDIRECT_MODES = listOf("follow", "manual")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "cookie2")

        /** The jar handles these; `kino.cookies.get` reads it. */
        private val HIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2")
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        /**
         * Text a person would read: no content type (the pre-v2 behavior), `text/…`, JSON, XML,
         * JavaScript, form data, or anything that names a charset. Everything else is bytes.
         */
        fun isText(type: MediaType?): Boolean {
            if (type == null || type.charset() != null) return true
            val sub = type.subtype.lowercase()
            return type.type.equals("text", true) || sub == "json" || sub.endsWith("+json") || sub == "xml" ||
                sub.endsWith("+xml") || sub == "javascript" || sub == "x-javascript" || sub == "x-www-form-urlencoded"
        }
    }
}
