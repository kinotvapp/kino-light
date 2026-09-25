package com.arkiv.player.data.plugin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One plugin's cookie jar: OkHttp's own `Set-Cookie` parsing (Domain, Path, Expires, Max-Age,
 * Secure, the public-suffix rule) plus Kino's rules — a cookie is kept only when the response came
 * from a host the plugin may reach ([hosts]), at most [MAX_PER_HOST] per cookie domain and
 * [MAX_TOTAL_BYTES] in total (the oldest go first), and `Secure` cookies only travel over https
 * (OkHttp's `Cookie.matches`).
 *
 * Persisted to [file] (the plugin's `plugin-data/<id>/cookies.json`, app-private, not encrypted
 * beyond that) so a login survives the runtime's idle close and app restarts. Session cookies are
 * persisted too, for the same reason. Loaded lazily on first use and written by [saveIfChanged]
 * after each request that changed it — always on the caller's thread, which is `PluginHttp`'s IO
 * thread, never Main. Cleared by [clear] (`kino.cookies.clear()`), by any settings change and by
 * uninstall (both delete the file with the rest of the plugin's data).
 */
class PluginCookies(
    private val file: File,
    private val hosts: EffectiveHosts,
    private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {
    /** Insertion order is age order: eviction removes from the front. */
    private val cookies: MutableList<Cookie> by lazy { load() }
    private var changed = false

    /** Set by [retire]: this jar belongs to a session that no longer exists and keeps nothing. */
    private var retired = false

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (retired || !allowed(url)) return
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > clock()) this.cookies += c
            changed = true
        }
        enforceCaps()
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!allowed(url)) return emptyList()
        dropExpired()
        return cookies.filter { it.matches(url) }
    }

    /** `kino.cookies.get(url, name)`: the value, or null. The caller already checked [url] is effective. */
    @Synchronized fun get(url: HttpUrl, name: String): String? = loadForRequest(url).lastOrNull { it.name == name }?.value

    @Synchronized fun clear() {
        cookies.clear()
        file.delete()
        changed = false
    }

    /**
     * After a settings change: clears the jar AND stops it for good, so a call of the old session
     * that is still finishing can't write its cookies back (the next runtime opens a fresh jar).
     */
    @Synchronized fun retire() {
        clear()
        retired = true
    }

    /** Writes the jar if anything changed since the last write. Call off the main thread. */
    @Synchronized fun saveIfChanged() {
        if (!changed || retired) return
        changed = false
        dropExpired()
        val array = JSONArray()
        cookies.forEach { c ->
            array.put(
                JSONObject().put("name", c.name).put("value", c.value).put("domain", c.domain).put("path", c.path)
                    .put("expiresAt", c.expiresAt).put("secure", c.secure).put("httpOnly", c.httpOnly).put("hostOnly", c.hostOnly),
            )
        }
        runCatching { writeFileAtomically(file, array.toString().toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized fun size(): Int = cookies.size

    /** WHICH host, not how: the request itself already passed [PluginHostGate] (scheme included). */
    private fun allowed(url: HttpUrl): Boolean = hosts.let { h -> h.userHostFor(url) != null || HostRules.matches(url.host, h.declared) }

    private fun dropExpired() {
        val now = clock()
        if (cookies.removeAll { it.expiresAt <= now }) changed = true
    }

    private fun enforceCaps() {
        dropExpired()
        cookies.groupBy { it.domain }.values.forEach { same ->
            if (same.size > MAX_PER_HOST) cookies.removeAll(same.take(same.size - MAX_PER_HOST).toSet())
        }
        while (cookies.isNotEmpty() && cookies.sumOf { bytes(it) } > MAX_TOTAL_BYTES) cookies.removeAt(0)
    }

    private fun bytes(c: Cookie): Int = c.name.length + c.value.length + c.domain.length + c.path.length

    private fun load(): MutableList<Cookie> {
        val text = runCatching { file.takeIf { it.length() <= MAX_FILE_BYTES }?.readText() }.getOrNull() ?: return ArrayList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return ArrayList()
        val out = ArrayList<Cookie>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val b = runCatching {
                Cookie.Builder().name(o.getString("name")).value(o.getString("value")).path(o.getString("path"))
                    .expiresAt(o.getLong("expiresAt"))
                    .apply {
                        if (o.optBoolean("hostOnly")) hostOnlyDomain(o.getString("domain")) else domain(o.getString("domain"))
                        if (o.optBoolean("secure")) secure()
                        if (o.optBoolean("httpOnly")) httpOnly()
                    }.build()
            }.getOrNull() ?: continue
            out += b
        }
        return out
    }

    companion object {
        const val MAX_PER_HOST = 50
        const val MAX_TOTAL_BYTES = 64 * 1024

        /** A file bigger than the caps can produce is someone else's: ignored, never parsed. */
        private const val MAX_FILE_BYTES = 4L * MAX_TOTAL_BYTES
        const val FILE_NAME = "cookies.json"
    }
}
