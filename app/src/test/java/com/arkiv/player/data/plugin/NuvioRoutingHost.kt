package com.arkiv.player.data.plugin

import org.json.JSONObject

/**
 * A [PluginHost] whose `kino.fetch` answers by URL: the first route whose key is a substring of the
 * requested URL wins; anything unrouted answers 404. Records every request (url, method, headers,
 * body) so a test can assert what a converted Nuvio scraper, the shim's axios adapter or the TMDB
 * adapter actually sent. `kino.crypto` and `kino.html.select` are the real ones.
 */
internal class NuvioRoutingHost(private val routes: Map<String, Reply>) : PluginHost {
    data class Reply(val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap())
    data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: JSONObject?, val timeoutMs: Int = 0)

    val requests = mutableListOf<Request>()
    val logs = mutableListOf<String>()

    override suspend fun fetch(requestJson: String): String {
        val req = JSONObject(requestJson)
        val url = req.getString("url")
        val h = req.optJSONObject("headers") ?: JSONObject()
        synchronized(requests) {
            requests += Request(url, req.optString("method"), h.keys().asSequence().associateWith { h.getString(it) }, req.optJSONObject("body"), req.optInt("timeoutMs"))
        }
        val reply = routes.entries.firstOrNull { it.key in url }?.value ?: Reply(404, "not found")
        val headers = JSONObject()
        reply.headers.forEach { (k, v) -> headers.put(k.lowercase(), v) }
        return JSONObject().put("ok", reply.status in 200..299).put("status", reply.status).put("url", url)
            .put("headers", headers).put("text", reply.body).toString()
    }

    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = null
    override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
    override fun storageRemove(key: String) = Unit
    override fun log(level: String, message: String) { synchronized(logs) { logs += "$level: $message" } }
    override suspend fun sleep(ms: Long) = Unit

    fun urls(): List<String> = synchronized(requests) { requests.map { it.url } }
}
