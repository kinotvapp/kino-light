package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The production [PluginHost]: [PluginHttp] for the network, [PluginStorage] for `kino.storage`,
 * [PluginCookies] for `kino.cookies`.
 *
 * [PluginHttp]'s 60-request budget is reset with [PluginHttp.beginCall] once per *top-level* plugin
 * capability call (`search`/`home`/`browse`/`episodes`/`resolve`), not once per [fetch]: `fetch`
 * here runs once per `kino.fetch()` inside that call, so resetting here would make the cap
 * unenforceable. `PluginRuntimePool` calls `http.beginCall()` (via AppGraph's `beforeCall`)
 * immediately before `runtime.call(...)` and serializes calls per plugin.
 */
class DefaultPluginHost(
    private val pluginId: String,
    private val http: PluginHttp,
    private val storage: PluginStorage,
    private val cookies: PluginCookies? = null,
    private val hosts: EffectiveHosts = EffectiveHosts(emptyList()),
    /** MockWebServer tests only, as in [PluginHttp]. */
    private val allowInsecureLocalhost: Boolean = false,
    private val logger: (String) -> Unit = { android.util.Log.i("KinoPlugin", it) },
) : PluginHost {
    override suspend fun fetch(requestJson: String): String = try {
        val resp = http.fetch(request(JSONObject(requestJson)))
        JSONObject().put("ok", resp.ok).put("status", resp.status).put("url", resp.url)
            .put("headers", JSONObject(resp.headers))
            .apply { resp.text?.let { put("text", it) }; resp.bytesBase64?.let { put("base64", it) } }
            .toString()
    } catch (e: PluginFetchException) {
        error(e.code, e.message.orEmpty())
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
        return PluginHttp.Request(
            url = o.getString("url"),
            method = o.optString("method", "GET"),
            headers = headers,
            body = o.optJSONObject("body")?.let(::body),
            manualRedirects = redirect == "manual",
            useCookies = o.optBoolean("cookies", true),
            timeoutMs = o.optLong("timeoutMs", 0),
        )
    }

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
    override fun storageSet(key: String, value: String) = storage.set(key, value)
    override fun storageRemove(key: String) = storage.remove(key)
    override fun storageKeys(): String = JSONArray(storage.keys()).toString()
    override fun log(level: String, message: String) = logger("[$pluginId] $level: ${message.take(2000)}")

    override fun cookieGet(url: String, name: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (runCatching { PluginHostGate.check(u, hosts, allowInsecureLocalhost) }.isFailure) return null
        return cookies?.get(u, name)
    }

    override fun cookiesClear() {
        cookies?.clear()
    }
}
