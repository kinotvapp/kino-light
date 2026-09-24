package com.arkiv.player.data.plugin

import org.json.JSONObject

/**
 * The production [PluginHost]: [PluginHttp] for the network, [PluginStorage] for `kino.storage`.
 *
 * [PluginHttp]'s 60-request budget is reset with [PluginHttp.beginCall] once per *top-level* plugin
 * capability call (`search`/`home`/`episodes`/`resolve`), not once per [fetch]: `fetch` here runs
 * once per `kino.fetch()` inside that call, so resetting here would make the cap unenforceable. The
 * caller that owns both this [http] and the matching [PluginRuntime] — a future `PluginRuntimePool`
 * — calls `http.beginCall()` immediately before `runtime.call(...)`, and must serialize calls to the
 * same runtime (e.g. one in flight at a time per plugin) so two calls can't interleave and clobber
 * each other's budget. See `PluginRuntimeTest`'s MockWebServer end-to-end test for the call site.
 */
class DefaultPluginHost(
    private val pluginId: String,
    private val http: PluginHttp,
    private val storage: PluginStorage,
    private val logger: (String) -> Unit = { android.util.Log.i("KinoPlugin", it) },
) : PluginHost {
    override suspend fun fetch(requestJson: String): String {
        val o = JSONObject(requestJson)
        val headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        val resp = http.fetch(
            PluginHttp.Request(
                url = o.getString("url"),
                method = o.optString("method", "GET"),
                headers = headers,
                body = if (o.isNull("body")) null else o.optString("body"),
                timeoutMs = o.optLong("timeoutMs", 0),
            ),
        )
        return JSONObject().put("ok", resp.ok).put("status", resp.status).put("url", resp.url)
            .put("headers", JSONObject(resp.headers)).put("body", resp.body).toString()
    }

    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = storage.get(key)
    override fun storageSet(key: String, value: String) = storage.set(key, value)
    override fun storageRemove(key: String) = storage.remove(key)
    override fun log(level: String, message: String) = logger("[$pluginId] $level: ${message.take(2000)}")
}
