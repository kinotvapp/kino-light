package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The production [PluginHost]: [PluginHttp] for the network, [PluginStorage] for `kino.storage`,
 * [config] for `kino.config`, [PluginCookies] for `kino.cookies`. The hosts `kino.cookies.get`
 * may read are [PluginHttp.hosts] -- one live set per runtime, not a parameter of its own that
 * could drift from what `kino.fetch` allows.
 *
 * [PluginHttp]'s 60-request budget is reset with [PluginHttp.beginCall] once per *top-level* plugin
 * capability call (`search`/`home`/`browse`/`episodes`/`resolve`), not once per [fetch]: `fetch`
 * here runs once per `kino.fetch()` inside that call, so resetting here would make the cap
 * unenforceable. `PluginRuntimePool` calls `http.beginCall()` (via AppGraph's `beforeCall`)
 * immediately before `runtime.call(...)` and serializes calls per plugin.
 *
 * Nothing here logs a config value: [config] goes only to the plugin.
 *
 * [secrets] (a plugin whose manifest declares sealed ones, see [PluginSecrets]): `kino.secret`
 * answers a marker, and [fetch] swaps markers for the plain values in the URL's path and query,
 * every header value and a text/JSON/form body, then sends that request toward the manifest's own
 * hosts only ([PluginHttp.Request.sealedTo]). What goes back to the plugin -- the final URL,
 * headers, text body, an error message -- has any opened value swapped back for its marker.
 */
class DefaultPluginHost(
    private val pluginId: String,
    private val http: PluginHttp,
    private val storage: PluginStorage,
    private val config: PluginConfig = PluginConfig.EMPTY,
    private val cookies: PluginCookies? = null,
    /** MockWebServer tests only, as in [PluginHttp]. */
    private val allowInsecureLocalhost: Boolean = false,
    private val logger: (String) -> Unit = { android.util.Log.i("KinoPlugin", it) },
    private val secrets: PluginSecrets? = null,
) : PluginHost {
    override fun secret(name: String): String? = secrets?.marker(name)

    override suspend fun fetch(requestJson: String): String = try {
        val resp = http.fetch(request(JSONObject(requestJson)))
        JSONObject().put("ok", resp.ok).put("status", resp.status).put("url", redact(resp.url))
            .put("headers", JSONObject(resp.headers.mapValues { redact(it.value) }))
            // A binary body (base64) is not scanned: spec §5, Redaction.
            .apply { resp.text?.let { put("text", redact(it)) }; resp.bytesBase64?.let { put("base64", it) } }
            .toString()
    } catch (e: PluginFetchException) {
        error(e.code, redact(e.message.orEmpty()))
    } catch (e: SealException) {
        // A seal that won't open on this build (no native X25519): a fixed message, never a value.
        error("invalid_request", e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        error("invalid_request", "solicitud inválida")
    } catch (e: org.json.JSONException) {
        error("invalid_request", "solicitud inválida")
    }

    private fun request(o: JSONObject): PluginHttp.Request {
        val h = o.optJSONObject("headers")
        val headers = h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty()
        // The prelude already restricts `redirect` to PluginHttp.REDIRECT_MODES before it crosses;
        // re-checked here against the same real constant so an unrecognized value is refused, never
        // silently treated as "follow" (a bypassed or future prelude bug must not default-allow).
        val redirect = if (o.has("redirect") && !o.isNull("redirect")) o.getString("redirect") else "follow"
        if (redirect !in PluginHttp.REDIRECT_MODES) throw PluginFetchException("invalid_request", "redirect desconocido")
        val request = PluginHttp.Request(
            url = o.getString("url"),
            method = o.optString("method", "GET"),
            headers = headers,
            body = o.optJSONObject("body")?.let(::body),
            manualRedirects = redirect == "manual",
            useCookies = o.optBoolean("cookies", true),
            timeoutMs = o.optLong("timeoutMs", 0),
        )
        return secrets?.let { sealed(request, it) } ?: request
    }

    /**
     * [request] with [s]'s markers swapped for their plain values and [PluginHttp.Request.sealedTo]
     * set; null when it carries none. In the URL, only the path and the query: the scheme,
     * userinfo, host and port keep any marker as text (parsing lowercases a host, which already
     * breaks one), so a plain value never becomes a host name -- looked up in DNS, named in a host
     * error, logged -- and the fragment, which never leaves the device, keeps its marker too.
     */
    private fun sealed(request: PluginHttp.Request, s: PluginSecrets): PluginHttp.Request? {
        val url = request.url.toHttpUrlOrNull()
        val inUrl = url != null && (s.containsMarker(url.encodedPath) || url.encodedQuery?.let(s::containsMarker) == true)
        val inHeaders = request.headers.values.any(s::containsMarker)
        val inBody = when (val b = request.body) {
            is PluginHttp.Body.Text -> s.containsMarker(b.text)
            is PluginHttp.Body.Json -> s.containsMarker(b.json)
            is PluginHttp.Body.Form -> b.fields.any { (k, v) -> s.containsMarker(k) || s.containsMarker(v) }
            is PluginHttp.Body.Bytes, null -> false
        }
        if (!inUrl && !inHeaders && !inBody) return null
        val sealedUrl = if (inUrl && url != null) {
            url.newBuilder().encodedPath(s.substitute(url.encodedPath))
                .apply { url.encodedQuery?.let { encodedQuery(s.substitute(it)) } }
                .build().toString()
        } else {
            request.url
        }
        return request.copy(
            url = sealedUrl,
            headers = request.headers.mapValues { s.substitute(it.value) },
            body = when (val b = request.body) {
                is PluginHttp.Body.Text -> PluginHttp.Body.Text(s.substitute(b.text))
                is PluginHttp.Body.Json -> PluginHttp.Body.Json(s.substitute(b.json))
                is PluginHttp.Body.Form -> PluginHttp.Body.Form(b.fields.map { (k, v) -> s.substitute(k) to s.substitute(v) })
                is PluginHttp.Body.Bytes, null -> b
            },
            sealedTo = s.sealedHosts,
        )
    }

    private fun redact(text: String): String = secrets?.redact(text) ?: text

    private fun body(b: JSONObject): PluginHttp.Body {
        val kind = b.optString("kind")
        // Same as `redirect` above: re-checked against the real PluginHttp.BODY_KINDS constant, not
        // just the `when`'s own exhaustiveness, so the rejection is explicit and traceable to it.
        if (kind !in PluginHttp.BODY_KINDS) throw PluginFetchException("invalid_request", "tipo de body desconocido")
        return when (kind) {
            "text" -> PluginHttp.Body.Text(b.getString("value"))
            "json" -> PluginHttp.Body.Json(b.getString("value"))
            "form" -> PluginHttp.Body.Form(
                b.getJSONArray("value").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } },
            )
            "base64" -> PluginHttp.Body.Bytes(
                try {
                    Base64.getDecoder().decode(b.getString("value"))
                } catch (e: IllegalArgumentException) {
                    throw PluginFetchException("invalid_request", "body.base64 no es base64 válido")
                },
            )
            else -> throw PluginFetchException("invalid_request", "tipo de body desconocido")
        }
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message.take(PluginErrors.MAX_MESSAGE_CHARS))).toString()

    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = storage.get(key)
    override fun storageSet(key: String, value: String, ttlMs: Long?) = storage.set(key, value, ttlMs)
    override fun storageRemove(key: String) = storage.remove(key)
    override fun storageKeys(): String = JSONArray(storage.keys()).toString()
    override fun log(level: String, message: String) = logger("[$pluginId] $level: ${message.take(2000)}")
    override fun config(): String = config.toJson()

    override fun cookieGet(url: String, name: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        // [http]'s own, live host set (see [LiveHosts]): never a copy taken when the runtime opened,
        // which would miss a host approved reactively earlier in this very call.
        if (runCatching { PluginHostGate.check(u, http.hosts, allowInsecureLocalhost) }.isFailure) return null
        return cookies?.get(u, name)
    }

    override fun cookiesClear() {
        cookies?.clear()
    }
}
