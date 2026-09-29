package com.arkiv.player.playback

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * How [LiveHlsProxy] reaches the live CDNs.
 *
 * Goes through OkHttp only so the hosts can be resolved with [dns] (DoH in the app): an ISP that sinkholes a CDN's
 * host answers the system resolver with a page of its own (measured in Chile: a 301 to a copyright notice), and
 * `HttpURLConnection` has no DNS hook. Redirects are never followed here: the request carries the channel's
 * credentials, so [LiveHlsProxy] decides which one, if any, is worth following.
 */
internal class OriginConnections(resolver: Dns, connectTimeoutMs: Int, readTimeoutMs: Int) {

    /**
     * [resolver]'s answer with the IPv4 addresses first. DoH lists IPv6 first, and OkHttp tries them in order; the system
     * resolver this replaces preferred IPv4, and on the TV this was measured on the IPv6 path to the CDN carried
     * segments at a fraction of the speed.
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String) = resolver.lookup(hostname).sortedBy { it is java.net.Inet6Address }
    }

    private val client = OkHttpClient.Builder()
        .dns(this.dns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .build()

    /** Nothing is sent until the response is first read, like `HttpURLConnection`: the caller times the wait. */
    fun open(url: String, headers: Map<String, String>): OriginResponse {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return OriginResponse(client.newCall(request))
    }
}

/**
 * The part of `HttpURLConnection` [LiveHlsProxy] uses, over an OkHttp [Call]: [responseCode] sends the request and
 * throws the [IOException] of a failed one; from 400 up the body is in [errorStream] and [inputStream] refuses.
 */
internal class OriginResponse(private val call: Call) {

    private var response: Response? = null

    @Synchronized
    private fun response(): Response = response ?: call.execute().also { response = it }

    val responseCode: Int get() = response().code

    val responseMessage: String? get() = response().message.ifEmpty { null }

    fun getHeaderField(name: String): String? = response().header(name)

    val inputStream: InputStream
        get() {
            val r = response()
            if (r.code >= 400) throw IOException("HTTP ${r.code}")
            return r.body?.byteStream() ?: throw IOException("no body")
        }

    val errorStream: InputStream?
        get() {
            val r = response()
            return if (r.code >= 400) r.body?.byteStream() else null
        }

    fun disconnect() {
        call.cancel()
        response?.close()
    }
}
