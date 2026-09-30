package com.arkiv.player.data.plugin

import com.arkiv.player.data.net.DohDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A `kino.fetch` failure with the [code] the plugin sees on the thrown error (`e.code`):
 * `host_not_allowed | timeout | network | too_large | invalid_request` (contract.json `fetch.errorCodes`).
 */
open class PluginFetchException(val code: String, message: String) : IOException(message)

open class HostNotAllowedException(val host: String) : PluginFetchException("host_not_allowed", "host no permitido: ${host.take(200)}")

/**
 * The PLAYER's gate refused [host] only because plugin [pluginId] never declared it, and it is a miss
 * reactive approval could close ([PluginHostGate.isPromptableMiss]): an HLS playlist's segments on
 * another CDN, a redirect hop. Thrown only by a client built to ask ([PluginStreamHttp.client]'s
 * `askAboutFor`), so the player can put the question to the person ([PlaybackHostPrompts]); to
 * anything else it is the same `host_not_allowed` as ever, with the same message.
 */
class UndeclaredPlaybackHostException(val pluginId: String, host: String) : HostNotAllowedException(host)

/** [PluginDns] refused every address a name resolved to. */
class PrivateAddressException(val hostname: String) : UnknownHostException("$hostname apunta a una dirección privada")

/**
 * The single rule of plugin networking: a request (and every redirect hop) goes only to a host the
 * person approved at install, over https (or plain http on the one host they approved as
 * `insecureHttp`, see [EffectiveHosts.allowsScheme]) and never to an IP literal or a local name —
 * or to a server the person typed in the plugin's settings, exactly as typed (see [UserHost]). A live
 * channel of a plugin approved for `liveStreamHosts: "any"` ([EffectiveHosts.anyPublicLiveHost])
 * may also reach any public host, over http or https, but still never a local one. Used
 * by `kino.fetch` ([PluginHttp]) and by the player ([PluginStreamGate]), which also carries a
 * Widevine license request. `allowInsecureLocalhost` exists for MockWebServer tests; production
 * code never sets it.
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

    /**
     * [xuper] is non-null ONLY for a stream of the one plugin [XuperPrivilege.grants] (the player
     * learns it from `PluginAccess.Ready.xuper`, computed from the INSTALLED record's address). It
     * is the playback-time twin of [PluginOutput.stream]'s carve-out: a URL the native bridge itself
     * resolved ([XuperStreams.resolved]) skips the declared-host and https checks -- the same two,
     * and only those. A local address is refused first, as for any plugin. With [xuper] null, or
     * for any URL not in it, every check runs exactly as before.
     */
    fun check(url: HttpUrl, hosts: EffectiveHosts, allowInsecureLocalhost: Boolean = false, xuper: XuperStreams? = null) {
        if (hosts.userHostFor(url) != null) {
            // userHostOf already refused these when the value was saved; a second look costs nothing.
            if (PluginHosts.isForbiddenUserHost(url.host)) throw HostNotAllowedException(url.host)
            if (IP_SHAPED_HOST.matches(url.host) && runCatching { PluginDns(userHostNames = setOf(url.host)).lookup(url.host) }.isFailure) {
                throw HostNotAllowedException(url.host)
            }
            return
        }
        if (hosts.anyPublicStreamHost && !hosts.isUserHostName(url.host)) {
            // Live "any" or the broad video permission: one rule for both.
            // Any scheme HttpUrl knows (http or https) on a public IPv4 literal or a public NAME;
            // PluginDns still refuses a name that resolves into the LAN, at connect time. A typed
            // server's NAME on another port or scheme is not "any": PluginDns lets a typed name
            // resolve into the LAN, so it keeps the strict rules below (exactly as typed, or refused).
            if (HostRules.isPublicIpv4Literal(url.host)) return
            if (!HostRules.isLocalAddress(url.host)) return
            throw HostNotAllowedException(url.host)
        }
        val testLocalhost = allowInsecureLocalhost && url.host == "localhost"
        if (HostRules.isLocalAddress(url.host) && !testLocalhost) throw HostNotAllowedException(url.host)
        if (xuper?.resolved(url) == true) return
        if (!HostRules.matches(url.host, hosts.declared)) throw HostNotAllowedException(url.host)
        if (hosts.allowsScheme(url)) return
        if (testLocalhost) return
        throw PluginFetchException("host_not_allowed", "solo se permite https")
    }

    /**
     * One redirect hop, [from] -> [to]: [to] must pass [check], and a request that started at a
     * user host may only move to that same user host or to a declared host — never to another
     * typed server (spec §1.4). With [xuper], [to] is waived only when it is itself in the table
     * (see [check]): coming FROM a bridge-resolved URL grants its redirect target nothing.
     */
    fun checkRedirect(from: HttpUrl, to: HttpUrl, hosts: EffectiveHosts, allowInsecureLocalhost: Boolean = false, xuper: XuperStreams? = null) {
        check(to, hosts, allowInsecureLocalhost, xuper)
        val origin = hosts.userHostFor(from) ?: return
        val target = hosts.userHostFor(to) ?: return
        if (target != origin) throw HostNotAllowedException(to.host)
    }

    /**
     * True only for the ONE gap reactive approval may close: a plain public https host that simply
     * isn't declared yet. Never true for a hard refusal (local address, IP literal, plain http on a
     * host not marked `insecureHttp`), a server the person typed ([EffectiveHosts.userHostFor]), the
     * live "any host" carve-out, or a host that IS declared but failed [check] for some other reason
     * (e.g. the wrong scheme) -- none of those are a missing-host problem reactive approval can fix.
     */
    fun isPromptableMiss(url: HttpUrl, hosts: EffectiveHosts): Boolean {
        if (url.scheme != "https") return false
        if (hosts.userHostFor(url) != null) return false
        if (hosts.anyPublicStreamHost) return false
        if (HostRules.isLocalAddress(url.host)) return false
        // Only a host a manifest could itself have declared: approving one is adding it to
        // `hosts` verbatim, so a shape HostRules refuses there (a trailing dot, an underscore, a
        // single label, a `*`) would be persisted as a pattern that never matches again -- asked
        // about, approved, and then still refused and asked about again on every retry.
        if (!HostRules.isValidPattern(url.host) || url.host.startsWith("*.")) return false
        return !HostRules.matches(url.host, hosts.declared)
    }

    /**
     * The player's gate refused [url] with [refusal]: an [UndeclaredPlaybackHostException] for
     * [askAboutFor] when that refusal is exactly a promptable miss ([isPromptableMiss] against
     * [hosts], the rules `kino.fetch` and a returned Stream use), [refusal] itself otherwise -- and
     * always when [askAboutFor] is null (a download, a license client: nobody to ask, or nothing
     * that should be asked).
     */
    fun playbackRefusal(refusal: HostNotAllowedException, url: HttpUrl, hosts: EffectiveHosts, askAboutFor: String?): HostNotAllowedException =
        if (askAboutFor != null && isPromptableMiss(url, hosts)) UndeclaredPlaybackHostException(askAboutFor, url.host) else refusal
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
    private val delegate: Dns = DohDns,
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
 * With [reactiveApproval] set, a host that [PluginHostGate.isPromptableMiss] calls a genuine gap
 * (not a hard refusal) is asked about in the moment instead of failing outright; see
 * [ReactiveApproval] and [ensureHostAllowed]. Null (every existing caller) keeps today's behavior.
 * An approved host is written into [liveHosts] -- the ONE host set this runtime's cookie jar and
 * `kino.cookies.get` read too (see [LiveHosts]) -- so the rest of the same call sees it everywhere,
 * not only in the next `kino.fetch`.
 *
 * With [calls] (the runtime's [PluginCallTracker], always set in production), the person is asked
 * only on behalf of a call someone is waiting on ([PluginCall.asksAboutHosts]: alive, not a
 * background job, `resolve` or `episodes`); any other miss fails silently as `host_not_allowed`,
 * logged, with nothing remembered.
 *
 * Runs on [Dispatchers.IO]; the cookie jar is written there too.
 */
class PluginHttp(
    base: OkHttpClient,
    private val pluginId: String,
    /** This runtime's shared host set; [PluginCookies] gets the SAME instance in `AppGraph`. */
    val liveHosts: LiveHosts,
    appVersion: String,
    private val cookies: PluginCookies? = null,
    private val allowInsecureLocalhost: Boolean = false,
    /** MockWebServer tests only, as in [PluginStreamHttp.client]: where a declared name resolves. Production keeps the system's. */
    delegateDns: Dns = Dns.SYSTEM,
    private val reactiveApproval: ReactiveApproval? = null,
    private val calls: PluginCallTracker? = null,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) {
    /** Over a fixed host set of its own: tests, and any caller that shares it with nothing else. */
    constructor(
        base: OkHttpClient,
        pluginId: String,
        hosts: EffectiveHosts,
        appVersion: String,
        cookies: PluginCookies? = null,
        allowInsecureLocalhost: Boolean = false,
        delegateDns: Dns = Dns.SYSTEM,
        reactiveApproval: ReactiveApproval? = null,
        calls: PluginCallTracker? = null,
        log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
    ) : this(base, pluginId, LiveHosts(hosts), appVersion, cookies, allowInsecureLocalhost, delegateDns, reactiveApproval, calls, log)

    /**
     * What this plugin may reach right now: declared + typed servers + anything approved reactively
     * since the runtime opened. Always [EffectiveHosts.strict]: neither live "any" nor the broad video
     * permission ever reaches `kino.fetch`, whatever a caller hands in.
     */
    val hosts: EffectiveHosts get() = liveHosts.value.strict

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
        /**
         * Set when a sealed secret was substituted into this request ([PluginSecrets]): the ONLY host
         * patterns it may reach, on every hop -- the manifest's own `hosts`, never a host approved
         * reactively, a typed server or the broad video permission. Checked before the ordinary gate,
         * which still applies on top, so a refusal here never becomes a host question.
         */
        val sealedTo: List<String>? = null,
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

    /**
     * [PluginHttp]'s hook for a host the manifest never declared: ask [requester], and persist the
     * answer via [onApproved]/[onRejected] (a [PluginRegistry] call, kept out of this class so it has
     * no storage dependency of its own). [rejectedHosts] is a snapshot taken when the runtime opened;
     * this instance also grows it in memory as new "no"s come in, so the SAME open runtime never asks
     * about a host twice even before the registry write is visible elsewhere. Null disables the
     * feature entirely: every existing caller and test keeps today's behavior unchanged.
     */
    class ReactiveApproval(
        val pluginName: String,
        val requester: HostApprovalRequester,
        val onApproved: (host: String) -> Unit,
        val onRejected: (host: String) -> Unit,
        rejectedHosts: Set<String>,
    ) {
        @Volatile var rejectedHosts: Set<String> = rejectedHosts
    }

    private val userAgent = "Kino/$appVersion (plugin $pluginId)"
    private val requests = AtomicInteger(0)
    /**
     * One in-flight [HostApprovalRequester.request] per host, so two concurrent misses on the same
     * host share a single prompt; see [askOnce].
     */
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean?>>()
    /** Guards [askOnce]'s winner-only mutation of [liveHosts] and its calls to [ReactiveApproval]'s
     *  callbacks: a plain JVM monitor, never held across a suspension point. [liveHosts] has no
     *  other writer, so its readers (the cookie jar, `kino.cookies.get`) need no lock of their own. */
    private val hostsLock = Any()
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookies ?: CookieJar.NO_COOKIES)
        .dns(PluginDns(allowLoopback = allowInsecureLocalhost, delegate = delegateDns, userHostNames = hosts.userHostNames))
        .eventListenerFactory { SlowCallPhases(pluginId, log) }
        .build()

    /**
     * Where a slow or failed request spent its time: DNS, TCP connect, TLS, or waiting for the
     * answer on a pooled (reused) or fresh connection. Logged only for a request that failed or took
     * more than [SLOW_MS]. Measured need: on the TV a plugin's first request after a few minutes idle
     * could hang its whole 15 s while the retry answered at once, and nothing said which phase hung.
     */
    private class SlowCallPhases(private val pluginId: String, private val log: (String) -> Unit) : okhttp3.EventListener() {
        private val t0 = System.nanoTime()
        private val marks = StringBuilder()
        private fun mark(what: String) {
            if (marks.length < 400) marks.append(what).append('@').append((System.nanoTime() - t0) / 1_000_000).append("ms ")
        }

        override fun dnsStart(call: okhttp3.Call, domainName: String) = mark("dns")
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<InetAddress>) =
            mark("dns-ok(${inetAddressList.size}${if (inetAddressList.any { it is Inet6Address }) ",v6" else ""})")
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) =
            mark("connect(${if (inetSocketAddress.address is Inet6Address) "v6" else "v4"})")
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = mark("tls-ok")
        override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: IOException) =
            mark("connect-failed")
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) =
            mark("conn(${connection.protocol()})")
        override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) = mark("sent")
        override fun responseHeadersStart(call: okhttp3.Call) = mark("answer")

        override fun callEnd(call: okhttp3.Call) = report(call, null)
        override fun callFailed(call: okhttp3.Call, ioe: IOException) = report(call, ioe)

        private fun report(call: okhttp3.Call, failure: IOException?) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (failure == null && ms < SLOW_MS) return
            // A connection that shows up with no dns/connect mark before it was reused from the pool.
            val pooled = "dns" !in marks && "connect(" !in marks
            log(
                "[$pluginId] slow ${call.request().url.host} ${ms} ms" + (if (pooled) " on a pooled connection" else "") +
                    (failure?.let { " failed ${it.javaClass.simpleName}" } ?: "") + ": $marks",
            )
        }

        companion object {
            const val SLOW_MS = 5_000L
        }
    }

    /** Starts a new plugin call: the 60-request budget is per call. */
    fun beginCall() = requests.set(0)

    suspend fun fetch(req: Request): Response = withContext(Dispatchers.IO) {
        // The call this fetch belongs to, read once: what it meets goes on that call's trace.
        val trace = calls?.current?.trace
        try {
            doFetch(req, trace)
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

    private suspend fun doFetch(req: Request, trace: PluginCallTrace?): Response {
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
            req.sealedTo?.let { allowed ->
                if (!HostRules.matches(url.host, allowed)) {
                    log("[$pluginId] fetch with sealed data to ${url.host} refused: not a host its manifest declares")
                    throw PluginFetchException("host_not_allowed", "este plugin no puede enviar datos sellados a ${url.host.take(100)}")
                }
            }
            ensureHostAllowed(from, url, trace)
            if (requests.incrementAndGet() > MAX_REQUESTS_PER_CALL) {
                throw invalid("demasiadas solicitudes en una sola llamada (máximo $MAX_REQUESTS_PER_CALL)")
            }
            executeTraced(callClient, buildRequest(url, method, body, req.headers), url.host, trace, sealed = req.sealedTo != null).use { resp ->
                if (resp.code >= 400) trace?.answered(url.host, resp.code)
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

    /** One request, on [trace] as waiting while it is out, and as a failed site if it never answers. */
    private fun executeTraced(client: OkHttpClient, request: okhttp3.Request, host: String, trace: PluginCallTrace?, sealed: Boolean = false): okhttp3.Response {
        trace?.started(host)
        val t0 = System.nanoTime()
        // Host and path only: a query can carry tokens or keys (TMDB's api_key), never logged. With
        // a sealed value substituted in, the host alone: the path may carry it too.
        val what = if (sealed) "${request.method} $host (datos sellados)" else "${request.method} $host${request.url.encodedPath.take(40)}"
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        try {
            return client.newCall(request).execute().also { log("[$pluginId] fetch $what -> ${it.code} in ${ms()} ms") }
        } catch (e: IOException) {
            val how = when (e) {
                is PluginFetchException, is PrivateAddressException -> null
                is InterruptedIOException -> PluginCallTrace.Failure.TIMEOUT
                is UnknownHostException -> PluginCallTrace.Failure.DNS
                else -> PluginCallTrace.Failure.NETWORK
            }
            how?.let { trace?.failed(host, it) }
            log("[$pluginId] fetch $what -> ${how ?: "refused"} (${e.javaClass.simpleName}) in ${ms()} ms")
            throw e
        } finally {
            trace?.finished(host)
        }
    }

    /**
     * [PluginHostGate.check]/[checkRedirect], with one recovery path: a promptable miss the person
     * approves in the moment is retried once, after [askOnce] has added the host to [hosts] and
     * persisted the decision -- exactly once, even when several concurrent misses on the same host
     * shared one prompt (see [askOnce]). Every refusal goes on [trace] with why.
     */
    private suspend fun ensureHostAllowed(from: HttpUrl?, url: HttpUrl, trace: PluginCallTrace?) {
        try {
            checkOnce(from, url)
        } catch (e: HostNotAllowedException) {
            fun refuse(why: PluginCallTrace.Refusal): Nothing {
                trace?.refused(url.host, why)
                throw e
            }
            val ra = reactiveApproval
            if (ra == null || !PluginHostGate.isPromptableMiss(url, hosts)) refuse(PluginCallTrace.Refusal.NOT_ASKED)
            if (url.host in ra.rejectedHosts) refuse(PluginCallTrace.Refusal.REJECTED_BEFORE)
            if (hosts.declared.size >= ManifestParser.MAX_HOSTS) refuse(PluginCallTrace.Refusal.LIMIT)
            val call = calls?.current
            if (calls != null && (call == null || !call.asksAboutHosts)) {
                // Nobody to ask on this call's behalf: it is over (a scraper's own retries outliving
                // its timeout), a background job, or a search/Home/browse call. Fail as ever,
                // silently: no dialog, nothing remembered.
                log("[$pluginId] ${call?.function ?: "no call running"}: undeclared host ${url.host} not asked (${notAskedWhy(call)})")
                refuse(PluginCallTrace.Refusal.NOT_ASKED)
            }
            when (askOnce(ra, url.host, call)) {
                // Still refused only when the cap filled up while the person was deciding: askOnce
                // then logged why, and this rethrows the original host_not_allowed.
                true -> try {
                    checkOnce(from, url)
                } catch (still: HostNotAllowedException) {
                    trace?.refused(url.host, PluginCallTrace.Refusal.LIMIT)
                    throw still
                }
                false -> refuse(PluginCallTrace.Refusal.REJECTED_NOW)
                // No answer: the call ended while the question was up (it came down unanswered).
                // Without a tracker, a requester that gave up (tests): a timeout, as it always was.
                null -> if (call != null) throw e else throw PluginFetchException("timeout", "no hubo respuesta a tiempo para conectarse a ${url.host}")
            }
        }
    }

    private fun notAskedWhy(call: PluginCall?): String = when {
        call == null || !call.isAlive -> "its call is over"
        !call.interactive -> "background call"
        else -> "${call.function} never asks"
    }

    private fun checkOnce(from: HttpUrl?, url: HttpUrl) {
        if (from == null) PluginHostGate.check(url, hosts, allowInsecureLocalhost) else PluginHostGate.checkRedirect(from, url, hosts, allowInsecureLocalhost)
    }

    /**
     * One prompt per host per [PluginHttp] instance, its side effects applied exactly once no matter
     * how many concurrent misses on that host shared it:
     * - Exactly one caller -- the one whose [pendingApprovals] entry [ConcurrentHashMap.computeIfAbsent]
     *   actually creates -- calls [HostApprovalRequester.request] and, under [hostsLock], mutates
     *   [hosts] / [ReactiveApproval.rejectedHosts] and fires [ReactiveApproval.onApproved]/[onRejected].
     *   Every other concurrent caller only awaits that winner's [CompletableDeferred]; with N concurrent
     *   misses on the same host, [EffectiveHosts.declared] gains the host once, not N times, and the
     *   registry callback fires once, not N times.
     * - With [call] (production), the wait is [PluginCall.askWhileAlive]: the call's clock is paused
     *   for as long as the question is queued or on screen, and the question is cancelled -- null,
     *   nothing recorded -- when the call ends first. Without one (tests), a requester's own `null`
     *   surfaces as a `timeout` [PluginFetchException], as it always did.
     * - [hostsLock] also makes the 20-host cap check-and-add atomic against a DIFFERENT host being
     *   approved by another winner at the same moment: two winners racing at 19 declared hosts can no
     *   longer both add their host and land at 21.
     * - Deliberately not `CoroutineScope(currentCoroutineContext()).async { … }` (as first sketched):
     *   that would parent the shared deferred to whichever caller wins the race, so an unrelated
     *   cancellation further up THAT winner's own call stack (its own timeout, say) would complete the
     *   deferred exceptionally for every OTHER caller waiting on the same host too -- callers who were
     *   never themselves cancelled. A bare [CompletableDeferred] avoids the parent-job coupling, but a
     *   winner's cancellation can still reach a waiting loser through the deferred's own exceptional
     *   completion: a loser that catches a [CancellationException] out of `shared.await()` first checks
     *   [kotlinx.coroutines.ensureActive] on ITS OWN context -- if that doesn't throw, the cancellation
     *   was never really the loser's, so it retries (asking again itself) instead of propagating a
     *   cancellation that isn't its own. `fetch()`'s catch chain has no mapping from a bare
     *   `CancellationException` to any `kino.fetch` error code, so letting it through as-is would kill
     *   the loser's call with an unclassified failure instead of a real answer.
     */
    private suspend fun askOnce(ra: ReactiveApproval, host: String, call: PluginCall?): Boolean? {
        while (true) {
            var mine: CompletableDeferred<Boolean?>? = null
            val shared = pendingApprovals.computeIfAbsent(host) { CompletableDeferred<Boolean?>().also { mine = it } }
            val winning = mine
            if (winning == null) {
                try {
                    return shared.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // rethrows only if THIS caller was cancelled
                    continue // the winner's own cancellation, not ours: the entry is gone, ask afresh
                }
            }
            try {
                // With a call: its clock stopped while the person decides, and the question taken
                // down (null, nothing recorded) if the call ends first.
                val askedAt = System.nanoTime()
                val answer = if (call != null) {
                    log("[$pluginId] ${call.function}: asking about $host; call clock paused with ${call.clock.remainingMs()} ms of its ${call.clock.budgetMs} ms left")
                    call.askWhileAlive { ra.requester.request(pluginId, ra.pluginName, host) }
                } else {
                    ra.requester.request(pluginId, ra.pluginName, host)
                }
                val waited = (System.nanoTime() - askedAt) / 1_000_000
                val outcome = when (answer) {
                    true -> "approved"
                    false -> "rejected"
                    null -> if (call != null) "withdrawn unanswered, its call is over" else "no answer"
                }
                log("[$pluginId] ${call?.function ?: "fetch"}: $host $outcome after $waited ms" + (call?.clock?.remainingMs()?.let { "; call resumes with $it ms" } ?: ""))
                synchronized(hostsLock) {
                    when (answer) {
                        true -> {
                            val current = liveHosts.value
                            if (current.declared.size < ManifestParser.MAX_HOSTS) {
                                liveHosts.value = current.copy(declared = current.declared + host)
                                ra.onApproved(host)
                            } else {
                                // Checked before asking too (ensureHostAllowed); reachable only when a
                                // DIFFERENT host's approval filled the last slot while this prompt was up.
                                log("[$pluginId] host $host approved but not added: already at the ${ManifestParser.MAX_HOSTS}-host limit")
                            }
                        }
                        false -> {
                            ra.rejectedHosts = ra.rejectedHosts + host
                            ra.onRejected(host)
                        }
                        null -> Unit
                    }
                }
                // Removed BEFORE completing, on both paths: a loser resumed by the completion (on a
                // dispatcher that resumes it inline, or on another thread before this one gets further)
                // and looping back to computeIfAbsent must never find this already-completed entry again
                // -- it would await it, get the same cancellation at once, and spin until the removal ran.
                pendingApprovals.remove(host, winning)
                winning.complete(answer)
                return answer
            } catch (e: Throwable) {
                pendingApprovals.remove(host, winning)
                winning.completeExceptionally(e)
                throw e
            }
        }
    }

    /**
     * Mirrors a decision about [host] taken OUTSIDE this instance's own `kino.fetch` -- a URL of the
     * Stream the plugin's `resolve` returned ([StreamHostApproval]), already persisted there -- into
     * this open runtime, the way [askOnce] applies its own: an approved host joins [liveHosts] (same
     * cap, never twice), a refused one joins [ReactiveApproval.rejectedHosts]. Without it the
     * plugin's next `kino.fetch` to that host in this runtime would ask the person again about a
     * host they just answered. Persisting is the caller's job, so no callback fires here.
     */
    fun recordDecision(host: String, approved: Boolean) = synchronized(hostsLock) {
        if (approved) {
            val current = liveHosts.value
            if (!HostRules.matches(host, current.declared) && current.declared.size < ManifestParser.MAX_HOSTS) {
                liveHosts.value = current.copy(declared = current.declared + host)
            }
        } else {
            reactiveApproval?.let { it.rejectedHosts = it.rejectedHosts + host }
        }
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
        /**
         * Headers a plugin may not set. `accept-encoding`: Nuvio scrapers copy a browser's
         * "gzip, deflate, br", and a request that sets it makes OkHttp stop decompressing, so the
         * plugin got compressed bytes as text (PelisPlusHD: "unexpected token" parsing TMDB's JSON on
         * the TV). OkHttp asks for gzip itself and hands the plugin plain text.
         */
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "cookie2", "accept-encoding")

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
