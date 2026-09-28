package com.arkiv.player.data.live

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads a declared playlist or guide, refusing (IOException) anything over [maxBytes]. */
fun interface LivePlaylistFetcher {
    suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray
}

/**
 * Downloads a declared playlist or guide for the app to parse. [client] MUST be
 * `PluginStreamHttp.client(base, plugin.hosts)`: the strict gate (declared hosts and typed servers,
 * every redirect hop, no LAN by DNS), never the `liveStreamHosts: "any"` one. A body that stops
 * halfway throws (okio's read fails), so a cut download never replaces a good saved copy.
 */
class PluginPlaylistFetcher(private val client: OkHttpClient) : LivePlaylistFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("playlist answered ${r.code}")
            val source = r.body?.source() ?: throw IOException("empty playlist response")
            if (source.request(maxBytes + 1)) throw IOException("playlist over ${maxBytes / (1024 * 1024)} MB")
            source.buffer.readByteArray()
        }
    }
}
