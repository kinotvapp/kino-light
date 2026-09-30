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
 * hosts only, over https only ([PluginHttp.Request.sealedTo]); [crypto] takes markers in its
 * key-like fields only. What goes back to the plugin -- the final URL, headers, text body (and its
 * base64 twin), an error message, a cookie value, a `kino.crypto` answer -- and every `kino.log`
 * line have any declared value (opened for that, see [PluginSecrets]), in every form [PluginSecrets.redact]
 * knows, swapped back for its marker.
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

    /**
     * `kino.crypto` with sealed values (spec §5): a marker is swapped for its value only in what an
     * op reads as a key -- `key` (encrypt, decrypt), the HMAC `key`, pbkdf2's `password` and `salt`.
     * A marker in `data`, `iv` or `aad` is refused with [PluginCrypto.SEALED_REFUSED], whatever the
     * key is: those are paths that hand the value back or let it be computed. Data comes straight
     * back out of a decrypt. An iv or aad does too, even under a sealed key, because the plugin can
     * use that key as a block cipher: the same block decrypted with CBC under the sealed iv and with
     * ECB, XORed, is the iv -- and with the key's own marker as the iv, that is the key; the aad
     * falls the same way to GHASH, whose key E_K(0) ECB also gives. A cipher key (encrypt, decrypt)
     * with a marker must be EXACTLY one marker, nothing before or after it: the rest of a longer key
     * would be known, and peeling it off shrinks the search to the secret alone (des-ede3 with
     * `m + 'A'.repeat(16)` is single DES under the secret's first 8 bytes, 2^56). And only for an AES
     * cipher: a sealed des-ede3 key is refused too, since read under a JS-chosen `keyEncoding` its
     * bytes can carry little entropy each, which puts the key in reach of a search. An HMAC key and
     * pbkdf2's password and salt may join a marker with other text (OAuth 1's
     * `consumerSecret&tokenSecret`): HMAC mixes its whole key through a hash, so a known part never
     * splits the unknown one off. A marker anywhere else stays text. The answer is redacted like
     * everything else that returns to the plugin.
     */
    override fun crypto(opJson: String): String {
        val s = secrets ?: return PluginCrypto.run(opJson)
        val o = try {
            JSONObject(opJson)
        } catch (e: org.json.JSONException) {
            return PluginCrypto.run(opJson)
        }
        fun hasMarker(field: String) = (o.opt(field) as? String)?.let(s::containsMarker) == true
        val op = o.optString("op")
        val cipherOp = op == "encrypt" || op == "decrypt"
        val partialCipherKey = cipherOp && hasMarker("key") && !s.isMarker(o.getString("key"))
        val nonAesSealedKey = cipherOp && hasMarker("key") && !o.optString("alg").startsWith("aes-")
        if (hasMarker("data") || hasMarker("iv") || hasMarker("aad") || partialCipherKey || nonAesSealedKey) {
            return JSONObject().put("error", PluginCrypto.SEALED_REFUSED).toString()
        }
        val keyLike = when (op) {
            "hmac", "encrypt", "decrypt" -> listOf("key")
            "pbkdf2" -> listOf("password", "salt")
            else -> emptyList()
        }
        try {
            for (field in keyLike) if (hasMarker(field)) o.put(field, s.substitute(o.getString(field)))
        } catch (e: SealException) {
            // A seal that won't open on this build: a fixed message, never a value.
            return JSONObject().put("error", e.message.orEmpty()).toString()
        }
        val answer = JSONObject(PluginCrypto.run(o.toString()))
        for (k in answer.keys().asSequence().toList()) (answer.opt(k) as? String)?.let { answer.put(k, s.redact(it)) }
        return answer.toString()
    }

    override suspend fun fetch(requestJson: String): String = try {
        val resp = http.fetch(request(JSONObject(requestJson)))
        val text = resp.text?.let(::redact)
        JSONObject().put("ok", resp.ok).put("status", resp.status).put("url", redact(resp.url))
            .put("headers", JSONObject(resp.headers.mapValues { redact(it.value) }))
            .apply { text?.let { put("text", it) }; base64(resp, text)?.let { put("base64", it) } }
            .toString()
    } catch (e: PluginFetchException) {
        error(e.code, e.message.orEmpty())
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
        val parsed = request.url.toHttpUrlOrNull()
        val inUrl = parsed != null && (s.containsMarker(parsed.encodedPath) || parsed.encodedQuery?.let(s::containsMarker) == true)
        val inHeaders = request.headers.values.any(s::containsMarker)
        val inBody = when (val b = request.body) {
            is PluginHttp.Body.Text -> s.containsMarker(b.text)
            is PluginHttp.Body.Json -> s.containsMarker(b.json)
            is PluginHttp.Body.Form -> b.fields.any { (k, v) -> s.containsMarker(k) || s.containsMarker(v) }
            is PluginHttp.Body.Bytes, null -> false
        }
        if (!inUrl && !inHeaders && !inBody) return null
        // Each value in its context's encoding, so it arrives as exactly ONE value: percent-encoded
        // in the path and the query (a `/`, `&`, `=`, `+` or `#` in it can't split a segment or add a
        // parameter), JSON-escaped in a JSON body (a `"` or `\` can't end the string). PluginSecrets
        // redacts these same forms, so the final URL handed back to the plugin shows markers only.
        val component = PluginSecrets.Encoding.URL_COMPONENT
        val sealedUrl = if (inUrl && parsed != null) {
            parsed.newBuilder().encodedPath(s.substitute(parsed.encodedPath, component))
                .apply { parsed.encodedQuery?.let { encodedQuery(s.substitute(it, component)) } }
                .build().toString()
        } else {
            request.url
        }
        return request.copy(
            url = sealedUrl,
            headers = request.headers.mapValues { (name, value) -> sealedHeader(name, value, s) },
            body = when (val b = request.body) {
                is PluginHttp.Body.Text -> PluginHttp.Body.Text(s.substitute(b.text))
                is PluginHttp.Body.Json -> PluginHttp.Body.Json(s.substitute(b.json, PluginSecrets.Encoding.JSON_STRING))
                is PluginHttp.Body.Form -> PluginHttp.Body.Form(b.fields.map { (k, v) -> s.substitute(k) to s.substitute(v) })
                is PluginHttp.Body.Bytes, null -> b
            },
            sealedTo = s.sealedHosts,
        )
    }

    /**
     * What the plugin gets as `r.base64()`. A binary body's bytes are not scanned (spec §5,
     * Redaction). A text body that isn't UTF-8 also comes with its raw bytes; when redaction changed
     * its text (the [redacted] one), those bytes are re-encoded from the redacted text in the same
     * charset, so the twin says what the text says and never still holds the value. Only the value's
     * bytes change: the rest of the body is its original bytes, unless it held some the charset
     * can't round-trip.
     *
     * The declared charset can be wrong (`charset=UTF-16` over UTF-8 bytes): the text is then garbage
     * redaction can't read while the bytes still hold the value. So the twin's bytes are also read as
     * UTF-8 and as Latin-1, and a twin that holds a value either way is dropped -- the prelude then
     * answers `r.base64()` from the (redacted) text's UTF-8 bytes.
     */
    private fun base64(resp: PluginHttp.Response, redacted: String?): String? {
        val raw = resp.bytesBase64
        if (redacted == null || raw == null) return raw
        val twin = if (redacted == resp.text) {
            raw
        } else {
            // No charset to re-encode with (never, as PluginHttp builds it): no twin rather than the raw one.
            val charset = resp.textCharset ?: return null
            Base64.getEncoder().encodeToString(redacted.toByteArray(charset))
        }
        val s = secrets ?: return twin
        val bytes = Base64.getDecoder().decode(twin)
        return twin.takeUnless { s.containsValue(String(bytes, Charsets.UTF_8)) || s.containsValue(String(bytes, Charsets.ISO_8859_1)) }
    }

    /**
     * A header [value] with [s]'s markers swapped in. One OkHttp can't send (a line break or any
     * other control character but a tab, anything outside ASCII) is refused here, naming the header
     * only: OkHttp would throw with the value in its message, and PluginHttp would drop the header.
     */
    private fun sealedHeader(name: String, value: String, s: PluginSecrets): String {
        if (!s.containsMarker(value)) return value
        val out = s.substitute(value)
        if (out.any { it != '\t' && it !in ' '..'~' }) {
            throw PluginFetchException("invalid_request", "el encabezado ${name.take(40)} no puede llevar este dato sellado: tiene caracteres no permitidos")
        }
        return out
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

    /** Redacted whole, then cut: a cut first could leave a piece of a value no redaction recognizes. */
    private fun error(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", redact(message).take(PluginErrors.MAX_MESSAGE_CHARS))).toString()

    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = storage.get(key)
    override fun storageSet(key: String, value: String, ttlMs: Long?) = storage.set(key, value, ttlMs)
    override fun storageRemove(key: String) = storage.remove(key)
    override fun storageKeys(): String = JSONArray(storage.keys()).toString()
    override fun log(level: String, message: String) = logger("[$pluginId] $level: ${redact(message).take(2000)}")
    override fun config(): String = config.toJson()

    override fun cookieGet(url: String, name: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        // [http]'s own, live host set (see [LiveHosts]): never a copy taken when the runtime opened,
        // which would miss a host approved reactively earlier in this very call.
        if (runCatching { PluginHostGate.check(u, http.hosts, allowInsecureLocalhost) }.isFailure) return null
        return cookies?.get(u, name)?.let(::redact)
    }

    override fun cookiesClear() {
        cookies?.clear()
    }
}
